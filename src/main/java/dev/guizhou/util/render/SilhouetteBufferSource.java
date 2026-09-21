package dev.guizhou.util.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * 1.21.1 adapter replacing HandSilhouetteCollector. Hand geometry is captured into an
 * owned immediate buffer source with a forced flat colour; {@link #flushIntoTarget()}
 * draws the captured batches into whichever render target is bound (HandShaderRender
 * binds its own offscreen target first).
 *
 * Review item 6: the native backing buffer is allocated once per session and reused
 * ({@code HandShaderRender} keeps a single instance); {@link #discard()} must never
 * draw into the visible framebuffer, so pending geometry is only ever released by
 * drawing — the discard path binds the offscreen capture target first.
 */
public final class SilhouetteBufferSource implements MultiBufferSource {
    private static final java.util.Map<RenderType, RenderType> TYPES = new java.util.IdentityHashMap<>();
    private final MultiBufferSource.BufferSource buffers;
    private final int colorARGB;
    private boolean dirty;

    public static final VertexConsumer NOOP = new VertexConsumer() {
        @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    };

    SilhouetteBufferSource(MultiBufferSource.BufferSource buffers, int rgb) {
        this.buffers = buffers;
        this.colorARGB = 0xFF000000 | rgb;
    }

    @Override
    public VertexConsumer getBuffer(RenderType renderType) {
        if (HandShaderRender.capturePaused) return NOOP;
        if (SeeThroughRender.isGlintType(renderType)) return NOOP;
        if (!(renderType instanceof RenderType.CompositeRenderType composite)) return NOOP;
        // The native outline shader preserves texture alpha but ignores texture RGB.
        // Capturing with the item's lit shader would tint cyan swords black for a red outline.
        renderType = TYPES.computeIfAbsent(composite, original -> {
            var state = ((dev.guizhou.mixin.render.RenderTypeAccessor)(Object) original).guizhouware$getState();
            var texture = ((dev.guizhou.mixin.render.CompositeStateAccessor)(Object) state).guizhouware$getTextureState();
            return RenderType.create("guizhouware_hand_mask", com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR,
                    com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, 256, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(net.minecraft.client.renderer.RenderStateShard.RENDERTYPE_OUTLINE_SHADER)
                            .setTextureState(texture)
                            .setDepthTestState(net.minecraft.client.renderer.RenderStateShard.NO_DEPTH_TEST)
                            .setCullState(net.minecraft.client.renderer.RenderStateShard.NO_CULL)
                            .setWriteMaskState(net.minecraft.client.renderer.RenderStateShard.COLOR_WRITE)
                            .createCompositeState(false));
        });
        HandShaderRender.prepare(renderType);
        VertexConsumer real = buffers.getBuffer(renderType);
        dirty = true;
        float r = ((this.colorARGB >> 16) & 0xFF) / 255.0f;
        float g = ((this.colorARGB >> 8) & 0xFF) / 255.0f;
        float b = (this.colorARGB & 0xFF) / 255.0f;
        float a = ((this.colorARGB >>> 24) & 0xFF) / 255.0f;
        return new FlatColorVertexConsumer(real, r, g, b, a);
    }

    public boolean hasPendingGeometry() {
        return dirty;
    }

    static void invalidateTypes() { TYPES.clear(); }

    /** Draw every captured batch; the caller has already bound the capture target. */
    public void flushIntoTarget() {
        if (!dirty) return;
        buffers.endBatch();
        dirty = false;
    }

    /**
     * Releases pending batches without painting the visible framebuffer: the caller
     * must have bound the offscreen capture target. Drawing is the only way to return
     * the shared builder state to a clean slate.
     */
    public void forceRelease() {
        buffers.endBatch();
        dirty = false;
    }

    /** VertexConsumer wrapper that overrides vertex colour with a flat value. */
    private record FlatColorVertexConsumer(VertexConsumer delegate, float r, float g, float b, float a)
            implements VertexConsumer {

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(r, g, b, a);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }
    }
}
