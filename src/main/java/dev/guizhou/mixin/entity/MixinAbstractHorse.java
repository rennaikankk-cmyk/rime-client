package dev.guizhou.mixin.entity;

import dev.guizhou.features.modules.movement.EntityControlModule;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractHorse.class)
public class MixinAbstractHorse {
    @Inject(method = "isSaddled", at = @At("HEAD"), cancellable = true)
    private void guizhouware$steerWithoutSaddle(CallbackInfoReturnable<Boolean> cir) {
        if (EntityControlModule.active()) cir.setReturnValue(true);
    }
}
