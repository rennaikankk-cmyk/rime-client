package dev.guizhou.util.render;

import dev.guizhou.util.render.font.HudFonts;
import net.minecraft.client.gui.GuiGraphics;
import dev.guizhou.util.render.GuiTextureCompat;
import net.minecraft.resources.ResourceLocation;

/**
 * HUD panel language on top of {@link ShadcnDraw}. Short-corner framed boards,
 * text first; colour is for status only. Rounded shapes share the one-mesh painter.
 * Colours go through {@link HudAppearance} exactly once.
 */
public final class HudDraw {
    /** Translucent panel body; low RGB bits are the HudAppearance background key. */
    public static final int CARD = 0xE6151820;
    public static final int TRACK = 0xFF1D212C;
    public static final int RULE = 0xB32B3140;

    public static final float RADIUS = 2f, PAD = 6f;
    public static final float LABEL = 6.5f, VALUE = 11f, UNIT = 7f, BODY = 8.5f;

    private static final ResourceLocation ICONS = ResourceLocation.fromNamespaceAndPath("guizhouware", "textures/gui/hud_icons.png");
    private static final int ICON_CELL = 96, ICON_COLUMNS = 8, ATLAS_W = 768, ATLAS_H = 288;

    public enum HudIcon {
        WARNING, ERROR, SUCCESS, INFO, SKULL, TOTEM, HEART, SHIELD,
        SIGNAL, GAUGE, PIN, RADAR, ACTIVITY, PACKAGE, CLOCK, MONITOR,
        BELL, KEY
    }

    private HudDraw() {}

    // ------------------------------------------------------------ palette

    public static int accent() { return HudAppearance.color(ShadcnDraw.ACCENT); }

    /** Second brand stop; follows a customised element accent instead of staying cyan. */
    public static int accent2() { return HudAppearance.color(ShadcnDraw.ACCENT_2); }

    /** Health / durability semantic: >60% success, >30% warning, else danger. */
    public static int grade(float fraction) {
        return fraction > .6f ? ShadcnDraw.SUCCESS : fraction > .3f ? ShadcnDraw.WARNING : ShadcnDraw.DANGER;
    }

    /** Background opacity of the element in scope, for shadows that should vanish with the card. */
    public static float backgroundAlpha() {
        return (HudAppearance.color(0xFF151820) >>> 24) / 255f;
    }

    public static int fade(int color, float alpha) { return ShadcnDraw.alpha(color, alpha); }

    // ------------------------------------------------------------ composites

    /** Thin-line panel: fill plus hairline. No shadow, no glow. */
    public static void card(GuiGraphics g, float x, float y, float w, float h, float r) {
        card(g, x, y, w, h, r, 1f);
    }

    public static void card(GuiGraphics g, float x, float y, float w, float h, float r, float alpha) {
        round(g, x, y, w, h, r, fade(CARD, alpha));
        outline(g, x, y, w, h, r, 1f, fade(0xFF2B3140, alpha));
    }

    /** Brand hairline on a vanilla container panel; keeps the inventory texture intact. */
    public static void containerAccent(GuiGraphics g, float x, float y, float w) {
        if (w <= 20f) return;
        ShadcnDraw.round(g, x + 8f, y + 1f, w - 16f, 1f, 0, fade(ShadcnDraw.ACCENT, .7f));
    }

    /** Thin accent rule along the panel top, between the corners. */
    public static void topGlow(GuiGraphics g, float x, float y, float w, float r, float alpha) {
        float inset = Math.max(r, 2f);
        if (w <= inset * 2) return;
        ShadcnDraw.round(g, x + inset, y, w - inset * 2, 1f, 0, fade(accent(), .55f * alpha));
    }

    /** Track plus solid fill; fraction is clamped. Short corners, not a capsule. */
    public static void bar(GuiGraphics g, float x, float y, float w, float h, float fraction, int fill) {
        float r = Math.min(1f, h / 2f);
        ShadcnDraw.round(g, x, y, w, h, r, TRACK);
        float f = Math.clamp(Float.isFinite(fraction) ? fraction : 0f, 0f, 1f);
        if (f * w >= .5f) ShadcnDraw.round(g, x, y, Math.max(h, w * f), h, r, fill);
    }

    public static void barGradient(GuiGraphics g, float x, float y, float w, float h, float fraction, int left, int right) {
        bar(g, x, y, w, h, fraction, left);
    }

    /** Status text in the semantic colour; no capsule, no badge. */
    public static float pill(GuiGraphics g, String text, float x, float y, float h, float size, int color) {
        float w = ShadcnDraw.width(text, size, HudFonts.SEMI);
        textMid(g, text, x, y + h / 2f, size, HudFonts.SEMI, color);
        return w;
    }

