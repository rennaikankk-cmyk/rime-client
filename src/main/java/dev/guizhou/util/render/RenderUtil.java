package dev.guizhou.util.render;

import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import dev.guizhou.util.render.state.RectRenderState;
import dev.guizhou.util.traits.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;

import java.awt.*;

public class RenderUtil implements Util {

    public static void rect(GuiGraphics context, float x1, float y1, float x2, float y2, int color) {
        int ix1 = Math.round(x1);
        int iy1 = Math.round(y1);
        int ix2 = Math.round(x2);
        int iy2 = Math.round(y2);
        context.fill(ix1, iy1, ix2, iy2, GuiFade.apply(color));
    }

    public static void rect(GuiGraphics context, float x1, float y1, float x2, float y2, int color, float width) {
        int w = Math.max(1, Math.round(width));
        int c = GuiFade.apply(color);
        context.fill(Math.round(x1), Math.round(y1), Math.round(x2), Math.round(y1) + w, c);
        context.fill(Math.round(x2) - w, Math.round(y1), Math.round(x2), Math.round(y2), c);
        context.fill(Math.round(x1), Math.round(y2) - w, Math.round(x2), Math.round(y2), c);
        context.fill(Math.round(x1), Math.round(y1), Math.round(x1) + w, Math.round(y2), c);
    }

    public static void horizontalGradient(GuiGraphics context, float x1, float y1, float x2, float y2, Color left, Color right) {
        int ix1 = Math.round(x1);
        int iy1 = Math.round(y1);
        int ix2 = Math.round(x2);
        int iy2 = Math.round(y2);

        gradient(context, ix1, iy1, ix2, iy2, left.hashCode(), left.hashCode(), right.hashCode(), right.hashCode());
    }

    public static void verticalGradient(GuiGraphics context, float x1, float y1, float x2, float y2, Color top, Color bottom) {
        int ix1 = Math.round(x1);
        int iy1 = Math.round(y1);
        int ix2 = Math.round(x2);
        int iy2 = Math.round(y2);

        gradient(context, ix1, iy1, ix2, iy2, top.hashCode(), bottom.hashCode(), bottom.hashCode(), top.hashCode());
    }

    public static void shadow(GuiGraphics context, float x1, float y1, float x2, float y2, int radius, int alpha) {
        if (radius <= 0 || alpha <= 0) return;
        int ix1 = Math.round(x1);
        int iy1 = Math.round(y1);
        int ix2 = Math.round(x2);
        int iy2 = Math.round(y2);
        int solid = (Math.min(255, alpha) << 24);
        int clear = 0;

        gradient(context, ix1, iy1 - radius, ix2, iy1, clear, solid, solid, clear);

        gradient(context, ix1, iy2, ix2, iy2 + radius, solid, clear, clear, solid);

        gradient(context, ix1 - radius, iy1, ix1, iy2, clear, clear, solid, solid);

        gradient(context, ix2, iy1, ix2 + radius, iy2, solid, solid, clear, clear);

        int cornerAlpha = (int) (alpha * 0.7f) << 24;
        gradient(context, ix1 - radius, iy1 - radius, ix1, iy1, clear, clear, cornerAlpha, clear);
        gradient(context, ix2, iy1 - radius, ix2 + radius, iy1, clear, clear, clear, cornerAlpha);
        gradient(context, ix1 - radius, iy2, ix1, iy2 + radius, clear, cornerAlpha, clear, clear);
        gradient(context, ix2, iy2, ix2 + radius, iy2 + radius, cornerAlpha, clear, clear, clear);
    }

    public static void gradient(GuiGraphics graphics,
                                int x1, int y1, int x2, int y2,
                                int topLeft, int bottomLeft, int bottomRight, int topRight) {
        graphics.guiRenderState.submitGuiElement(new RectRenderState(
                RenderPipelines.GUI, TextureSetup.noTexture(), new Matrix3x2f(graphics.pose()),
                x1, y1, x2, y2,
                GuiFade.apply(topLeft), GuiFade.apply(bottomLeft), GuiFade.apply(bottomRight), GuiFade.apply(topRight),
                graphics.scissorStack.peek()
        ));
    }

    public static void rect(PoseStack stack, float x1, float y1, float x2, float y2, int color) {
        rectFilled(stack, x1, y1, x2, y2, color);
    }

    public static void rect(PoseStack stack, float x1, float y1, float x2, float y2, int color, float width) {
        drawHorizontalLine(stack, x1, x2, y1, color, width);
        drawVerticalLine(stack, x2, y1, y2, color, width);
        drawHorizontalLine(stack, x1, x2, y2, color, width);
        drawVerticalLine(stack, x1, y1, y2, color, width);
    }

    protected static void drawHorizontalLine(PoseStack matrices, float x1, float x2, float y, int color) {
        if (x2 < x1) {
            float i = x1;
            x1 = x2;
            x2 = i;
        }

        rectFilled(matrices, x1, y, x2 + 1, y + 1, color);
    }

    protected static void drawVerticalLine(PoseStack matrices, float x, float y1, float y2, int color) {
        if (y2 < y1) {
            float i = y1;
            y1 = y2;
            y2 = i;
        }

        rectFilled(matrices, x, y1 + 1, x + 1, y2, color);
    }

