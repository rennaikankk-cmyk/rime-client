package dev.guizhou.features.modules.hud;

import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.features.modules.client.HudModule;
import dev.guizhou.util.render.ShadcnDraw;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class NotifierHudModule extends HudModule {

    public enum Icon {
        DANGER("⚠", 0xFFFFAA00),
        SKULL("☠", 0xFFFF5555),
        INFO("ℹ", 0xFFFFFF55);

        private final String glyph;
        private final int color;

        Icon(String glyph, int color) {
            this.glyph = glyph;
            this.color = color;
        }
    }

    private static final long DEFAULT_DURATION_MS = 3000;
    private static final long FADE_MS = 400;
    private static final int MAX_VISIBLE = 5;
    private static final float ROW_GAP = 4f;
    private static final float STATS_GAP = 4f;
    private static final float STATS_TOP_OFFSET = 56f;
    private static final float NAME_CLEARANCE = 11f;
    private static final float BUBBLE_HEIGHT = 10f;
    private static final float TEXT = 11f;
    private static final float PAD = 6f;
    private static final float CARD_H = 22f;

    private static final class Entry {
        final String key;
        Icon icon;
        Component message;
        long expiry;

        Entry(String key, Icon icon, Component message, long expiry) {
            this.key = key;
            this.icon = icon;
            this.message = message;
            this.expiry = expiry;
        }
    }

    private static final List<Entry> ENTRIES = new CopyOnWriteArrayList<>();

    public NotifierHudModule() {
        super("Notifier");
    }

    public static void push(Icon icon, String message) {
        push(null, icon, Component.literal(message), DEFAULT_DURATION_MS);
    }

    public static void push(String key, Icon icon, String message) {
        push(key, icon, Component.literal(message), DEFAULT_DURATION_MS);
    }

    public static void push(String key, Icon icon, Component message) {
        push(key, icon, message, DEFAULT_DURATION_MS);
    }

    public static void push(String key, Icon icon, Component message, long durationMs) {
        long expiry = System.currentTimeMillis() + durationMs;
        if (key != null) {
            for (Entry e : ENTRIES) {
                if (key.equals(e.key)) {
                    e.icon = icon;
                    e.message = message;
                    e.expiry = expiry;
                    return;
                }
            }
        }
        ENTRIES.add(new Entry(key, icon, message, expiry));
    }

    @Override
    public void render(Render2DEvent event) {
        long now = System.currentTimeMillis();
        ENTRIES.removeIf(e -> e.expiry <= now);
        if (ENTRIES.isEmpty()) {
            bounds(0, 0, 0, 0);
            return;
        }

        float statsTop = screenHeight() - STATS_TOP_OFFSET;
        if (showingBubbles()) statsTop -= BUBBLE_HEIGHT;
        float anchorY = statsTop - STATS_GAP - NAME_CLEARANCE;

        int total = ENTRIES.size();
        int shown = Math.min(total, MAX_VISIBLE);

        // Fit long messages to the viewport and size the whole stack once,
        // so the entire notification block is one draggable, clamped unit.
        float maxTextW = Math.max(40f, screenWidth() - 48f - PAD * 2f - TEXT - 6f);
        String[] messages = new String[shown];
        float stackW = 0f;
        for (int k = 0; k < shown; k++) {
            Entry e = ENTRIES.get(total - 1 - k);
            messages[k] = ShadcnDraw.fit(e.message.getString(), maxTextW, TEXT);
            float cardW = PAD * 2f + ShadcnDraw.width(e.icon.glyph, TEXT) + 6f
                    + ShadcnDraw.width(messages[k], TEXT);
            stackW = Math.max(stackW, cardW);
        }
        float stackH = shown * (CARD_H + ROW_GAP) - ROW_GAP;

        int defX = Math.round(screenWidth() / 2f - stackW / 2f);
        int defY = Math.round(anchorY - stackH);
        int[] o = origin(defX, defY, (int) stackW, (int) stackH);
        int ox = o[0], oy = o[1];
        bounds(ox, oy, (int) stackW, (int) stackH);

        for (int k = 0; k < shown; k++) {
            Entry e = ENTRIES.get(total - 1 - k);
            float alpha = fade(e, now);
            // k=0 is the newest toast at the bottom of the stack.
            drawToast(event, e, messages[k], ox, oy + stackH - CARD_H - k * (CARD_H + ROW_GAP), stackW, alpha);
        }
    }

    private void drawToast(Render2DEvent event, Entry e, String message, float x, float y, float cardW, float alpha) {
        ShadcnDraw.round(event.getContext(), x, y, cardW, CARD_H, 6f, mulAlpha(0xFF343434, alpha));
        ShadcnDraw.round(event.getContext(), x + 1f, y + 1f, cardW - 2f, CARD_H - 2f, 5f, mulAlpha(0xF5171717, alpha));
        float ty = y + (CARD_H - TEXT) / 2f - 1f;
        float tx = x + PAD;
        ShadcnDraw.text(event.getContext(), e.icon.glyph, tx, ty, TEXT, mulAlpha(e.icon.color, alpha));
        ShadcnDraw.text(event.getContext(), message, tx + ShadcnDraw.width(e.icon.glyph, TEXT) + 6f, ty, TEXT,
                mulAlpha(ShadcnDraw.TEXT, alpha));
    }

    private float fade(Entry e, long now) {
        long remaining = e.expiry - now;
        if (remaining >= FADE_MS) return 1f;
        return Math.max(0f, remaining / (float) FADE_MS);
    }

    private static int mulAlpha(int argb, float alpha) {
        if (alpha >= 1f) return argb;
        int a = (argb >>> 24);
        a = (int) (a * Math.max(0f, Math.min(1f, alpha)));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    private boolean showingBubbles() {
        return mc.player != null && (mc.player.isEyeInFluid(FluidTags.WATER)
                || mc.player.getAirSupply() < mc.player.getMaxAirSupply());
    }
}
