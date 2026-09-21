package dev.guizhou.util.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * 1.21.1 adapter replacing TeeSubmitCollector: a MultiBufferSource whose buffers
 * are duplicated into a primary and a secondary sink (hand silhouette capture).
 */
public final class TeeBufferSource implements MultiBufferSource {
    private final MultiBufferSource primary;
    private final MultiBufferSource secondary;

    public TeeBufferSource(MultiBufferSource primary, MultiBufferSource secondary) {
        this.primary = primary;
        this.secondary = secondary;
    }

    @Override
    public VertexConsumer getBuffer(RenderType renderType) {
        return new DualVertexConsumer(primary.getBuffer(renderType), secondary.getBuffer(renderType));
    }
}
