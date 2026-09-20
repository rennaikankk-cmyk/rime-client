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
 * digits come from Geist, CJK falls through to the side-download ib_zh face
 * (same lookup the ClickGui chain uses) and then the OS sans tail.
 */
public final class HudFonts {

    public static final int REGULAR = 400;
    public static final int SEMI = 600;
    public static final int BOLD = 700;

    /** Hero-number bake ascent; all sizes render by scaling against this. */
    private static final float BAKE_ASCENT = 26f;

    private static volatile FontRenderer regular;
    private static volatile FontRenderer semibold;
    private static volatile FontRenderer bold;

    private HudFonts() {}

    public static FontRenderer of(int weight) {
        if (regular == null) init();
        if (regular == null) return null;
        return switch (weight) {
            case 700 -> bold;
            case 600 -> semibold;
            default -> regular;
        };
    }

    /** Draws text at an explicit CSS-px size, no drop shadow. x/y in GUI px. */
    public static void draw(GuiGraphics g, String text, float x, float y, float sizePx, int weight, int color) {
        FontRenderer r = of(weight);
        if (r == null) return;
        float em = r.emGuiHeight();
        float s = sizePx / em;
        if (!Float.isFinite(s) || s <= 0f) return;
        var pose = g.pose();
        pose.pushMatrix();
        pose.scale(s, s);
        r.drawString(g, text, x / s, y / s, color, false);
        pose.popMatrix();
    }

    /** Width in GUI px for text at an explicit CSS-px size. */
    public static float width(String text, float sizePx, int weight) {
        FontRenderer r = of(weight);
        if (r == null) return 0f;
        return r.getTextWidth(text) * (sizePx / r.emGuiHeight());
    }

    private static synchronized void init() {
        if (regular != null) return;
        try {
            Font zh = zhFace();
            regular = make("/assets/guizhouware/font/geist-400.ttf", zh);
            semibold = make("/assets/guizhouware/font/geist-600.ttf", zh);
            bold = make("/assets/guizhouware/font/geist-700.ttf", zh);
        } catch (Throwable ignored) {
            // any failure leaves of() returning null; callers skip text rather than crash
        }
    }

    private static FontRenderer make(String geistPath, Font zh) {
        List<Font> chain = new ArrayList<>(3);
        Font geist = loadResource(geistPath);
        if (geist != null) chain.add(geist);
        if (zh != null) chain.add(zh);
        chain.add(new Font(Font.SANS_SERIF, Font.PLAIN, 1));
        return new FontRenderer(chain.toArray(new Font[0]), 512, 4, BAKE_ASCENT);
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
