package dev.guizhou.util.render.font;

import net.minecraft.client.gui.GuiGraphics;

import java.awt.Font;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Serif HUD fonts sized in CSS pixels. The night preview is the design truth.
 * Three static weights share one face chain: Anthropic Serif when installed,
 * then Source Serif 4, Noto Serif SC, Palatino, Songti, then the logical serif.
 * Anthropic Serif is never embedded. No drop shadow; fully transparent text is
 * skipped rather than forced opaque by the renderer's legacy alpha floor.
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
            nextRegular = make(Font.PLAIN);
            nextSemibold = make(Font.BOLD);
            nextBold = make(Font.BOLD);
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

    private static final String[] SERIF_FAMILIES = {
            "Anthropic Serif", "Source Serif 4", "Noto Serif SC", "Palatino Linotype", "Songti SC", "宋体"
    };

    private static FontRenderer make(int style) {
        List<Font> chain = new ArrayList<>(SERIF_FAMILIES.length + 1);
        for (String family : SERIF_FAMILIES) {
            Font face = installedFamily(family, style);
            if (face != null) chain.add(face);
        }
        chain.add(new Font(Font.SERIF, style, 1));
        return new FontRenderer(chain.toArray(new Font[0]), CHARS_PER_PAGE, 4, BAKE_ASCENT);
    }

    /** Installed family only. A missing name must not fall through to the logical dialog face. */
    private static Font installedFamily(String family, int style) {
        try {
            for (String name : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames()) {
                if (name.equalsIgnoreCase(family)) return new Font(name, style, 1);
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
