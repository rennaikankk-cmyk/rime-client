package dev.guizhou.util.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;

/**
 * 1.21.1 hand silhouette capture. The 1.21.5+ version drove a GpuDevice encoder and
 * output-texture overrides; on 1.21.1 the capture target is cleared directly and the
 * captured silhouette batches are drawn while the target is write-bound.
 *
 * Review item 6: one native ByteBufferBuilder and one buffer source live for the whole
 * session and are reused every frame; releases only ever draw into the offscreen
 * capture target.
 */
public final class HandShaderRender {

    private static final int INITIAL_BUFFER = 786432;
    private static final ByteBufferBuilder SHARED_BUILDER = new ByteBufferBuilder(INITIAL_BUFFER);
    private static final java.util.SequencedMap<net.minecraft.client.renderer.RenderType, ByteBufferBuilder> FIXED_BUFFERS = new java.util.LinkedHashMap<>();
    private static final MultiBufferSource.BufferSource SHARED_BUFFERS = MultiBufferSource.immediateWithBuffers(FIXED_BUFFERS, SHARED_BUILDER);

    static void prepare(net.minecraft.client.renderer.RenderType type) {
        FIXED_BUFFERS.computeIfAbsent(type, ignored -> new ByteBufferBuilder(8192));
    }

    private static TextureTarget handTarget;
    private static int width, height;

    private static boolean captureOpen;
    private static boolean hasCapture;
    private static SilhouetteBufferSource capturing;

    public static boolean capturePaused;

    private HandShaderRender() {}

    private static boolean ensureTarget() {
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getWidth();
        int h = mc.getWindow().getHeight();
        if (w <= 0 || h <= 0) return false;

        if (handTarget == null) {
            handTarget = new TextureTarget(w, h, true, false);
            width = w;
            height = h;
        } else if (w != width || h != height) {
            handTarget.resize(w, h, true);
            width = w;
            height = h;
        }
        return true;
    }

    /** The offscreen capture target; null until the first successful capture setup. */
    public static com.mojang.blaze3d.pipeline.RenderTarget captureTarget() {
        return handTarget;
    }

    public static SilhouetteBufferSource beginCapture(int rgb) {
        if (!ensureTarget()) return null;

        discardCapture();
        handTarget.setClearColor(0, 0, 0, 0);
        handTarget.clear(Minecraft.ON_OSX);
        Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
        capturing = new SilhouetteBufferSource(SHARED_BUFFERS, rgb);
        captureOpen = true;
        hasCapture = false;
        return capturing;
    }

    public static void flush() {
        if (!captureOpen || capturing == null || handTarget == null) return;
        captureOpen = false;

        if (!capturing.hasPendingGeometry()) return;

        handTarget.bindWrite(false);
        try {
            RenderSystem.disableBlend();
            capturing.flushIntoTarget();
        } finally {
            Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
        }
        hasCapture = true;
    }

    /** Called when the shader module turned off mid-capture; releases geometry offscreen. */
    public static void discardCapture() {
        if (!captureOpen || capturing == null) return;
        captureOpen = false;
        if (!capturing.hasPendingGeometry() || handTarget == null) return;
        handTarget.bindWrite(false);
        try {
            RenderSystem.disableBlend();
            capturing.forceRelease();
        } finally {
            Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
        }
    }

    public static void invalidate() {
        discardCapture();
        SHARED_BUFFERS.endBatch();
        FIXED_BUFFERS.values().forEach(ByteBufferBuilder::close);
        FIXED_BUFFERS.clear();
        SilhouetteBufferSource.invalidateTypes();
        capturing = null; hasCapture = false;
        if (handTarget != null) { handTarget.destroyBuffers(); handTarget = null; }
    }

    public static boolean hasFreshCapture() {
        return hasCapture;
    }

    public static void clearFreshCapture() {
        hasCapture = false;
    }
}
