package dev.guizhou.util.render;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * 1.21.1 adapter: feeds one vertex stream into two consumers. Replaces the 1.21.9
 * TeeSubmitCollector approach where both targets were driven by the submit API.
 */
public final class DualVertexConsumer implements VertexConsumer {
    private final VertexConsumer first;
    private final VertexConsumer second;

    public DualVertexConsumer(VertexConsumer first, VertexConsumer second) {
        this.first = first;
        this.second = second;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        first.addVertex(x, y, z);
        second.addVertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        first.setColor(r, g, b, a);
        second.setColor(r, g, b, a);
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        first.setUv(u, v);
        second.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        first.setUv1(u, v);
        second.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        first.setUv2(u, v);
        second.setUv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        first.setNormal(x, y, z);
        second.setNormal(x, y, z);
        return this;
    }
}
