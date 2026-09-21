package dev.guizhou.mixin.render;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.render.ShadersModule;
import dev.guizhou.util.render.WorldChamsChain;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1.21.1: the entity outline chain is LevelRenderer.entityEffect, processed inside
 * renderLevel. The shader module forces the outline pass on and, in Default mode,
 * wraps that process call to the locally built chams chain.
 */
@Mixin(LevelRenderer.class)
public class MixinShaderLevelRenderer {

    @Shadow
    private PostChain entityEffect;

    @Inject(method = "shouldShowEntityOutlines", at = @At("HEAD"), cancellable = true)
    private void guizhouware$forceOutlines(CallbackInfoReturnable<Boolean> cir) {
        ShadersModule shaders = GuizhouWare.moduleManager == null
                ? null
                : GuizhouWare.moduleManager.getModuleByClass(ShadersModule.class);
        if (shaders != null && shaders.isEnabled() && shaders.wantsOutlines()) {
            cir.setReturnValue(true);
        }
    }

    @WrapOperation(
        require = 0, // Optional visual hook: a foreign overwrite may remove the vanilla invocation.
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/PostChain;process(F)V",
            ordinal = 0
        )
    )
    private void guizhouware$swapEntityOutlineChain(PostChain vanilla, float partialTick, Operation<Void> original) {
        ShadersModule shaders = GuizhouWare.moduleManager == null
                ? null
                : GuizhouWare.moduleManager.getModuleByClass(ShadersModule.class);
        if (shaders != null && shaders.isEnabled()
                && shaders.mode.getValue() == ShadersModule.Mode.Default
                && entityEffect == vanilla) {
            WorldChamsChain.updateParameters(
                    shaders.getGlowRadius(), shaders.getGlowIntensity(),
                    shaders.getFillTint(), shaders.getFillAlpha());
            // Our chain regenerates the vanilla final target; the native
            // doEntityOutline performs the single composite afterwards.
            WorldChamsChain.process(vanilla, partialTick);
            return;
        }
        original.call(vanilla, partialTick);
    }
}
