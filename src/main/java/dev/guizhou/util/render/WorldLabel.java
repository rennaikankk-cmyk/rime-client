package dev.guizhou.util.render;

import dev.guizhou.util.render.font.Fonts;
import net.minecraft.client.gui.GuiGraphics;

/** Screen-space text anchored to a world point, drawn in Render2D like the nametags. */
public final class WorldLabel {
    private WorldLabel() {}

    /** Scale shrinks with distance down to {@code minScale}; returns false when behind the camera. */
    public static boolean draw(GuiGraphics graphics, double x, double y, double z, double distance,
                               String text, int textArgb, int backgroundArgb, float scale, float minScale) {
        float[] screen = MatrixCapture.worldToScreen(x, y, z);
        if (screen == null) return false;
        float s = (float) Math.max(minScale, scale * 8.0 / (Math.max(0.0, distance) + 8.0));
        int width = Fonts.width(text);
        int height = Fonts.lineHeight();
        int half = width / 2;
        graphics.pose().pushPose();
        graphics.pose().translate(screen[0], screen[1], 0);
        graphics.pose().scale(s, s, s);
        graphics.fill(-half - 2, -height - 1, half + 2, 1, backgroundArgb);
        Fonts.drawString(graphics, text, -half, -height, textArgb);
        graphics.pose().popPose();
        return true;
    }
}
