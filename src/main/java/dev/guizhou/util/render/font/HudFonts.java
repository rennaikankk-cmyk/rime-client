package dev.guizhou.util.render.font;

import net.minecraft.client.gui.GuiGraphics;

import java.awt.Font;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Geist-backed HUD fonts sized in CSS pixels — the shadcn prototype is the
 * design truth. Three static weights; glyphs bake at a 26px ascent so the
 * 22/26px hero numbers stay sharp while smaller sizes downscale. Latin and
 * digits come from Geist, CJK falls through to an installed sans face
 * (Microsoft YaHei on Windows), then the side-download ib_zh face, then the
 * OS logical sans. No drop shadow; fully transparent text is skipped rather
 * than forced opaque by the renderer's legacy alpha floor.
 */
public final class HudFonts {

    public static final int REGULAR = 400;
    public static final int SEMI = 600;
    public static final int BOLD = 700;

    /** Hero-number bake ascent; all sizes render by scaling against this. */
    private static final float BAKE_ASCENT = 26f;
    /** Sparse-label pages: keep CJK baking cheap. */
    private static final int CHARS_PER_PAGE = 128;

    private static volatile FontRenderer regular;
    private static volatile FontRenderer semibold;
    private static volatile FontRenderer bold;

    private HudFonts() {}

    public static FontRenderer of(int weight) {
        return FontFallback.recover("menu/HUD font initialization", () -> customFont(weight), () -> null);
    }
    private static FontRenderer customFont(int weight) {
            if (regular == null) init();
            return switch (weight) {
            case 700 -> bold;
            case 600 -> semibold;
            default -> regular;
            };
    }

    /** Draws text at an explicit CSS-px size, no drop shadow. x/y in GUI px. */
    public static void draw(GuiGraphics g, String text, float x, float y, float sizePx, int weight, int color) {
        // FontRenderer forces alpha < 4 to opaque; a nearly invisible draw is skipped instead
        if (text == null || text.isEmpty() || !Float.isFinite(sizePx) || sizePx <= 0 || ((color >>> 24) & 0xFF) < 4) return;
        FontFallback.recover("menu/HUD text drawing", () -> {
            FontRenderer r = customFont(weight);
            float s = sizePx / r.emGuiHeight();
            if (!Float.isFinite(s) || s <= 0f) throw new IllegalStateException("Invalid custom font scale");
            var pose = g.pose();
            pose.pushMatrix();
            try {
                pose.scale(s, s);
                r.drawString(g, text, x / s, y / s, color, false);
            } finally { pose.popMatrix(); }
            return null;
        }, () -> { FontFallback.drawScaled(g,text,x,y,sizePx,color); return null; });
    }

    /** Width in GUI px for text at an explicit CSS-px size. */
    public static float width(String text, float sizePx, int weight) {
        if (text == null || text.isEmpty() || !Float.isFinite(sizePx) || sizePx <= 0) return 0;
        return FontFallback.recover("menu/HUD text measurement", () -> {
            FontRenderer r = customFont(weight);
            float measured = r.getTextWidth(text) * (sizePx / r.emGuiHeight());
            if (!Float.isFinite(measured) || measured < 0) throw new IllegalStateException("Invalid custom font width");
            return measured;
        }, () -> FontFallback.scaledWidth(text,sizePx));
    }

    private static synchronized void init() {
        if (regular != null) return;
        FontRenderer nextRegular = null, nextSemibold = null, nextBold = null;
        boolean published = false;
        try {
            Font yahei = installedSans();
            Font zh = zhFace();
            nextRegular = make("/assets/guizhouware/font/geist-400.ttf", yahei, zh);
            Font strongCjk = yahei == null ? null : yahei.deriveFont(Font.BOLD);
            nextSemibold = make("/assets/guizhouware/font/geist-600.ttf", strongCjk, zh);
            nextBold = make("/assets/guizhouware/font/geist-700.ttf", strongCjk, zh);
            semibold = nextSemibold;
            bold = nextBold;
            regular = nextRegular; // Publish only after all weights succeed.
            published = true;
        } finally {
            if (!published) {
                closeIncomplete(nextRegular);
                closeIncomplete(nextSemibold);
                closeIncomplete(nextBold);
            }
        }
    }

    private static void closeIncomplete(FontRenderer renderer) {
        if (renderer == null) return;
        try { renderer.close(); }
        catch (Throwable failure) { FontFallback.fail("partial font cleanup", failure); }
    }

    private static FontRenderer make(String geistPath, Font yahei, Font zh) {
        List<Font> chain = new ArrayList<>(4);
        Font geist = loadResource(geistPath);
        if (geist != null) chain.add(geist);
        if (yahei != null) chain.add(yahei);
        if (zh != null) chain.add(zh);
        chain.add(new Font(Font.SANS_SERIF, Font.PLAIN, 1));
        return new FontRenderer(chain.toArray(new Font[0]), CHARS_PER_PAGE, 4, BAKE_ASCENT);
    }

    /** Installed CJK sans (Microsoft YaHei on Windows), preferred over the IB face. */
    private static Font installedSans() {
        try {
            for (String family : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames()) {
                if (family.equalsIgnoreCase("Microsoft YaHei") || family.equals("微软雅黑")) {
                    return new Font(family, Font.PLAIN, 1);
                }
            }
        } catch (Throwable ignored) {
            // headless or font lookup failure — fall through
        }
        return null;
    }

    private static Font zhFace() {
        try {
            java.nio.file.Path p = java.nio.file.Paths.get("guizhouware", "fonts", "ib_zh.ttf");
            if (java.nio.file.Files.exists(p)) {
                return Font.createFont(Font.TRUETYPE_FONT, p.toFile()).deriveFont(1f);
            }
        } catch (Exception ignored) {
            // external font optional
        }
        return loadResource("/assets/guizhouware/font/ib_zh.ttf");
    }

    private static Font loadResource(String path) {
        try (InputStream is = HudFonts.class.getResourceAsStream(path)) {
            if (is != null) {
                return Font.createFont(Font.TRUETYPE_FONT, is).deriveFont(1f);
            }
        } catch (Exception ignored) {
            // optional face — fall through
        }
        return null;
    }
}
