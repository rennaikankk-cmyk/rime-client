package dev.guizhou.util.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.Uniform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostPass;

/**
 * 1.21.1 hand outline/glow pipeline built from real PostPasses over the offscreen
 * hand silhouette. Ping-pong targets are owned here; the final pass writes an
 * offscreen result which is then blended onto the main framebuffer (PostPass clears
 * its output, so no pass may target main directly). Programs live under
 * assets/minecraft/shaders/program/gzwx_* (EffectInstance resolves that fixed path);
 * the DiffuseSampler/aux samplers and ProjMat/InSize/OutSize/ScreenSize uniforms are
 * supplied by PostPass itself.
 */
public final class HandShaderChain {

    private static final float FILL_ALPHA = 0.35f;
    private static final float OUTLINE_ALPHA = 1.0f;

    private static RenderTarget tmpA;
    private static RenderTarget tmpB;
    private static RenderTarget tmpC;
    private static PostPass dilateH;
    private static PostPass outline;
    private static PostPass glowH;
    private static PostPass outlineGlow;
    private static int lastWidth, lastHeight;
    private static RenderTarget boundHand;
    private static boolean healthy;
    private static String failure;

    private static int lastLineWidth = Integer.MIN_VALUE;
    private static boolean lastFill;
    private static int lastGlowRadius = Integer.MIN_VALUE;
    private static float lastGlowIntensity = Float.NaN;

    private HandShaderChain() {}

    public static boolean healthy() {
        return healthy;
    }

    public static String failure() {
        return failure;
    }

    /** Returns true when the chain is ready for {@link #processAndComposite(float)}. */
    public static boolean get(boolean outlineWanted, int thickness, boolean fill,
                              boolean glow, int glowRadius, float glowIntensity) {
        int lineWidth = outlineWanted ? Math.max(1, thickness) : 0;
        int glowR = glow ? Math.max(0, glowRadius) : 0;
        if (lineWidth == 0 && !fill && glowR == 0) return false;

        if (healthy && boundHand == HandShaderRender.captureTarget()
                && lastWidth == Minecraft.getInstance().getWindow().getWidth()
                && lastHeight == Minecraft.getInstance().getWindow().getHeight()
                && lineWidth == lastLineWidth && fill == lastFill
                && glowR == lastGlowRadius && glowIntensity == lastGlowIntensity) {
            return true;
        }

        if (!rebuild(lineWidth, fill, glowR, glowIntensity)) {
            PostPipelineSupport.warnThrottled("hand-chain",
                    "Hand shader chain rebuild failed: " + failure, null);
            return healthy;
        }
        lastLineWidth = lineWidth;
        lastFill = fill;
        lastGlowRadius = glowR;
        lastGlowIntensity = glowIntensity;
        return true;
    }

    private static boolean rebuild(int lineWidth, boolean fill, int glowRadius, float glowIntensity) {
        try {
            Minecraft mc = Minecraft.getInstance();
            RenderTarget hand = HandShaderRender.captureTarget();
            if (hand == null) {
                failure = "capture target not initialised";
                return false;
            }
            int w = Math.max(1, mc.getWindow().getWidth());
            int h = Math.max(1, mc.getWindow().getHeight());
            if (tmpA == null) {
                tmpA = PostPipelineSupport.screenSizedTarget("guizhouware_hand_tmp_a");
                tmpB = PostPipelineSupport.screenSizedTarget("guizhouware_hand_tmp_b");
                tmpC = PostPipelineSupport.screenSizedTarget("guizhouware_hand_tmp_c");
            }
            PostPipelineSupport.resizeIfNeeded(tmpA, w, h);
            PostPipelineSupport.resizeIfNeeded(tmpB, w, h);
            PostPipelineSupport.resizeIfNeeded(tmpC, w, h);

            closePasses();
            dilateH = new PostPass(mc.getResourceManager(), "gzwx_hand_outline_h", hand, tmpA, false);
            setInt(dilateH, "LineWidth", lineWidth);

            if (glowRadius > 0) {
                glowH = new PostPass(mc.getResourceManager(), "gzwx_hand_glow_h", hand, tmpB, false);
                setInt(glowH, "GlowRadius", glowRadius);
                outlineGlow = new PostPass(mc.getResourceManager(), "gzwx_hand_outline_glow", tmpA, tmpC, false);
                outlineGlow.addAuxAsset("OrigSampler", hand::getColorTextureId, w, h);
                outlineGlow.addAuxAsset("GlowSampler", tmpB::getColorTextureId, w, h);
                setFloat(outlineGlow, "FillAlpha", fill ? FILL_ALPHA : 0.0f);
                setFloat(outlineGlow, "OutlineAlpha", OUTLINE_ALPHA);
                setFloat(outlineGlow, "GlowIntensity", glowIntensity);
                setInt(outlineGlow, "LineWidth", lineWidth);
                setInt(outlineGlow, "GlowRadius", glowRadius);
            } else {
                outline = new PostPass(mc.getResourceManager(), "gzwx_hand_outline", tmpA, tmpB, false);
                outline.addAuxAsset("OrigSampler", hand::getColorTextureId, w, h);
                setFloat(outline, "FillAlpha", fill ? FILL_ALPHA : 0.0f);
                setFloat(outline, "OutlineAlpha", OUTLINE_ALPHA);
                setInt(outline, "LineWidth", lineWidth);
            }
            boundHand = hand;
            lastWidth = w; lastHeight = h;
            healthy = true;
            failure = null;
            return true;
        } catch (Exception e) {
            healthy = false;
            failure = e.toString();
            PostPipelineSupport.warnThrottled("hand-chain-build", "Hand shader chain build failed", e);
            return false;
        }
    }

    public static void processAndComposite(float partialTick) {
        if (!healthy || !HandShaderRender.hasFreshCapture()) return;
        HandShaderRender.clearFreshCapture();
        int w = Math.max(1, Minecraft.getInstance().getWindow().getWidth());
        int h = Math.max(1, Minecraft.getInstance().getWindow().getHeight());
        try {
            dilateH.setOrthoMatrix(PostPipelineSupport.ortho(w, h));
            dilateH.process(partialTick);
            if (outlineGlow != null && glowH != null) {
                glowH.setOrthoMatrix(PostPipelineSupport.ortho(w, h));
                glowH.process(partialTick);
                outlineGlow.setOrthoMatrix(PostPipelineSupport.ortho(w, h));
                outlineGlow.process(partialTick);
                PostPipelineSupport.blendOntoMain(tmpC);
            } else if (outline != null) {
                outline.setOrthoMatrix(PostPipelineSupport.ortho(w, h));
                outline.process(partialTick);
                PostPipelineSupport.blendOntoMain(tmpB);
            }
        } catch (Exception e) {
            healthy = false;
            PostPipelineSupport.warnThrottled("hand-chain-run", "Hand shader chain failed at runtime", e);
        }
    }

    public static void invalidate() {
        healthy = false; boundHand = null; closePasses();
    }

    private static void closePasses() {
        if (dilateH != null) { dilateH.close(); dilateH = null; }
        if (outline != null) { outline.close(); outline = null; }
        if (glowH != null) { glowH.close(); glowH = null; }
        if (outlineGlow != null) { outlineGlow.close(); outlineGlow = null; }
    }

    private static void setInt(PostPass pass, String name, int value) {
        Uniform uniform = pass.getEffect().getUniform(name);
        if (uniform != null) uniform.set(value);
    }

    private static void setFloat(PostPass pass, String name, float value) {
        Uniform uniform = pass.getEffect().getUniform(name);
        if (uniform != null) uniform.set(value);
    }
}
