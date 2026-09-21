package dev.guizhou.util.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.21.1 adapter for the 1.21.5+ tinted GUI blit. The newer API passes a
 * {@code RenderPipelines.GUI_TEXTURED} pipeline and an ARGB tint to
 * {@code GuiGraphics.blit}; 1.21.1 blits are untinted, so the tint is applied by
 * multiplying the shader colour around the blit (blend state is untouched).
 *
 * Review item 7: {@code RenderSystem.getShaderColor()} exposes the live global array
 * and {@code setShaderColor} mutates that same array in place — copying the four
 * scalars out before and restoring them after avoids progressive darkening.
 */
public final class GuiBlitCompat {
    private GuiBlitCompat() {}

    public static void blitTinted(GuiGraphics graphics, ResourceLocation texture,
                                  int x, int y, float u, float v,
                                  int width, int height, int textureWidth, int textureHeight,
                                  int argb) {
        if ((argb >>> 24) == 0) return;
        float r = ((argb >> 16) & 0xFF) / 255.0f;
        float g = ((argb >> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        float a = ((argb >>> 24) & 0xFF) / 255.0f;
        float[] live = RenderSystem.getShaderColor();
        float pr = live[0], pg = live[1], pb = live[2], pa = live[3];
        RenderSystem.setShaderColor(pr * r, pg * g, pb * b, pa * a);
        try {
            graphics.blit(texture, x, y, u, v, width, height, textureWidth, textureHeight);
        } finally {
            RenderSystem.setShaderColor(pr, pg, pb, pa);
        }
    }
}
