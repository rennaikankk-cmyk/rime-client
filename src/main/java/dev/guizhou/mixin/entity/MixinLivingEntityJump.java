package dev.guizhou.mixin.entity;

import dev.guizhou.features.modules.movement.NoJumpDelayModule;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class MixinLivingEntityJump {
    @Shadow private int noJumpDelay;

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void guizhouware$clearJumpDelay(CallbackInfo ci) {
        if (NoJumpDelayModule.active()) noJumpDelay = 0;
    }
}
