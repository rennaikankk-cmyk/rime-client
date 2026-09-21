package dev.guizhou.mixin.entity;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.movement.GuangxiElytraModule;
import dev.guizhou.manager.RotationManager;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static dev.guizhou.util.traits.Util.mc;

@Mixin(LivingEntity.class)
public class MixinLivingEntityTravel {
    @Unique private float guizhouware$origYaw, guizhouware$origPitch;
    @Unique private boolean guizhouware$applied;
    @Unique private Vec3 guizhouware$incomingVelocity = Vec3.ZERO;

    // 1.21.1: glide physics is inlined in LivingEntity.travel. Bytecode shape:
    // isFallFlying() -> inline quaternion/damping -> setDeltaMovement(v) ->
    // getDeltaMovement() -> move(SELF, v). The only move() call after isFallFlying
    // belongs to the glide branch, so ModifyArg(index=1) inside that slice receives
    // the final glide velocity; the transformed result is also written back through
    // setDeltaMovement so stored velocity and the move argument stay consistent
    // (matching the old updateFallFlyingMovement-return semantics).
    @ModifyArg(
            method = "travel",
            slice = @Slice(
                    from = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;isFallFlying()Z")
            ),
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V", ordinal = 0),
            index = 1,
            require = 1)
    private Vec3 guizhouware$flightVelocity(Vec3 vanillaVelocity) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self != mc.player || GuizhouWare.moduleManager == null) return vanillaVelocity;
        GuangxiElytraModule module = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        if (module == null || !module.isEnabled()) return vanillaVelocity;
        Vec3 transformed = module.applyFlightVelocity(vanillaVelocity, guizhouware$incomingVelocity, self.getLookAngle());
        if (transformed != vanillaVelocity) {
            self.setDeltaMovement(transformed);
        }
        return transformed;
    }

    @Inject(method = "travel", at = @At("HEAD"))
    private void guizhouware$travelHead(Vec3 movementInput, CallbackInfo ci) {
        guizhouware$applied = false;

        LivingEntity self = (LivingEntity) (Object) this;
        guizhouware$incomingVelocity = self.getDeltaMovement();
        if (self != mc.player || !guizhouware$spoofing()) return;

        guizhouware$origYaw = self.getYRot();
        guizhouware$origPitch = self.getXRot();
        self.setYRot(GuizhouWare.rotationManager.getRotationYaw());
        self.setXRot(GuizhouWare.rotationManager.getRotationPitch());
        guizhouware$applied = true;
    }

    @Inject(method = "travel", at = @At("RETURN"))
    private void guizhouware$travelReturn(Vec3 movementInput, CallbackInfo ci) {
        if (!guizhouware$applied) return;
        guizhouware$applied = false;

        LivingEntity self = (LivingEntity) (Object) this;
        self.setYRot(guizhouware$origYaw);
        self.setXRot(guizhouware$origPitch);
    }

    @ModifyExpressionValue(
            method = "jumpFromGround",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getYRot()F"))
    private float guizhouware$jumpYaw(float original) {
        if ((Object) this != mc.player || !guizhouware$spoofing()) return original;
        return GuizhouWare.rotationManager.getRotationYaw();
    }

    @Unique
    private boolean guizhouware$spoofing() {
        RotationManager rm = GuizhouWare.rotationManager;
        return rm != null && rm.isMoveFixEnabled() && rm.isRotating();
    }

    @ModifyExpressionValue(
            method = "handleRelativeFrictionAndCalculateMovement",
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;horizontalCollision:Z"))
    private boolean guizhouware$noScaffoldClimb(boolean original) {
        if ((Object) this != mc.player || !original) return original;
        return !((LivingEntity) (Object) this).getInBlockState().is(Blocks.SCAFFOLDING);
    }
}
