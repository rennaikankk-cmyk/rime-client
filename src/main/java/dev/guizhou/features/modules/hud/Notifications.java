package dev.guizhou.features.modules.hud;

import dev.guizhou.util.render.HudDraw.HudIcon;
import dev.guizhou.util.render.ShadcnDraw;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Corner toast API. Safe from any thread: requests queue here and the
 * Notifier HUD element adopts them on the render thread. A key coalesces
 * repeated events into one live toast (timer restarts, content updates).
 * SUCCESS / ERROR toasts play the interface cue unless {@code silent}.
 */
public final class Notifications {
    public enum Level {
        INFO(ShadcnDraw.ACCENT_2, HudIcon.INFO),
        SUCCESS(ShadcnDraw.SUCCESS, HudIcon.SUCCESS),
        WARNING(ShadcnDraw.WARNING, HudIcon.WARNING),
        ERROR(ShadcnDraw.DANGER, HudIcon.ERROR);

        final int color;
        final HudIcon icon;

        Level(int color, HudIcon icon) {
            this.color = color;
            this.icon = icon;
        }

        public int color() { return color; }
        public HudIcon icon() { return icon; }
    }

    public static final long DEFAULT_DURATION_MS = 3500;
    private static final int PENDING_LIMIT = 32;

    record Request(String key, Level level, HudIcon icon, String title, String message,
                   long durationMs, boolean sound, long createdAt) {}

    private static final ConcurrentLinkedQueue<Request> PENDING = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger SIZE = new AtomicInteger();

    private Notifications() {}

    public static void info(String title, String message) { push(Level.INFO, title, message); }
    public static void success(String title, String message) { push(Level.SUCCESS, title, message); }
    public static void warning(String title, String message) { push(Level.WARNING, title, message); }
    public static void error(String title, String message) { push(Level.ERROR, title, message); }

    public static void push(Level level, String title, String message) {
        push(null, level, null, title, message, DEFAULT_DURATION_MS, true);
    }

    public static void push(Level level, String title, Component message) {
        push(null, level, null, title, text(message), DEFAULT_DURATION_MS, true);
    }

    public static void push(String key, Level level, String title, Component message, long durationMs) {
        push(key, level, null, title, text(message), durationMs, true);
    }

    /**
     * Full form. {@code icon} null uses the level icon; {@code message} may be empty
     * for a single-line toast; {@code sound} only applies to SUCCESS and ERROR.
     */
    public static void push(String key, Level level, HudIcon icon, String title, String message,
                            long durationMs, boolean sound) {
        if (level == null) level = Level.INFO;
        String head = title == null || title.isBlank() ? "" : title.strip();
        String body = message == null ? "" : message.strip();
        if (head.isEmpty() && body.isEmpty()) return;
        if (head.isEmpty()) { head = body; body = ""; }
        long duration = Math.clamp(durationMs <= 0 ? DEFAULT_DURATION_MS : durationMs, 800L, 60_000L);
        PENDING.add(new Request(key, level, icon == null ? level.icon : icon, head, body, duration, sound,
                System.currentTimeMillis()));
        if (SIZE.incrementAndGet() > PENDING_LIMIT && PENDING.poll() != null) SIZE.decrementAndGet();
    }

    /** Module on/off toast; silent because the module toggle already has its own cue. */
    public static void moduleToggled(String id, String displayName, boolean on) {
        push("module:" + id, on ? Level.SUCCESS : Level.INFO, on ? HudIcon.SUCCESS : HudIcon.INFO,
                displayName, on ? "已开启" : "已关闭", 2200, false);
    }

    /** Render thread: requests still worth showing, oldest first. */
    static List<Request> drain(long now) {
        List<Request> out = null;
        Request r;
        while ((r = PENDING.poll()) != null) {
            SIZE.decrementAndGet();
            if (now - r.createdAt() > r.durationMs()) continue; // queued while the element was hidden
            if (out == null) out = new ArrayList<>(4);
            out.add(r);
        }
        return out == null ? List.of() : out;
    }

    private static String text(Component message) {
        return message == null ? "" : message.getString();
    }
}
