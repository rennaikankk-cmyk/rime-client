package dev.guizhou.mixin.entity;

import dev.guizhou.features.modules.movement.EntityControlModule;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1.21.11 moved {@code isSaddled} off AbstractHorse onto Mob. Horses, donkeys
 * and mules still call it for control, jump and immobility, so the saddle
 * bypass has to land on the method that actually exists.
 */
@Mixin(Mob.class)
public class MixinAbstractHorse {
    @Inject(method = "isSaddled", at = @At("HEAD"), cancellable = true)
    private void guizhouware$steerWithoutSaddle(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof AbstractHorse && EntityControlModule.active()) {
            cir.setReturnValue(true);
        }
    }
}
