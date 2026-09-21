package dev.guizhou.util.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.guizhou.util.traits.Util;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.21.1 wireframe capture. The 1.21.11 version extracted an EntityRenderState and
 * re-submitted it into a capturing collector; on 1.21.1 the entity dispatcher renders
 * straight into a MultiBufferSource, so capture re-renders the entity into a
 * capturing source that records quads camera-relative via the provided pose stack.
 */
public final class WireframeEntityRenderer implements Util {

    private static final int FULL_BRIGHT = 0xF000F0;

    private WireframeEntityRenderer() {}

    public static List<float[][]> render(
            PoseStack eventStack,
            Entity entity,
            Vec3 pos,
            List<float[][]> quads,
            float partialTick,
            Color sideColor,
            Color lineColor,
            float lineWidth) {

        if (quads == null) {
            quads = captureGeometry(entity, partialTick);
        }
        if (quads.isEmpty()) return quads;

        Vec3 cam = mc.getEntityRenderDispatcher().camera.getPosition();
        float dx = (float) (pos.x - cam.x);
        float dy = (float) (pos.y - cam.y);
        float dz = (float) (pos.z - cam.z);

        eventStack.pushPose();
        eventStack.translate(dx, dy, dz);
        PoseStack.Pose pose = eventStack.last();

        if (sideColor.getAlpha() > 0) {
            BufferBuilder fill = Tesselator.getInstance()
                    .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (float[][] q : quads) {
                for (float[] v : q) {
                    fill.addVertex(pose, v[0], v[1], v[2]).setColor(sideColor.getRed() / 255f, sideColor.getGreen() / 255f, sideColor.getBlue() / 255f, sideColor.getAlpha() / 255f);
                }
            }
            Layers.quads().draw(fill.buildOrThrow());
        }

        BufferBuilder edges = Tesselator.getInstance()
                .begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        float lr = lineColor.getRed() / 255f, lg = lineColor.getGreen() / 255f, lb = lineColor.getBlue() / 255f, la = lineColor.getAlpha() / 255f;
        for (float[][] q : quads) {
            for (int i = 0; i < 4; i++) {
                float[] v1 = q[i];
                float[] v2 = q[(i + 1) % 4];
                float nx = v2[0] - v1[0], ny = v2[1] - v1[1], nz = v2[2] - v1[2];
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len > 1.0E-4f) { nx /= len; ny /= len; nz /= len; } else { nx = 0f; ny = 1f; nz = 0f; }
                edges.addVertex(pose, v1[0], v1[1], v1[2]).setColor(lr, lg, lb, la).setNormal(nx, ny, nz);
                edges.addVertex(pose, v2[0], v2[1], v2[2]).setColor(lr, lg, lb, la).setNormal(nx, ny, nz);
            }
        }
        Layers.lines(lineWidth, true).draw(edges.buildOrThrow());

        eventStack.popPose();
        return quads;
    }

    private static List<float[][]> captureGeometry(Entity entity, float partialTick) {
        try {
            CapturingBufferSource capture = new CapturingBufferSource();
            PoseStack stack = new PoseStack();
            EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
            dispatcher.render(entity, 0.0, 0.0, 0.0,
                    entity.getYRot(), partialTick, stack, capture, FULL_BRIGHT);
            return capture.quads;
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    /** Buffer source that records every written quad; no actual draw happens. */
    private static final class CapturingBufferSource implements MultiBufferSource {
        final List<float[][]> quads = new ArrayList<>();
        private final float[][] pending = new float[4][];
        private int count = 0;

        @Override
        public VertexConsumer getBuffer(RenderType renderType) {
            return new CapturingConsumer();
        }

        private final class CapturingConsumer implements VertexConsumer {
            @Override
            public VertexConsumer addVertex(float x, float y, float z) {
                pending[count++] = new float[]{x, y, z};
                if (count == 4) {
                    quads.add(new float[][]{
                            pending[0].clone(), pending[1].clone(),
                            pending[2].clone(), pending[3].clone()
                    });
                    count = 0;
                }
                return this;
            }

            @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
            @Override public VertexConsumer setUv(float u, float v) { return this; }
            @Override public VertexConsumer setUv1(int u, int v) { return this; }
            @Override public VertexConsumer setUv2(int u, int v) { return this; }
            @Override public VertexConsumer setNormal(float nx, float ny, float nz) { return this; }
        }
    }
}