    /** Small framed bind hint. */
    public static float keycap(GuiGraphics g, String text, float x, float y, float h, float size) {
        float w = ShadcnDraw.width(text, size, HudFonts.SEMI) + 6f;
        ShadcnDraw.round(g, x, y, w, h, 1.5f, ShadcnDraw.SURFACE);
        ShadcnDraw.outline(g, x, y, w, h, 1.5f, 1f, ShadcnDraw.BORDER);
        textMid(g, text, x + 3f, y + h / 2f, size, HudFonts.SEMI, ShadcnDraw.MUTED);
        return w;
    }

    public static float keycapWidth(String text, float size) {
        return ShadcnDraw.width(text, size, HudFonts.SEMI) + 6f;
    }

    public static void dot(GuiGraphics g, float cx, float cy, float d, int color) {
        ShadcnDraw.round(g, cx - d / 2f, cy - d / 2f, d, d, d / 2f, color);
    }

    /** Horizontal hairline. */
    public static void rule(GuiGraphics g, float x, float y, float w) {
        ShadcnDraw.round(g, x, y, w, .5f, 0, RULE);
    }

    /** Vertical hairline. */
    public static void ruleV(GuiGraphics g, float x, float y, float h) {
        ShadcnDraw.round(g, x, y, .5f, h, 0, RULE);
    }

    // ------------------------------------------------------------ text

    /** Draws text vertically centred on midY; Geist caps sit slightly above the em box centre. */
    public static void textMid(GuiGraphics g, String text, float x, float midY, float size, int weight, int color) {
        ShadcnDraw.text(g, text, x, midY - size * .56f, size, weight, color);
    }

    public static void textMidRight(GuiGraphics g, String text, float right, float midY, float size, int weight, int color) {
        textMid(g, text, right - ShadcnDraw.width(text, size, weight), midY, size, weight, color);
    }

    public static void textMidCentered(GuiGraphics g, String text, float cx, float midY, float size, int weight, int color) {
        textMid(g, text, cx - ShadcnDraw.width(text, size, weight) / 2f, midY, size, weight, color);
    }

    // ------------------------------------------------------------ icons

    public static void icon(GuiGraphics g, HudIcon icon, float x, float y, float size, int color) {
        color = HudAppearance.color(color);
        if (size <= 0 || (color >>> 24) == 0) return;
        int index = icon.ordinal();
        float factor = size / ICON_CELL;
        var pose = g.pose();
        pose.pushPose();
        try {
            pose.translate(x, y, 0);
            pose.scale(factor, factor, 1);
            GuiTextureCompat.blit(g, ICONS, 0, 0,
                    (float) (index % ICON_COLUMNS * ICON_CELL), (float) (index / ICON_COLUMNS * ICON_CELL),
                    ICON_CELL, ICON_CELL, ATLAS_W, ATLAS_H, color);
        } finally {
            pose.popPose();
        }
    }

    /** Status mark: a short vertical accent, not a badge tile. */
    public static void iconTile(GuiGraphics g, HudIcon icon, float x, float y, float box, int color) {
        ShadcnDraw.round(g, x + box * .35f, y + 1f, 2f, box - 2f, 1f, color);
    }

    // ------------------------------------------------------------ fast rounded shapes

    public static void round(GuiGraphics g, float x, float y, float w, float h, float r, int color) {
        roundRaw(g, x, y, w, h, r, HudAppearance.color(color));
    }

    public static void outline(GuiGraphics g, float x, float y, float w, float h, float r, float thickness, int color) {
        color = HudAppearance.color(color);
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
        shape(g, x, y, w, h, r, thickness, color, true);
    }

    /** Same as {@link #round} for a colour already passed through HudAppearance. */
    public static void roundDirect(GuiGraphics g, float x, float y, float w, float h, float r, int color) {
        roundRaw(g, x, y, w, h, r, color);
    }

    public static void outlineDirect(GuiGraphics g, float x, float y, float w, float h, float r, float thickness, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
        shape(g, x, y, w, h, r, thickness, color, true);
    }

    private static void roundRaw(GuiGraphics g, float x, float y, float w, float h, float r, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
        ShadcnDraw.submitShape(g, x, y, w, h, r, 0, color, color, false);
    }

    private static void shape(GuiGraphics g, float x, float y, float w, float h, float radius, float ring,
                              int color, boolean hollow) {
        ShadcnDraw.submitShape(g, x, y, w, h, radius, ring, color, color, hollow);
    }
}
