package dev.guizhou.util.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared plumbing for the 1.21.1 post pipelines: the ortho matrix PostPass needs per
 * target size, the transparent composite onto the main framebuffer (PostPass clears
 * its output, so a final pass must never target main), and rate-limited init logging
 * so a broken feature is visible instead of silently degrading.
 */
public final class PostPipelineSupport {
    private static final Logger LOGGER = LoggerFactory.getLogger("GuizhouWare/Post");

    private static final Map<String, Long> LAST_WARN = new HashMap<>();
    private static final long WARN_INTERVAL_MS = 30_000L;

    private PostPipelineSupport() {}

    /** Ortho matrix matching PostChain.updateOrthoMatrix for pixel-coord quads. */
    public static Matrix4f ortho(int width, int height) {
        return new Matrix4f().ortho(0.0f, width, 0.0f, height, 0.1f, 1000.0f);
    }

    public static RenderTarget screenSizedTarget(String name) {
        Minecraft mc = Minecraft.getInstance();
        return new TextureTarget(
                Math.max(1, mc.getWindow().getWidth()), Math.max(1, mc.getWindow().getHeight()), true, false);
    }

    public static void resizeIfNeeded(RenderTarget target, int width, int height) {
        if (target != null && (target.width != width || target.height != height)) {
            target.resize(width, height, true);
        }
    }

    /**
     * Blends the offscreen result over the main framebuffer without clearing it —
     * the world image is preserved under the effect.
     */
    public static void blendOntoMain(RenderTarget source) {
        if (source == null) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        main.bindWrite(false);
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        // The native fullscreen blitter supplies its own projection and never clears main.
        // false preserves blending, so transparent effect pixels retain the world.
        source.blitToScreen(main.width, main.height, false);
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        source.unbindRead();
    }

    /** Rate-limited warning so a failing pipeline stays visible without log spam. */
    public static void warnThrottled(String key, String message, Throwable t) {
        long now = System.currentTimeMillis();
        Long last = LAST_WARN.get(key);
        if (last != null && now - last < WARN_INTERVAL_MS) return;
        LAST_WARN.put(key, now);
        if (t != null) LOGGER.warn(message, t);
        else LOGGER.warn(message);
    }
}
