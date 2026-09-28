package dev.guizhou.util.render;

import dev.guizhou.features.modules.client.ClickGuiModule;
import dev.guizhou.util.render.font.HudFonts;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.gui.GuiGraphics;
import dev.guizhou.util.render.GuiTextureCompat;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * Native GUI primitives shared by the shell screens and the HUD. A rounded
 * rectangle is one mesh: three body quads and four corner fans. No web runtime.
 */
public final class ShadcnDraw {
    // Night + terracotta. The low RGB bits are HudAppearance lookup keys; keep them unique.
    public static final int BG = 0xFF262624, CARD = 0xF230302E, SURFACE = 0xFF1F1E1C, RAISED = 0xFF3A3935,
            BORDER = 0xFF45443F, TEXT = 0xFFFAF9F5, MUTED = 0xFFC2C0B6, SUBTLE = 0xFF8A887F,
            ACCENT = 0xFFE08A6C, ACCENT_2 = 0xFFD97757, SUCCESS = 0xFF9BBFA6, WARNING = 0xFFE0B15C, DANGER = 0xFFE39A92;

    private static final ResourceLocation ICONS = ResourceLocation.fromNamespaceAndPath("guizhouware", "textures/gui/shell_icons.png");
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

    /**
     * One mesh. Two rings fade from clear to the shadow colour. Not five full rounded fills.
     * A filled panel is 3 body quads + 4×4 corner quads (19). This shadow is 40 quads.
     */
    public static void shadow(GuiGraphics g, float x, float y, float w, float h, float radius, float spread, int color) {
        color = HudAppearance.color(color);
        if (w <= 0 || h <= 0 || spread <= 0 || (color >>> 24) == 0) return;
        Mesh mesh = new Mesh();
        float shift = spread * 0.35f;
        ring(mesh, x - spread, y - spread * 0.65f + shift, w + spread * 2, h + spread * 2, radius + spread,
                x - spread * 0.45f, y - spread * 0.28f + shift * 0.5f, w + spread * 0.9f, h + spread * 0.9f, radius + spread * 0.45f,
                0, alpha(color, 0.22f));
        ring(mesh, x - spread * 0.45f, y - spread * 0.28f + shift * 0.5f, w + spread * 0.9f, h + spread * 0.9f, radius + spread * 0.45f,
                x, y, w, h, radius, alpha(color, 0.22f), alpha(color, 0.62f));
        mesh.submit(g);
    }

    private static void shape(GuiGraphics g, float x, float y, float w, float h, float radius, float ring,
                              int left, int right, boolean hollow) {
        submitShape(g, x, y, w, h, radius, ring, left, right, hollow);
    }

    /** One mesh in the current GUI pose. Corners use four quads each. */
    static void submitShape(GuiGraphics g, float x, float y, float w, float h, float radius, float ring,
                            int left, int right, boolean hollow) {
        if (w <= 0 || h <= 0 || ((left >>> 24) == 0 && (right >>> 24) == 0)) return;
        float r = Math.max(0, Math.min(radius, Math.min(w, h) / 2f));
        Mesh mesh = new Mesh();
        if (!hollow || ring <= 0) {
            fill(mesh, x, y, w, h, r, left, right);
        } else {
            float t = Math.min(ring, Math.min(w, h) / 2f);
            if (t >= Math.min(w, h) / 2f - 0.01f) fill(mesh, x, y, w, h, r, left, right);
            else ring(mesh, x, y, w, h, r, x + t, y + t, w - 2 * t, h - 2 * t, Math.max(0, r - t), left, right);
        }
        mesh.submit(g);
    }

    private static final int CORNER = 4;
    private static final float PI = (float) Math.PI;

    private static void fill(Mesh mesh, float x, float y, float w, float h, float r, int left, int right) {
        if (r <= 0.5f) {
            mesh.quad(x, y, x, y + h, x + w, y + h, x + w, y, left, right, x, w);
            return;
        }
        float y0 = y + r, y1 = y + h - r;
        if (y1 > y0) mesh.quad(x, y0, x, y1, x + w, y1, x + w, y0, left, right, x, w);
        float x0 = x + r, x1 = x + w - r;
        if (x1 > x0) {
            mesh.quad(x0, y, x0, y0, x1, y0, x1, y, left, right, x, w);
            mesh.quad(x0, y1, x0, y + h, x1, y + h, x1, y1, left, right, x, w);
        }
        corner(mesh, x + r, y + r, r, PI, PI * 1.5f, left, right, x, w);
        corner(mesh, x + w - r, y + r, r, -PI / 2f, 0, left, right, x, w);
        corner(mesh, x + w - r, y + h - r, r, 0, PI / 2f, left, right, x, w);
        corner(mesh, x + r, y + h - r, r, PI / 2f, PI, left, right, x, w);
    }

