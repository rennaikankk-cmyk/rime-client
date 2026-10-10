package dev.guizhou.util.render.font;

import dev.guizhou.GuizhouWare;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;


public final class Fonts {

    private Fonts() {}

    private static final Minecraft mc = Minecraft.getInstance();

    private static final float SHADOW_OFFSET = 0.7f;

    private static volatile FontRenderer renderer;
    private static volatile boolean dirty = true;
    private static volatile boolean hudPass = false;

    private static boolean nametagOn() {
        try {
            dev.guizhou.features.modules.render.NametagsModule m =
                    GuizhouWare.moduleManager.getModuleByClass(dev.guizhou.features.modules.render.NametagsModule.class);
            return m != null && m.nametagFont.getValue();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void markDirty() { dirty = true; }

    public static void beginHudPass() { hudPass = true; }
    public static void endHudPass()   { hudPass = false; }

    public static void drawString(GuiGraphics graphics, String text, float x, float y, int color) {
        draw(safeRenderer(true), graphics, text, x, y, color, true);
    }

    public static int width(String text) {
        return measure(safeRenderer(true), text);
    }

    public static int lineHeight() {
        return Math.max(1, mc.font.lineHeight);
    }

    public static boolean drawOverrideActive() {
        return !FontFallback.nativePass() && FontFallback.customEnabled()
                && hudPass && safeRenderer(true) != null;
    }

    public static void renderOverrideString(GuiGraphics graphics, String text, float x, float y, int color, boolean shadow) {
        draw(safeRenderer(true), graphics, text, x, y, color, shadow);
    }

    public static void renderOverrideText(GuiGraphics graphics, FormattedCharSequence text, float x, float y, int color, boolean shadow) {
        FontRenderer fr = safeRenderer(true);
        if (fr == null) { FontFallback.draw(graphics,text,x,y,color,shadow); return; }
        FontFallback.recover("formatted text drawing", () -> {
            if (shadow) fr.drawText(graphics,text,x+SHADOW_OFFSET,y+SHADOW_OFFSET,color,true);
            fr.drawText(graphics,text,x,y,color,false);
            return null;
        }, () -> { FontFallback.draw(graphics,text,x,y,color,shadow); return null; });
    }

    public static int overrideWidth(String text) {
        return measure(safeRenderer(true), text);
    }

    public static int overrideWidth(FormattedCharSequence text) {
        FontRenderer fr = safeRenderer(true);
        return fr == null ? FontFallback.width(text) : FontFallback.recover("formatted text measurement",
                () -> roundedWidth(fr.getTextWidth(text)), () -> FontFallback.width(text));
    }

    /** Nametag text through the custom renderer (CJK-capable), vanilla font as fallback. */
    public static void drawNametagString(GuiGraphics graphics, String text, float x, float y, int color) {
        draw(safeRenderer(nametagOn()),graphics,text,x,y,color,true);
    }

    public static int nametagWidth(String text) {
        return measure(safeRenderer(nametagOn()),text);
    }

    public static int nametagLineHeight() {
        return lineHeight();
    }

    private static FontRenderer safeRenderer(boolean requested) {
        return requested ? FontFallback.recover("custom font initialization", Fonts::renderer, () -> null) : null;
    }
    private static int roundedWidth(float width) {
        if (!Float.isFinite(width) || width < 0) throw new IllegalStateException("Invalid custom font width");
        return Math.round(width);
    }
    private static int measure(FontRenderer fr, String text) {
        return fr == null ? FontFallback.width(text) : FontFallback.recover("custom text measurement",
                () -> roundedWidth(fr.getTextWidth(text)), () -> FontFallback.width(text));
    }
    private static void draw(FontRenderer fr, GuiGraphics graphics, String text, float x, float y, int color, boolean shadow) {
        if (fr == null) { FontFallback.draw(graphics,text,x,y,color,shadow); return; }
        FontFallback.recover("custom text drawing", () -> {
            if (shadow) fr.drawString(graphics,text,x+SHADOW_OFFSET,y+SHADOW_OFFSET,color,true);
            fr.drawString(graphics,text,x,y,color,false);
            return null;
        }, () -> { FontFallback.draw(graphics,text,x,y,color,shadow); return null; });
    }

    private static FontRenderer renderer() {
        if (mc.getWindow() == null) return null;
        if (dirty || renderer == null) rebuild();
        return renderer;
    }

    private static synchronized void rebuild() {
        if (!dirty && renderer != null) return;
        disposeIfPresent();

        renderer = new FontRenderer(FontFaces.resolve(mc.gameDirectory.toPath(), HudFonts.REGULAR));
        dirty = false;
    }

    private static synchronized void disposeIfPresent() {
        FontRenderer old = renderer;
        renderer = null;
        if (old != null) old.close();
    }

    /** Keep native durability and cooldown decorations, replacing only the count text. */
    public static void renderItemDecorations(GuiGraphics g, net.minecraft.world.item.ItemStack stack, int x, int y) {
        renderItemDecorations(g, stack, x, y, true);
    }

    public static void renderNametagItemDecorations(GuiGraphics g, net.minecraft.world.item.ItemStack stack, int x, int y) {
        renderItemDecorations(g, stack, x, y, nametagOn());
    }

    private static void renderItemDecorations(GuiGraphics g, net.minecraft.world.item.ItemStack stack, int x, int y, boolean custom) {
        if (stack.isEmpty()) return;
        if (!custom || !FontFallback.customEnabled()) { g.renderItemDecorations(mc.font, stack, x, y); return; }
        g.renderItemDecorations(mc.font, stack, x, y, "");
        if (stack.getCount() > 1) {
            String text = String.valueOf(stack.getCount());
            drawString(g, text, x + 17 - width(text), y + 9, 0xFFFFFFFF);
        }
    }
}
