package dev.guizhou.util.render;

import net.minecraft.client.gui.GuiGraphics;

public class ScissorUtil {
    /** 1.21.1 GuiGraphics scissors ignore the PoseStack; custom GUI clips must transform explicitly. */
    public static void enableTransformed(GuiGraphics context, int x1, int y1, int x2, int y2) {
        var pose = context.pose().last().pose();
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (int x : new int[]{x1, x2}) for (int y : new int[]{y1, y2}) {
            var point = pose.transformPosition(new org.joml.Vector3f(x, y, 0));
            minX = Math.min(minX, point.x); minY = Math.min(minY, point.y);
            maxX = Math.max(maxX, point.x); maxY = Math.max(maxY, point.y);
        }
        context.enableScissor((int)Math.floor(minX), (int)Math.floor(minY),
                (int)Math.ceil(maxX), (int)Math.ceil(maxY));
    }
    private static int X_1;
    private static int Y_1;
    private static int X_2;
    private static int Y_2;

    public static void enable(GuiGraphics context, int x1, int y1, int x2, int y2) {
        enableTransformed(context, x1, y1, x2, y2);
        X_1 = x1;
        Y_1 = y1;
        X_2 = x2;
        Y_2 = y2;
    }

    public static void disable(GuiGraphics context) {
        context.disableScissor();
    }

    public static void enable(GuiGraphics context) {
        enableTransformed(context, X_1, Y_1, X_2, Y_2);
    }
}
