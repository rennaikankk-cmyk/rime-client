package dev.guizhou.mixin.entity;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.render.NametagsModule;
import net.minecraft.client.renderer.entity.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public class MixinEntityRenderer {
    @Inject(method = "renderNameTag", at = @At("HEAD"), cancellable = true)
    private void cancelVanillaNameTag(CallbackInfo ci) {
        NametagsModule nametags = GuizhouWare.moduleManager.getModuleByClass(NametagsModule.class);
        if (nametags != null && nametags.isEnabled()) {
            ci.cancel();
        }
    }
}
