package dev.guizhou.util.render;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
/** Tinted GUI textures using the immediate 1.21.1 GUI renderer. */
public final class GuiTextureCompat {
    private GuiTextureCompat() {}
    private static void tint(GuiGraphics g, int color) {
        color = GuiFade.apply(color);
        g.setColor((color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f, (color >>> 24) / 255f);
    }
    public static void blit(GuiGraphics g, ResourceLocation texture, int x, int y, float u, float v, int w, int h, int tw, int th, int color) {
        tint(g, color);
        try { g.blit(texture, x, y, u, v, w, h, tw, th); }
        finally { g.setColor(1, 1, 1, 1); }
    }
    public static void blitSprite(GuiGraphics g, ResourceLocation texture, int x, int y, int w, int h, int color) {
        tint(g, color);
        try { g.blitSprite(texture, x, y, w, h); }
        finally { g.setColor(1, 1, 1, 1); }
    }
}
