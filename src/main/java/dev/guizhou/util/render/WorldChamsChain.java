package dev.guizhou.util.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.Uniform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;

/**
 * 1.21.1 chams pipeline replacing the vanilla entity outline process call. Input is
 * the vanilla entityEffect chain's "final" temp target (the outline silhouette);
 * passes run colour-average (c) -> dual-channel dilate (h) -> composite (v, aux
 * ColorSampler/OrigSampler), writing an offscreen result that is blended onto the
 * main framebuffer — never clearing the world image.
 */
public final class WorldChamsChain {

    private static final int LINE_WIDTH = 1;

    private static RenderTarget tmpColor;
    private static RenderTarget tmpDilate;
    private static RenderTarget tmpOut;
    private static PostPass passC;
    private static PostPass passH;
    private static PostPass passV;
    private static PostPass copyPass;
    private static boolean healthy;
    private static String failure;
    private static RenderTarget boundInput;
    private static int lastWidth, lastHeight;

    // last requested parameters (the redirect feeds the live module values)
    private static volatile int currentGlowRadius = 2;
    private static volatile float currentGlowIntensity = 1.0f;
    private static volatile float currentFillTint = 1.0f;
    private static volatile float currentFillAlpha = 0.35f;

    private static int lastGlowRadius = Integer.MIN_VALUE;
    private static float lastGlowIntensity = Float.NaN;
    private static float lastFillTint = Float.NaN;
    private static float lastFillAlpha = Float.NaN;

    private WorldChamsChain() {}

    public static boolean healthy() {
        return healthy;
    }

    public static String failure() {
        return failure;
    }

    public static void updateParameters(int glowRadius, float glowIntensity, float fillTint, float fillAlpha) {
        currentGlowRadius = glowRadius;
        currentGlowIntensity = glowIntensity;
        currentFillTint = fillTint;
        currentFillAlpha = fillAlpha;
    }

    /** Called instead of the vanilla outline chain process. */
    public static void process(PostChain vanilla, float partialTick) {
        if (!get(vanilla, currentGlowRadius, currentGlowIntensity, currentFillTint, currentFillAlpha)) {
            return;
        }
        int w = Math.max(1, Minecraft.getInstance().getWindow().getWidth());
        int h = Math.max(1, Minecraft.getInstance().getWindow().getHeight());
        try {
            passC.setOrthoMatrix(PostPipelineSupport.ortho(w, h));
            passC.process(partialTick);
            passH.setOrthoMatrix(PostPipelineSupport.ortho(w, h));
            passH.process(partialTick);
            passV.setOrthoMatrix(PostPipelineSupport.ortho(w, h));
            passV.process(partialTick);
            // Copy the processed effect back into the vanilla final target; the later
            // native doEntityOutline supplies the single composite to the screen.
            // (Never run vanilla.process over the already-processed effect.)
            copyPass.setOrthoMatrix(PostPipelineSupport.ortho(w, h));
            copyPass.process(partialTick);
        } catch (Exception e) {
            healthy = false;
            PostPipelineSupport.warnThrottled("chams-chain-run", "Chams chain failed at runtime", e);
        }
    }

    private static boolean get(PostChain vanillaOutlineChain, int glowRadius, float glowIntensity,
                               float fillTint, float fillAlpha) {
        if (!healthy || boundInput != vanillaOutlineChain.getTempTarget("final")
                || lastWidth != Minecraft.getInstance().getWindow().getWidth()
                || lastHeight != Minecraft.getInstance().getWindow().getHeight()
                || glowRadius != lastGlowRadius || glowIntensity != lastGlowIntensity
                || fillTint != lastFillTint || fillAlpha != lastFillAlpha) {
            if (!rebuild(vanillaOutlineChain, glowRadius, glowIntensity, fillTint, fillAlpha)) {
                PostPipelineSupport.warnThrottled("chams-chain",
                        "Chams chain rebuild failed: " + failure, null);
                return false;
            }
            lastGlowRadius = glowRadius;
            lastGlowIntensity = glowIntensity;
            lastFillTint = fillTint;
            lastFillAlpha = fillAlpha;
        }
        return healthy;
    }

    private static boolean rebuild(PostChain vanillaOutlineChain, int glowRadius, float glowIntensity,
                                   float fillTint, float fillAlpha) {
        try {
            Minecraft mc = Minecraft.getInstance();
            RenderTarget input = vanillaOutlineChain.getTempTarget("final");
            if (input == null) {
                failure = "vanilla outline final target missing";
                return false;
            }
            int w = Math.max(1, mc.getWindow().getWidth());
            int h = Math.max(1, mc.getWindow().getHeight());
            if (tmpColor == null) {
                tmpColor = PostPipelineSupport.screenSizedTarget("guizhouware_chams_color");
                tmpDilate = PostPipelineSupport.screenSizedTarget("guizhouware_chams_dilate");
                tmpOut = PostPipelineSupport.screenSizedTarget("guizhouware_chams_out");
            }
            PostPipelineSupport.resizeIfNeeded(tmpColor, w, h);
            PostPipelineSupport.resizeIfNeeded(tmpDilate, w, h);
            PostPipelineSupport.resizeIfNeeded(tmpOut, w, h);

            closePasses();
            passC = new PostPass(mc.getResourceManager(), "gzwx_chams_default_c", input, tmpColor, false);
            setInt(passC, "LineWidth", LINE_WIDTH);
            setInt(passC, "GlowThickness", glowRadius);

            passH = new PostPass(mc.getResourceManager(), "gzwx_chams_default_h", input, tmpDilate, false);
            setInt(passH, "LineWidth", LINE_WIDTH);
            setInt(passH, "GlowThickness", glowRadius);

            passV = new PostPass(mc.getResourceManager(), "gzwx_chams_default_v", tmpDilate, tmpOut, false);
            copyPass = new PostPass(mc.getResourceManager(), "gzwx_copy", tmpOut, input, false);
            passV.addAuxAsset("OrigSampler", input::getColorTextureId, w, h);
            passV.addAuxAsset("ColorSampler", tmpColor::getColorTextureId, w, h);
            setFloat(passV, "GlowIntensity", glowIntensity);
            setFloat(passV, "FillTint", fillTint);
            setFloat(passV, "FillAlpha", fillAlpha);
            setInt(passV, "GlowThickness", glowRadius);
            setInt(passV, "LineWidth", LINE_WIDTH);

            boundInput = input;
            lastWidth = w; lastHeight = h;
            healthy = true;
            failure = null;
            return true;
        } catch (Exception e) {
            healthy = false;
            failure = e.toString();
            PostPipelineSupport.warnThrottled("chams-chain-build", "Chams chain build failed", e);
            return false;
        }
    }

    public static void invalidate() {
        healthy = false; boundInput = null; closePasses();
    }

    private static void closePasses() {
        if (passC != null) { passC.close(); passC = null; }
        if (passH != null) { passH.close(); passH = null; }
        if (passV != null) { passV.close(); passV = null; }
        if (copyPass != null) { copyPass.close(); copyPass = null; }
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
