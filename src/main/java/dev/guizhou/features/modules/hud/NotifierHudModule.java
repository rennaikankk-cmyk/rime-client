package dev.guizhou.features.modules.hud;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.features.modules.client.HudClientModule;
import dev.guizhou.features.modules.client.HudModule;
import dev.guizhou.features.modules.client.HudPosition;
import dev.guizhou.util.render.HudDraw;
import dev.guizhou.util.render.HudDraw.HudIcon;
import dev.guizhou.util.render.ShadcnDraw;
import dev.guizhou.util.render.UiSounds;
import dev.guizhou.util.render.font.HudFonts;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Corner toast stack fed by {@link Notifications}. Toasts slide in from the
 * nearest screen edge, carry a thin level mark and countdown bar, and
 * collapse smoothly on exit. The whole stack is one draggable box; its
 * alignment and growth direction follow whichever screen quadrant it sits in.
 */
public class NotifierHudModule extends HudModule {

    /** Legacy icon set kept for existing callers; mapped onto toast levels. */
    public enum Icon { DANGER, SKULL, INFO }

    public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private static final float BOX_W = 188f, MIN_W = 116f;
    private static final float H_DOUBLE = 28f, H_SINGLE = 20f, GAP = 3f, R = 2f;
    private static final int MAX_VISIBLE = 4;
    private static final float BOX_H = MAX_VISIBLE * (H_DOUBLE + GAP) - GAP;
    private static final long ENTER_MS = 260, EXIT_MS = 220;
    private static final float TITLE = 8.5f, MESSAGE = 7.5f;

    private static final class Toast {
        final String key;
        Notifications.Level level;
        HudIcon icon;
        String title, message;
        long start, duration, expireAt, exitStart;
        boolean exiting, placed;
        float y, bump;

        Toast(Notifications.Request r, long now) {
            key = r.key();
            apply(r, now);
        }

        void apply(Notifications.Request r, long now) {
            level = r.level();
            icon = r.icon();
            title = r.title();
            message = r.message();
            start = now;
            duration = r.durationMs();
            expireAt = now + duration;
        }

        float height() { return message.isEmpty() ? H_SINGLE : H_DOUBLE; }
    }

    // Render thread only.
    private final List<Toast> toasts = new ArrayList<>();
    private long lastFrame;

    public NotifierHudModule() {
        super("Notifier");
    }

    // ------------------------------------------------------------ legacy API

    public static void push(Icon icon, String message) {
        push(null, icon, Component.literal(message), Notifications.DEFAULT_DURATION_MS);
    }

    public static void push(String key, Icon icon, String message) {
        push(key, icon, Component.literal(message), Notifications.DEFAULT_DURATION_MS);
    }

    public static void push(String key, Icon icon, Component message) {
        push(key, icon, message, Notifications.DEFAULT_DURATION_MS);
    }

    /** Legacy entry; well-known key prefixes get a titled toast instead of a bare line. */
    public static void push(String key, Icon icon, Component message, long durationMs) {
        String text = message == null ? "" : message.getString();
        if (key != null && key.startsWith("module:")) {
            String id = key.substring("module:".length());
            if (text.endsWith(" 已开启") || text.endsWith(" 已关闭")) {
                Notifications.moduleToggled(id, text.substring(0, text.length() - 4), text.endsWith(" 已开启"));
                return;
            }
        }
        Notifications.Level level = switch (icon == null ? Icon.INFO : icon) {
            case DANGER -> Notifications.Level.WARNING;
            case SKULL -> Notifications.Level.ERROR;
            case INFO -> Notifications.Level.INFO;
        };
        HudIcon glyph = icon == Icon.SKULL ? HudIcon.SKULL : null;
        String title = null;
        if (key != null) {
            if (key.startsWith("pops:")) { level = Notifications.Level.WARNING; glyph = HudIcon.TOTEM; title = "图腾爆出"; }
            else if (key.startsWith("death:")) { level = Notifications.Level.ERROR; glyph = HudIcon.SKULL; title = "玩家阵亡"; }
            else if (key.startsWith("gear:")) { level = Notifications.Level.WARNING; glyph = HudIcon.PACKAGE; title = "物资不足"; }
        }
        if (title == null) Notifications.push(key, level, glyph, text, "", durationMs, false);
        else Notifications.push(key, level, glyph, title, text, durationMs, false);
    }

    // ------------------------------------------------------------ render

    @Override
    protected boolean autoPlace() {
        return false; // the box height is constant; nudging it would make toasts jump
    }

