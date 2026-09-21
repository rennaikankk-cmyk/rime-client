package dev.guizhou.mixin.entity;

import dev.guizhou.features.modules.movement.NoSlowModule;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

@Mixin(SlimeBlock.class)
public class MixinSlimeBlock {
    @WrapOperation(method = "stepOn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"), require = 1)
    private void guizhouware$noSlowSlime(Entity entity, Vec3 slowedVelocity, Operation<Void> original) {
        if (entity != Minecraft.getInstance().player || !NoSlowModule.isActive(m -> m.slimeBlock.getValue())) {
            original.call(entity, slowedVelocity);
        }
    }
}
