package dev.guizhou.mixin.client;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.client.UnfocusedFpsModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FramerateLimitTracker.class)
public class MixinFramerateLimitTracker {
    @ModifyReturnValue(method = "getFramerateLimit", at = @At("RETURN"))
    private int rime$backgroundLimit(int vanillaLimit) {
        if (GuizhouWare.moduleManager == null) return vanillaLimit;
        UnfocusedFpsModule module = GuizhouWare.moduleManager.getModuleByClass(UnfocusedFpsModule.class);
        return module != null ? module.limit(vanillaLimit) : vanillaLimit;
    }
}
