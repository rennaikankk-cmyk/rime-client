package dev.guizhou.mixin.render.gui;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.guizhou.util.render.BootSplash;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Re-skins the resource loading overlay. Vanilla still owns timing, the screen drawn
 * underneath, progress smoothing, {@code setOverlay(null)} and the reload completion
 * callback in {@code tick()}; only the backdrop color, Mojang logo and progress bar
 * draws are swapped. The choice is made per frame, so a failed frame reverts to vanilla.
 */
@Mixin(LoadingOverlay.class)
public abstract class MixinLoadingOverlay {
    @Shadow @Final private boolean fadeIn;
    @Shadow private float currentProgress;
    @Shadow private long fadeOutStart;
    @Shadow private long fadeInStart;

    @Unique private boolean gzw$splash;
    @Unique private long gzw$shownAt = -1L;

    @Inject(method = "render", at = @At("HEAD"))
    private void gzw$beginFrame(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        gzw$splash = BootSplash.active();
        if (gzw$shownAt < 0L) gzw$shownAt = Util.getMillis();
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V"))
    private void gzw$backdrop(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color, Operation<Void> original) {
        original.call(graphics, x1, y1, x2, y2, gzw$splash ? BootSplash.backdrop(color >>> 24) : color);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearColorTexture(Lcom/mojang/blaze3d/textures/GpuTexture;I)V"))
    private void gzw$clear(CommandEncoder encoder, GpuTexture texture, int color, Operation<Void> original) {
        original.call(encoder, texture, gzw$splash ? BootSplash.backdrop(0xFF) : color);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V"))
    private void gzw$logo(GuiGraphics graphics, RenderPipeline pipeline, Identifier texture, int x, int y, float u, float v,
                          int width, int height, int regionWidth, int regionHeight, int textureWidth, int textureHeight,
                          int color, Operation<Void> original) {
        if (!gzw$splash) {
            original.call(graphics, pipeline, texture, x, y, u, v, width, height, regionWidth, regionHeight,
                    textureWidth, textureHeight, color);
        }
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/LoadingOverlay;drawProgressBar(Lnet/minecraft/client/gui/GuiGraphics;IIIIF)V"))
    private void gzw$progressBar(LoadingOverlay overlay, GuiGraphics graphics, int x1, int y1, int x2, int y2, float alpha,
                                 Operation<Void> original) {
        if (!gzw$splash) original.call(overlay, graphics, x1, y1, x2, y2, alpha);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void gzw$endFrame(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        long now = Util.getMillis();
        float fadeOut = fadeOutStart > -1L ? (now - fadeOutStart) / 1000f : -1f;
        if (gzw$splash) {
            float fadeInProgress = fadeInStart > -1L ? (now - fadeInStart) / 500f : -1f;
            BootSplash.render(graphics, currentProgress, fadeOut, fadeInProgress, fadeIn, now - gzw$shownAt);
        }
        // Only the launch overlay is created without fade-in; resource-pack reloads (F3+T) fade in.
        if (!fadeIn && fadeOut >= 2f) BootSplash.startupFinished();
    }
}