    private static void ring(Mesh mesh, float x, float y, float w, float h, float r,
                             float ix, float iy, float iw, float ih, float ir, int outer, int inner) {
        if (iw <= 0 || ih <= 0) {
            fill(mesh, x, y, w, h, r, outer, inner);
            return;
        }
        float x1 = x + w, y1 = y + h, ix1 = ix + iw, iy1 = iy + ih;
        mesh.solid(x + r, y, x1 - r, y, ix1 - ir, iy, ix + ir, iy, outer, inner);
        mesh.solid(x + r, y1, ix + ir, iy1, ix1 - ir, iy1, x1 - r, y1, outer, inner);
        mesh.solid(x, y + r, ix, iy + ir, ix, iy1 - ir, x, y1 - r, outer, inner);
        mesh.solid(x1, y + r, x1, y1 - r, ix1, iy1 - ir, ix1, iy + ir, outer, inner);
        cornerRing(mesh, x + r, y + r, r, ix + ir, iy + ir, ir, PI, PI * 1.5f, outer, inner);
        cornerRing(mesh, x1 - r, y + r, r, ix1 - ir, iy + ir, ir, -PI / 2f, 0, outer, inner);
        cornerRing(mesh, x1 - r, y1 - r, r, ix1 - ir, iy1 - ir, ir, 0, PI / 2f, outer, inner);
        cornerRing(mesh, x + r, y1 - r, r, ix + ir, iy1 - ir, ir, PI / 2f, PI, outer, inner);
    }

    private static void corner(Mesh mesh, float cx, float cy, float radius, float a0, float a1, int left, int right, float x0, float w) {
        float px = cx + (float) Math.cos(a0) * radius, py = cy + (float) Math.sin(a0) * radius;
        for (int i = 1; i <= CORNER; i++) {
            float a = a0 + (a1 - a0) * i / CORNER;
            float nx = cx + (float) Math.cos(a) * radius, ny = cy + (float) Math.sin(a) * radius;
            mesh.quad(cx, cy, px, py, nx, ny, cx, cy, left, right, x0, w);
            px = nx;
            py = ny;
        }
    }

    private static void cornerRing(Mesh mesh, float cx, float cy, float radius, float icx, float icy, float ir,
                                   float a0, float a1, int outer, int inner) {
        float ox = cx + (float) Math.cos(a0) * radius, oy = cy + (float) Math.sin(a0) * radius;
        float ix = icx + (float) Math.cos(a0) * ir, iy = icy + (float) Math.sin(a0) * ir;
        for (int i = 1; i <= CORNER; i++) {
            float a = a0 + (a1 - a0) * i / CORNER;
            float nox = cx + (float) Math.cos(a) * radius, noy = cy + (float) Math.sin(a) * radius;
            float nix = icx + (float) Math.cos(a) * ir, niy = icy + (float) Math.sin(a) * ir;
            mesh.solid(ox, oy, ix, iy, nix, niy, nox, noy, outer, inner);
            ox = nox;
            oy = noy;
            ix = nix;
            iy = niy;
        }
    }

    /** Render-thread builder. {@link #submit} copies into a state the GUI draws later. */
    private static final class Mesh {
        private float[] xy = new float[128];
        private int[] color = new int[64];
        private int n;
        private float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        private float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;

        void quad(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3,
                  int left, int right, float origin, float width) {
            add(x0, y0, at(left, right, x0, origin, width));
            add(x1, y1, at(left, right, x1, origin, width));
            add(x2, y2, at(left, right, x2, origin, width));
            add(x3, y3, at(left, right, x3, origin, width));
        }

        void solid(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3, int c0, int c1) {
            add(x0, y0, c0);
            add(x1, y1, c1);
            add(x2, y2, c1);
            add(x3, y3, c0);
        }

        private static int at(int left, int right, float px, float origin, float width) {
            if (left == right) return left;
            return mix(left, right, Math.clamp((px - origin) / Math.max(0.001f, width), 0f, 1f));
        }

        private void add(float x, float y, int c) {
            if (n + 1 > color.length) {
                float[] nxy = new float[xy.length * 2];
                int[] nc = new int[color.length * 2];
                System.arraycopy(xy, 0, nxy, 0, n * 2);
                System.arraycopy(color, 0, nc, 0, n);
                xy = nxy;
                color = nc;
            }
            xy[n * 2] = x;
            xy[n * 2 + 1] = y;
            color[n++] = c;
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
        }

        void submit(GuiGraphics g) {
            if (n == 0 || (n & 3) != 0) return;
            g.flush();
            Matrix4f matrix = g.pose().last().pose();
            BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (int i = 0; i < n; i++) buffer.addVertex(matrix, xy[i * 2], xy[i * 2 + 1], 0).setColor(GuiFade.apply(color[i]));
            Layers.quads().draw(buffer.buildOrThrow());
        }
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
