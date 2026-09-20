package dev.guizhou.util.render.font;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.client.ClickGuiModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;

import java.awt.Font;
import java.io.InputStream;

public final class Fonts {

    private Fonts() {}

    private static final Minecraft mc = Minecraft.getInstance();
    // InternalBeyond-style bundled chain: serif latin first, CJK second, OS sans last.
    private static final String BUNDLED_LATIN_PATH = "/assets/guizhouware/font/ib_latin.ttf";
    private static final String BUNDLED_ZH_PATH = "/assets/guizhouware/font/ib_zh.ttf";

    private static final float SHADOW_OFFSET = 0.7f;

    private static volatile FontRenderer renderer;
    private static volatile boolean dirty = true;
    private static volatile boolean hudPass = false;

    private static java.awt.Font bundledLatin;
    private static java.awt.Font bundledZh;
    private static boolean bundledLoaded;

    private static ClickGuiModule cfg() { return ClickGuiModule.getInstance(); }

    private static boolean clickGuiOn() { ClickGuiModule c = cfg(); return c != null && c.clickGuiFont.getValue(); }
    private static boolean hudOn()      { ClickGuiModule c = cfg(); return c != null && c.hudFont.getValue(); }
    private static boolean nametagOn() {
        try {
            dev.guizhou.features.modules.render.NametagsModule m =
                    GuizhouWare.moduleManager.getModuleByClass(dev.guizhou.features.modules.render.NametagsModule.class);
            return m != null && m.nametagFont.getValue();
        } catch (Throwable t) {
            return false;
        }
    }
    private static boolean anyOn()      { return clickGuiOn() || hudOn() || nametagOn(); }

    public static void markDirty() { dirty = true; }

    public static void beginHudPass() { hudPass = true; }
    public static void endHudPass()   { hudPass = false; }

    public static void drawString(GuiGraphics graphics, String text, float x, float y, int color) {
        FontRenderer fr = clickGuiOn() ? renderer() : null;
        if (fr == null) {
            graphics.drawString(mc.font, text, (int) x, (int) y, color);
            return;
        }
        fr.drawString(graphics, text, x + SHADOW_OFFSET, y + SHADOW_OFFSET, color, true);
        fr.drawString(graphics, text, x, y, color, false);
    }

    /**
     * HUD card text without the vanilla drop shadow. Uses the bundled renderer
     * when the hud-pass override (or click-gui font) is active, vanilla otherwise.
     */
    public static void drawStringNoShadow(GuiGraphics graphics, String text, float x, float y, int color) {
        FontRenderer fr = clickGuiOn() || drawOverrideActive() ? renderer() : null;
        if (fr == null) {
            graphics.drawString(mc.font, text, (int) x, (int) y, color, false);
            return;
        }
        fr.drawString(graphics, text, x, y, color, false);
    }

    /** Width matching the path drawStringNoShadow will take. */
    public static int widthBest(String text) {
        FontRenderer fr = clickGuiOn() || drawOverrideActive() ? renderer() : null;
        return fr == null ? mc.font.width(text) : Math.round(fr.getTextWidth(text));
    }

    public static int width(String text) {
        FontRenderer fr = clickGuiOn() ? renderer() : null;
        return fr == null ? mc.font.width(text) : Math.round(fr.getTextWidth(text));
    }

    public static int lineHeight() {
        FontRenderer fr = clickGuiOn() ? renderer() : null;
        return fr == null ? mc.font.lineHeight : Math.max(1, Math.round(fr.getHeight()));
    }

    public static boolean drawOverrideActive() {
        return hudPass && hudOn() && renderer() != null;
    }

    public static void renderOverrideString(GuiGraphics graphics, String text, float x, float y, int color, boolean shadow) {
        FontRenderer fr = renderer();
        if (fr == null) return;
        if (shadow) fr.drawString(graphics, text, x + SHADOW_OFFSET, y + SHADOW_OFFSET, color, true);
        fr.drawString(graphics, text, x, y, color, false);
    }

    public static void renderOverrideText(GuiGraphics graphics, FormattedCharSequence text, float x, float y, int color, boolean shadow) {
        FontRenderer fr = renderer();
        if (fr == null) return;
        if (shadow) fr.drawText(graphics, text, x + SHADOW_OFFSET, y + SHADOW_OFFSET, color, true);
        fr.drawText(graphics, text, x, y, color, false);
    }

