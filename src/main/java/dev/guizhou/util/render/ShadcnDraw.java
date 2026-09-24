package dev.guizhou.util.render;

import dev.guizhou.features.modules.client.ClickGuiModule;
import dev.guizhou.util.render.font.HudFonts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Native GUI primitives shared by the shell screens and the HUD. Rounded shapes
 * rasterize per physical pixel with coverage-blended edges, so corners stay
 * smooth at every GUI scale. No web runtime, no shaders.
 */
public final class ShadcnDraw {
    // Graphite + iris palette. The low RGB bits are HudAppearance lookup keys; keep them unique.
    public static final int BG = 0xFF0F1117, CARD = 0xF2151820, SURFACE = 0xFF1D212C, RAISED = 0xFF252A37,
            BORDER = 0xFF2B3140, TEXT = 0xFFECEEF4, MUTED = 0xFF8A92A6, SUBTLE = 0xFF5D6478,
            ACCENT = 0xFF8B7CFF, ACCENT_2 = 0xFF5CC8FF, SUCCESS = 0xFF4ADE80, WARNING = 0xFFFBBF24, DANGER = 0xFFF87171;

    private static final Identifier ICONS = Identifier.fromNamespaceAndPath("guizhouware", "textures/gui/shell_icons.png");
    private static final int ICON_CELL = 96, ICON_COLUMNS = 8, ATLAS_W = 768, ATLAS_H = 384;

    private ShadcnDraw() {}

    /** Theme accent for shell chrome; HUD elements keep their own per-element accent. */
    public static int accent() {
        ClickGuiModule gui = ClickGuiModule.getInstance();
        if (gui == null) return ACCENT;
        return switch (gui.theme.getValue()) {
            case CUSTOM -> gui.customColor.getValue().getRGB() | 0xFF000000;
            case RAINBOW -> gui.rainbowAt(0).getRGB() | 0xFF000000;
            default -> ACCENT;
        };
    }

    public static int alpha(int color, float factor) {
        int a = Math.round((color >>> 24) * Math.clamp(factor, 0f, 1f));
        return a << 24 | color & 0xFFFFFF;
    }

    public static int mix(int a, int b, float t) {
        t = Math.clamp(t, 0f, 1f);
        int result = 0;
        for (int shift = 0; shift <= 24; shift += 8)
            result |= Math.round(((a >>> shift) & 255) * (1 - t) + ((b >>> shift) & 255) * t) << shift;
        return result;
    }

    // ------------------------------------------------------------ rounded shapes

    public static void round(GuiGraphics g, float x, float y, float w, float h, float radius, int color) {
        color = HudAppearance.color(color);
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
        shape(g, x, y, w, h, radius, 0, color, color, false);
    }

    /** Horizontal two-stop gradient fill. */
    public static void roundGradient(GuiGraphics g, float x, float y, float w, float h, float radius, int left, int right) {
        left = HudAppearance.color(left);
        right = HudAppearance.color(right);
        if (w <= 0 || h <= 0) return;
        shape(g, x, y, w, h, radius, 0, left, right, false);
    }

    /** Ring of the given thickness; the interior stays transparent. */
    public static void outline(GuiGraphics g, float x, float y, float w, float h, float radius, float thickness, int color) {
        color = HudAppearance.color(color);
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
        shape(g, x, y, w, h, radius, thickness, color, color, true);
    }

    public static void panel(GuiGraphics g, float x, float y, float w, float h, float radius) {
        round(g, x, y, w, h, radius, CARD);
        outline(g, x, y, w, h, radius, 1, BORDER);
    }

    /** Soft drop shadow built from a few expanding low-alpha layers. */
    public static void shadow(GuiGraphics g, float x, float y, float w, float h, float radius, float spread, int color) {
        int layers = 5;
        for (int i = layers; i >= 1; i--) {
            float grow = spread * i / layers;
            round(g, x - grow, y - grow * 0.6f + spread * 0.35f, w + grow * 2, h + grow * 2, radius + grow,
                    alpha(color, 0.9f / (layers + 1)));
        }
    }

    private static void shape(GuiGraphics g, float x, float y, float w, float h, float radius, float ring,
                              int left, int right, boolean hollow) {
        var pose = g.pose();
        float k = deviceScale(g);
        pose.pushMatrix();
        try {
            pose.scale(1f / k, 1f / k);
            float X = x * k, Y = y * k, W = w * k, H = h * k;
            float R = Math.max(0, Math.min(radius * k, Math.min(W, H) / 2f));
            float T = hollow ? Math.max(k * 0.75f, ring * k) : 0;
            int top = (int) Math.floor(Y), bottom = (int) Math.ceil(Y + H);
            for (int row = top; row < bottom; row++) {
                float cov = Math.min(Y + H, row + 1) - Math.max(Y, row);
                if (cov <= 0) continue;
                float cy = row + 0.5f;
                if (!span(X, Y, W, H, R, cy, OUTER)) continue;
                if (!hollow || !span(X + T, Y + T, W - 2 * T, H - 2 * T, Math.max(0, R - T), cy, INNER)) {
                    spanFill(g, OUTER[0], OUTER[1], row, cov, left, right, X, W);
                } else {
                    spanFill(g, OUTER[0], INNER[0], row, cov, left, right, X, W);
                    spanFill(g, INNER[1], OUTER[1], row, cov, left, right, X, W);
                }
            }
        } finally {
            pose.popMatrix();
        }
    }

    // Render-thread scratch spans; shape() never nests.
    private static final float[] OUTER = new float[2], INNER = new float[2];