    protected static void drawHorizontalLine(PoseStack matrices, float x1, float x2, float y, int color, float width) {
        if (x2 < x1) {
            float i = x1;
            x1 = x2;
            x2 = i;
        }

        rectFilled(matrices, x1, y, x2 + width, y + width, color);
    }

    protected static void drawVerticalLine(PoseStack matrices, float x, float y1, float y2, int color, float width) {
        if (y2 < y1) {
            float i = y1;
            y1 = y2;
            y2 = i;
        }

        rectFilled(matrices, x, y1 + width, x + width, y2, color);
    }

    public static void rectFilled(PoseStack matrix, float x1, float y1, float x2, float y2, int color) {
        float i;
        if (x1 < x2) {
            i = x1;
            x1 = x2;
            x2 = i;
        }

        if (y1 < y2) {
            i = y1;
            y1 = y2;
            y2 = i;
        }

        float f = (float) (color >> 24 & 255) / 255.0F;
        float g = (float) (color >> 16 & 255) / 255.0F;
        float h = (float) (color >> 8 & 255) / 255.0F;
        float j = (float) (color & 255) / 255.0F;

        BufferBuilder bufferBuilder = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bufferBuilder.addVertex(matrix.last().pose(), x1, y2, 0.0F).setColor(g, h, j, f);
        bufferBuilder.addVertex(matrix.last().pose(), x2, y2, 0.0F).setColor(g, h, j, f);
        bufferBuilder.addVertex(matrix.last().pose(), x2, y1, 0.0F).setColor(g, h, j, f);
        bufferBuilder.addVertex(matrix.last().pose(), x1, y1, 0.0F).setColor(g, h, j, f);

        Layers.quads().draw(bufferBuilder.buildOrThrow());
    }

    public static void horizontalGradient(PoseStack matrix, float x1, float y1, float x2, float y2, Color left, Color right) {
        BufferBuilder bufferBuilder = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bufferBuilder.addVertex(matrix.last().pose(), x1, y1, 0.0F).setColor(left.getRed() / 255.0F, left.getGreen() / 255.0F, left.getBlue() / 255.0F, left.getAlpha() / 255.0F);
        bufferBuilder.addVertex(matrix.last().pose(), x1, y2, 0.0F).setColor(left.getRed() / 255.0F, left.getGreen() / 255.0F, left.getBlue() / 255.0F, left.getAlpha() / 255.0F);
        bufferBuilder.addVertex(matrix.last().pose(), x2, y2, 0.0F).setColor(right.getRed() / 255.0F, right.getGreen() / 255.0F, right.getBlue() / 255.0F, right.getAlpha() / 255.0F);
        bufferBuilder.addVertex(matrix.last().pose(), x2, y1, 0.0F).setColor(right.getRed() / 255.0F, right.getGreen() / 255.0F, right.getBlue() / 255.0F, right.getAlpha() / 255.0F);

        Layers.quads().draw(bufferBuilder.buildOrThrow());
    }

    public static void verticalGradient(PoseStack matrix, float x1, float y1, float x2, float y2, Color top, Color bottom) {
        BufferBuilder bufferBuilder = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bufferBuilder.addVertex(matrix.last().pose(), x1, y1, 0.0F).setColor(top.getRed() / 255.0F, top.getGreen() / 255.0F, top.getBlue() / 255.0F, top.getAlpha() / 255.0F);
        bufferBuilder.addVertex(matrix.last().pose(), x1, y2, 0.0F).setColor(bottom.getRed() / 255.0F, bottom.getGreen() / 255.0F, bottom.getBlue() / 255.0F, bottom.getAlpha() / 255.0F);
        bufferBuilder.addVertex(matrix.last().pose(), x2, y2, 0.0F).setColor(bottom.getRed() / 255.0F, bottom.getGreen() / 255.0F, bottom.getBlue() / 255.0F, bottom.getAlpha() / 255.0F);
        bufferBuilder.addVertex(matrix.last().pose(), x2, y1, 0.0F).setColor(top.getRed() / 255.0F, top.getGreen() / 255.0F, top.getBlue() / 255.0F, top.getAlpha() / 255.0F);

        Layers.quads().draw(bufferBuilder.buildOrThrow());
    }

    public static void drawBoxFilled(PoseStack stack, AABB box, Color c) {
        Gizmos.cuboid(box, GizmoStyle.fill(c.getRGB())).setAlwaysOnTop();
    }

    public static void drawBoxFilled(PoseStack stack, Vec3 vec, Color c) {
        drawBoxFilled(stack, AABB.unitCubeFromLowerCorner(vec), c);
    }

    public static void drawBoxFilled(PoseStack stack, BlockPos bp, Color c) {
        drawBoxFilled(stack, new AABB(bp), c);
    }

    public static void drawBox(PoseStack stack, AABB box, Color c, float lineWidth) {
        Gizmos.cuboid(box, GizmoStyle.stroke(c.getRGB(), lineWidth)).setAlwaysOnTop();
    }