    public static int overrideWidth(String text) {
        FontRenderer fr = renderer();
        return fr == null ? mc.font.width(text) : Math.round(fr.getTextWidth(text));
    }

    public static int overrideWidth(FormattedCharSequence text) {
        FontRenderer fr = renderer();
        return fr == null ? mc.font.width(text) : Math.round(fr.getTextWidth(text));
    }

    /** Nametag text through the custom renderer (CJK-capable), vanilla font as fallback. */
    public static void drawNametagString(GuiGraphics graphics, String text, float x, float y, int color) {
        FontRenderer fr = (nametagOn() || clickGuiOn()) ? renderer() : null;
        if (fr == null) {
            graphics.drawString(mc.font, text, (int) x, (int) y, color);
            return;
        }
        fr.drawString(graphics, text, x + SHADOW_OFFSET, y + SHADOW_OFFSET, color, true);
        fr.drawString(graphics, text, x, y, color, false);
    }

    public static int nametagWidth(String text) {
        FontRenderer fr = (nametagOn() || clickGuiOn()) ? renderer() : null;
        return fr == null ? mc.font.width(text) : Math.round(fr.getTextWidth(text));
    }

    public static int nametagLineHeight() {
        FontRenderer fr = (nametagOn() || clickGuiOn()) ? renderer() : null;
        return fr == null ? mc.font.lineHeight : Math.max(1, Math.round(fr.getHeight()));
    }

    private static FontRenderer renderer() {
        if (!anyOn()) {
            disposeIfPresent();
            return null;
        }
        if (mc.getWindow() == null) return null;
        if (dirty || renderer == null) rebuild();
        return renderer;
    }

    private static synchronized void rebuild() {
        if (!dirty && renderer != null) return;
        disposeIfPresent();

        ClickGuiModule c = cfg();
        renderer = new FontRenderer(resolveBaseFonts(c == null ? "" : c.fontName.getValue()));
        dirty = false;
    }

    /**
     * Font chain: user's system font first (if set), then bundled IB serif latin,
     * bundled CJK, OS sans — AWT picks the first font that has the glyph, so a
     * latin-only custom font still renders Chinese through the bundled CJK face.
     */
    private static Font[] resolveBaseFonts(String familyName) {
        java.util.List<Font> chain = new java.util.ArrayList<>(4);
        if (familyName != null && !familyName.isBlank()) {
            chain.add(new Font(familyName.trim(), Font.PLAIN, 1));
        }
        loadBundled();
        if (bundledLatin != null) chain.add(bundledLatin);
        if (bundledZh != null) chain.add(bundledZh);
        chain.add(new Font(Font.SANS_SERIF, Font.PLAIN, 1));
        return chain.toArray(new Font[0]);
    }

    private static synchronized void disposeIfPresent() {
        FontRenderer old = renderer;
        renderer = null;
        if (old != null) old.close();
    }

    private static void loadBundled() {
        if (bundledLoaded) return;
        bundledLoaded = true;
        bundledLatin = loadTtf(BUNDLED_LATIN_PATH);
        // CJK face is an optional side download (launcher puts it at
        // guizhouware/fonts/ib_zh.ttf next to the mods dir) — keeps the jar lean.
        bundledZh = loadExternalTtf("guizhouware", "fonts", "ib_zh.ttf");
        if (bundledZh == null) {
            bundledZh = loadTtf(BUNDLED_ZH_PATH);
        }
    }

    private static Font loadExternalTtf(String... segments) {
        try {
            java.nio.file.Path p = java.nio.file.Paths.get("", segments);
            if (java.nio.file.Files.exists(p)) {
                return Font.createFont(Font.TRUETYPE_FONT, p.toFile()).deriveFont(1f);
            }
        } catch (Exception e) {
            // external font optional — fall through
        }
        return null;
    }

    private static Font loadTtf(String resourcePath) {
        try (InputStream is = Fonts.class.getResourceAsStream(resourcePath)) {
            if (is != null) {
                return Font.createFont(Font.TRUETYPE_FONT, is).deriveFont(1f);
            }
        } catch (Exception e) {
            // bundled font optional — fall through to the OS sans tail of the chain
        }
        return null;
    }
}