    @Override
    public void render(Render2DEvent event) {
        long now = System.currentTimeMillis();
        float dt = lastFrame == 0 ? .016f : Math.min(.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        for (Notifications.Request r : Notifications.drain(now)) adopt(r, now);
        update(now, dt);

        HudClientModule hud = GuizhouWare.moduleManager == null ? null
                : GuizhouWare.moduleManager.getModuleByClass(HudClientModule.class);
        boolean editing = hud != null && hud.isEditorOpen();
        if (toasts.isEmpty() && !editing) {
            bounds(0, 0, 0, 0);
            return;
        }

        Corner corner = hud != null ? hud.notifierCorner.getValue() : Corner.TOP_LEFT;
        boolean left = corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT;
        boolean top = corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT;
        int defX = left ? 4 : Math.round(screenWidth() - BOX_W - 4);
        int below = corner == Corner.BOTTOM_RIGHT && hud != null ? hud.stackHeight(HudPosition.BOTTOM_RIGHT)
                : corner == Corner.BOTTOM_LEFT ? 40 : 0;
        int defY = top ? 4 : Math.round(bottomAnchor() - 4 - below - BOX_H);
        int[] o = origin(defX, defY, (int) BOX_W, (int) BOX_H);
        int ox = o[0], oy = o[1];
        bounds(ox, oy, (int) BOX_W, (int) BOX_H);

        boolean alignLeft = ox + BOX_W / 2f < screenWidth() / 2f;
        boolean fromTop = oy + BOX_H / 2f < screenHeight() / 2f;
        GuiGraphics g = event.getContext();

        if (toasts.isEmpty()) {
            float w = 150f;
            float x = alignLeft ? ox : ox + BOX_W - w;
            float y = fromTop ? oy : oy + BOX_H - H_DOUBLE;
            drawCard(g, x, y, w, H_DOUBLE, .55f, Notifications.Level.INFO.color(),
                    "通知预览", "拖动此区域调整位置", 1f, 0f);
            return;
        }

        float stack = 0;
        for (int i = toasts.size() - 1; i >= 0; i--) {
            Toast t = toasts.get(i);
            float enter = ease(Math.min(1f, (now - t.start) / (float) ENTER_MS));
            if (t.bump > 0) enter = 1f;
            float exit = t.exiting ? ease(Math.min(1f, (now - t.exitStart) / (float) EXIT_MS)) : 0f;
            float h = t.height();
            float slot = (h + GAP) * (t.exiting ? 1f - exit : enter);
            if (!t.placed) { t.y = stack; t.placed = true; }
            else t.y += (stack - t.y) * (1f - (float) Math.exp(-dt * 18f));
            stack += slot;
            if (t.y + h > BOX_H + GAP) continue;

            float w = width(t);
            float travel = (w + 12f) * (t.exiting ? exit : 1f - enter);
            float x = alignLeft ? ox - travel : ox + BOX_W - w + travel;
            float y = fromTop ? oy + t.y : oy + BOX_H - t.y - h;
            float alpha = (t.exiting ? 1f - exit : enter);
            float remain = t.exiting ? 0f : Math.clamp((t.expireAt - now) / (float) t.duration, 0f, 1f);
            drawCard(g, x, y, w, h, alpha, t.level.color(), t.title, t.message, remain, t.bump);
            t.bump = Math.max(0f, t.bump - dt * 3f);
        }
    }

    private void adopt(Notifications.Request r, long now) {
        if (r.key() != null) {
            for (Toast t : toasts) {
                if (r.key().equals(t.key)) {
                    t.apply(r, now);
                    if (t.exiting) { t.exiting = false; }
                    else t.bump = 1f;
                    toasts.remove(t);
                    toasts.add(t); // newest slot
                    playCue(r);
                    return;
                }
            }
        }
        toasts.add(new Toast(r, now));
        playCue(r);
    }

    private static void playCue(Notifications.Request r) {
        if (!r.sound()) return;
        if (r.level() == Notifications.Level.SUCCESS) UiSounds.success();
        else if (r.level() == Notifications.Level.ERROR) UiSounds.error();
    }

    private void update(long now, float dt) {
        int live = 0;
        for (Toast t : toasts) {
            if (!t.exiting && now >= t.expireAt) { t.exiting = true; t.exitStart = now; }
            if (!t.exiting) live++;
        }
        for (int i = 0; i < toasts.size() && live > MAX_VISIBLE; i++) {
            Toast t = toasts.get(i);
            if (t.exiting) continue;
            t.exiting = true;
            t.exitStart = now;
            live--;
        }
        toasts.removeIf(t -> t.exiting && now - t.exitStart >= EXIT_MS);
    }

    private static float width(Toast t) {
        float text = Math.max(ShadcnDraw.width(t.title, TITLE, HudFonts.SEMI), ShadcnDraw.width(t.message, MESSAGE));
        return Math.clamp(text + 16f, MIN_W, BOX_W);
    }

    private static void drawCard(GuiGraphics g, float x, float y, float w, float h, float a, int levelColor,
                                 String title, String message, float remain, float bump) {
        if (a <= .01f) return;
        HudDraw.card(g, x, y, w, h, R, a);
        ShadcnDraw.round(g, x, y + 3f, 2f, h - 6f, 1f, HudDraw.fade(levelColor, (.7f + .3f * bump) * a));
        float tx = x + 10f, maxW = w - 16f;
        if (message.isEmpty()) {
            HudDraw.textMid(g, ShadcnDraw.fit(title, maxW, TITLE), tx, y + h / 2f - .5f, TITLE, HudFonts.SEMI,
                    HudDraw.fade(ShadcnDraw.TEXT, a));
        } else {
            HudDraw.textMid(g, ShadcnDraw.fit(title, maxW, TITLE), tx, y + 9.5f, TITLE, HudFonts.SEMI,
                    HudDraw.fade(ShadcnDraw.TEXT, a));
            HudDraw.textMid(g, ShadcnDraw.fit(message, maxW, MESSAGE), tx, y + 19f, MESSAGE, HudFonts.REGULAR,
                    HudDraw.fade(ShadcnDraw.MUTED, a));
        }
        float bw = w - 8f;
        ShadcnDraw.round(g, x + 4f, y + h - 2f, bw, 1f, 0, HudDraw.fade(HudDraw.TRACK, a));
        if (remain > 0) ShadcnDraw.round(g, x + 4f, y + h - 2f, bw * remain, 1f, 0, HudDraw.fade(levelColor, .8f * a));
    }

    private static float ease(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }
}