    public static void drawBox(PoseStack stack, Vec3 vec, Color c, float lineWidth) {
        drawBox(stack, AABB.unitCubeFromLowerCorner(vec), c, lineWidth);
    }

    public static void drawBox(PoseStack stack, BlockPos bp, Color c, float lineWidth) {
        drawBox(stack, new AABB(bp), c, lineWidth);
    }

    public static void drawLine(Vec3 from, Vec3 to, Color c, float lineWidth) {
        Gizmos.line(from, to, c.getRGB(), lineWidth).setAlwaysOnTop();
    }

    // ---- World-space translucent quads (camera-relative, drawn via the global quad pipeline) ----

    private static void worldVertex(BufferBuilder bb, Matrix4f m, Vec3 cam, double x, double y, double z, Color c) {
        bb.addVertex(m, (float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z))
                .setColor(c.getRed() / 255.0F, c.getGreen() / 255.0F, c.getBlue() / 255.0F, c.getAlpha() / 255.0F);
    }

    /** Flat horizontal quad at height {@code y} spanning [x1,z1]..[x2,z2]. */
    public static void quadHorizontal(PoseStack stack, double x1, double z1, double x2, double z2, double y, Color c) {
        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        Matrix4f m = stack.last().pose();
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        worldVertex(bb, m, cam, x1, y, z1, c);
        worldVertex(bb, m, cam, x2, y, z1, c);
        worldVertex(bb, m, cam, x2, y, z2, c);
        worldVertex(bb, m, cam, x1, y, z2, c);
        Layers.quads().draw(bb.buildOrThrow());
    }

    /**
     * Vertical quad ("skirt" wall). The horizontal edge from (x1,z1) to (x2,z2) is extruded
     * down from {@code yTop} to {@code yBottom}, with a colour gradient between the two edges.
     */
    public static void gradientQuadVertical(PoseStack stack, double x1, double z1, double x2, double z2,
                                            double yTop, double yBottom, Color top, Color bottom) {
        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        Matrix4f m = stack.last().pose();
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        worldVertex(bb, m, cam, x1, yTop, z1, top);
        worldVertex(bb, m, cam, x2, yTop, z2, top);
        worldVertex(bb, m, cam, x2, yBottom, z2, bottom);
        worldVertex(bb, m, cam, x1, yBottom, z1, bottom);
        Layers.quads().draw(bb.buildOrThrow());
    }

    public static void placementLine(Vec3 from, Vec3 to, Color color, float width, boolean through) {
        var gizmo = Gizmos.line(from, to, color.getRGB(), width);
        if (through) gizmo.setAlwaysOnTop();
    }

    public static void placementBox(AABB box, Color fill, Color line, float width, boolean through, boolean filled, boolean outlined) {
        if (filled) {
            var gizmo = Gizmos.cuboid(box, GizmoStyle.fill(fill.getRGB()));
            if (through) gizmo.setAlwaysOnTop();
        }
        if (outlined) {
            var gizmo = Gizmos.cuboid(box, GizmoStyle.stroke(line.getRGB(), width));
            if (through) gizmo.setAlwaysOnTop();
        }
    }

    /** One mesh for the top and four gradient faces; the depth policy is local to this overlay. */
    public static void placementGradient(PoseStack stack, AABB box, Color fill, Color line, float width, double height, boolean through) {
        if (fill.getAlpha() > 0 && height > 0) {
            Vec3 cam = mc.gameRenderer.getMainCamera().position();
            Matrix4f m = stack.last().pose();
            Color clear = new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), 0);
            double bottom = box.maxY - Math.min(box.getYsize(), height);
            BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            double[][] corners = {{box.minX,box.minZ},{box.maxX,box.minZ},{box.maxX,box.maxZ},{box.minX,box.maxZ}};
            for (double[] c : corners) worldVertex(bb,m,cam,c[0],box.maxY,c[1],fill);
            for (int i=0;i<4;i++) {
                double[] a=corners[i], b=corners[(i+1)%4];
                worldVertex(bb,m,cam,a[0],box.maxY,a[1],fill);
                worldVertex(bb,m,cam,b[0],box.maxY,b[1],fill);
                worldVertex(bb,m,cam,b[0],bottom,b[1],clear);
                worldVertex(bb,m,cam,a[0],bottom,a[1],clear);
            }
            Layers.placementQuads(through).draw(bb.buildOrThrow());
        }
        if (line.getAlpha()>0) placementBox(new AABB(box.minX,box.maxY-Math.min(box.getYsize(),height),box.minZ,
                box.maxX,box.maxY,box.maxZ),fill,line,width,through,false,true);
    }

    public static PoseStack matrixFrom(Vec3 pos) {
        PoseStack matrices = new PoseStack();
        Camera camera = mc.gameRenderer.getMainCamera();
        matrices.mulPose(Axis.XP.rotationDegrees(camera.xRot()));
        matrices.mulPose(Axis.YP.rotationDegrees(camera.yRot() + 180.0F));
        matrices.translate(pos.x() - camera.position().x, pos.y() - camera.position().y, pos.z() - camera.position().z);
        return matrices;
    }
}