    /** Horizontal extent of a rounded rectangle at scanline centre cy; false outside it. */
    private static boolean span(float x, float y, float w, float h, float r, float cy, float[] out) {
        if (w <= 0 || h <= 0 || cy < y || cy > y + h) return false;
        float inset = 0;
        if (r > 0) {
            float dy = cy < y + r ? y + r - cy : cy > y + h - r ? cy - (y + h - r) : 0;
            if (dy > r) return false;
            inset = r - (float) Math.sqrt(Math.max(0, r * r - dy * dy));
        }
        out[0] = x + inset;
        out[1] = x + w - inset;
        return true;
    }

    private static void spanFill(GuiGraphics g, float a, float b, int row, float coverage, int left, int right, float x0, float w0) {
        if (b <= a) return;
        boolean gradient = left != right;
        int ia = (int) Math.ceil(a), ib = (int) Math.floor(b);
        if (ia > ib) {
            // Whole span inside one physical pixel.
            int px = (int) Math.floor(a);
            g.fill(px, row, px + 1, row + 1, fade(colorAt(left, right, px, x0, w0, gradient), coverage * (b - a)));
            return;
        }
        if (ib > ia) {
            if (gradient) {
                int cl = fade(colorAt(left, right, ia, x0, w0, true), coverage);
                int cr = fade(colorAt(left, right, ib, x0, w0, true), coverage);
                RenderUtil.gradient(g, ia, row, ib, row + 1, cl, cl, cr, cr);
            } else {
                g.fill(ia, row, ib, row + 1, fade(left, coverage));
            }
        }
        if (a < ia) g.fill(ia - 1, row, ia, row + 1, fade(colorAt(left, right, ia - 1, x0, w0, gradient), coverage * (ia - a)));
        if (b > ib) g.fill(ib, row, ib + 1, row + 1, fade(colorAt(left, right, ib, x0, w0, gradient), coverage * (b - ib)));
    }

    private static int colorAt(int left, int right, float px, float x0, float w0, boolean gradient) {
        return gradient ? mix(left, right, (px - x0) / Math.max(1, w0)) : left;
    }

    private static int fade(int color, float coverage) {
        return alpha(color, coverage);
    }

    /** Physical pixels per current GUI unit, including any screen-level pose scale. */
    private static float deviceScale(GuiGraphics g) {
        var m = g.pose();
        float poseScale = (float) Math.sqrt(m.m00() * m.m00() + m.m01() * m.m01());
        double gui = Minecraft.getInstance().getWindow() == null ? 1 : Minecraft.getInstance().getWindow().getGuiScale();
        float k = (float) (poseScale * gui);
        return Float.isFinite(k) && k > 0.25f ? Math.min(k, 8f) : 1f;
    }

    // ------------------------------------------------------------ icons

    public enum Icon {
        COMBAT, MOVEMENT, RENDER, PLAYER, WORLD, CLIENT, FUN, HUD,
        SEARCH, STAR, STAR_FILL, CLOSE, KEYBOARD, FOLDER, RESET, CHEVRON,
        CHECK, PLUS, TRASH, SAVE, PLAY, USERS, SLIDERS, POWER,
        SHIELD, MOVE, EXTERNAL, INFO, ZAP, GRID, LIST, SPARKLE
    }

    public static void icon(GuiGraphics g, Icon icon, float x, float y, float size, int color) {
        color = HudAppearance.color(color);
        if (size <= 0 || (color >>> 24) == 0) return;
        int index = icon.ordinal();
        float factor = size / ICON_CELL;
        var pose = g.pose();
        pose.pushMatrix();
        try {
            pose.translate(x, y);
            pose.scale(factor, factor);
            g.blit(RenderPipelines.GUI_TEXTURED, ICONS, 0, 0,
                    (float) (index % ICON_COLUMNS * ICON_CELL), (float) (index / ICON_COLUMNS * ICON_CELL),
                    ICON_CELL, ICON_CELL, ATLAS_W, ATLAS_H, color);
        } finally {
            pose.popMatrix();
        }
    }

    // ------------------------------------------------------------ text

    public static void text(GuiGraphics g, String text, float x, float y, float size, int color) {
        text(g, text, x, y, size, HudFonts.REGULAR, color);
    }

    public static void text(GuiGraphics g, String text, float x, float y, float size, int weight, int color) {
        HudFonts.draw(g, text, x, y, size, weight, HudAppearance.color(color));
    }

    public static void textCentered(GuiGraphics g, String text, float cx, float y, float size, int weight, int color) {
        text(g, text, cx - width(text, size, weight) / 2f, y, size, weight, color);
    }

    public static void textRight(GuiGraphics g, String text, float right, float y, float size, int weight, int color) {
        text(g, text, right - width(text, size, weight), y, size, weight, color);
    }

    public static float width(String text, float size) { return HudFonts.width(text, size, HudFonts.REGULAR); }

    public static float width(String text, float size, int weight) { return HudFonts.width(text, size, weight); }

    public static String fit(String text, float maxWidth, float size) {
        if (text == null || maxWidth <= 0) return "";
        if (width(text, size) <= maxWidth) return text;
        String suffix = "…";
        if (width(suffix, size) > maxWidth) return "";
        int end = text.length();
        while (end > 0) {
            end = text.offsetByCodePoints(end, -1);
            if (width(text.substring(0, end) + suffix, size) <= maxWidth) return text.substring(0, end) + suffix;
        }
        return suffix;
    }

    public static boolean contains(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
