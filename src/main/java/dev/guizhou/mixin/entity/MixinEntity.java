package dev.guizhou.mixin.entity;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.movement.VelocityModule;
import dev.guizhou.features.modules.movement.NoSlowModule;
import dev.guizhou.features.modules.render.NoRenderModule;
import dev.guizhou.features.modules.render.ShadersModule;
import dev.guizhou.manager.RotationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class MixinEntity {

    @ModifyVariable(method = "makeStuckInBlock", at = @At("HEAD"), argsOnly = true, require = 1)
    private Vec3 guizhouware$noSlowStuck(Vec3 multiplier, BlockState state, Vec3 original) {
        if ((Object) this != Minecraft.getInstance().player) return multiplier;
        // Only replace the movement multiplier; vanilla still resets fall distance in webs.
        return NoSlowModule.stuckMultiplier(state, multiplier);
    }

    @Inject(method = "getBlockSpeedFactor", at = @At("RETURN"), cancellable = true, require = 1)
    private void guizhouware$noSlowSurface(CallbackInfoReturnable<Float> cir) {
        Entity self = (Entity) (Object) this;
        if (self != Minecraft.getInstance().player || cir.getReturnValue() >= 1.0f) return;
        BlockState state = self.level().getBlockState(self.blockPosition());
        if (state.is(Blocks.WATER) || state.is(Blocks.BUBBLE_COLUMN)) return;
        if (state.getBlock().getSpeedFactor() == 1.0f) {
            state = self.level().getBlockState(self.getBlockPosBelowThatAffectsMyMovement());
        }
        if (NoSlowModule.ignoresSpeedFactor(state)) cir.setReturnValue(1.0f);
    }

    @Inject(method = "getLookAngle", at = @At("HEAD"), cancellable = true)
    private void guizhouware$spoofLookAngle(CallbackInfoReturnable<Vec3> cir) {
        Entity self = (Entity) (Object) this;
        if (self != Minecraft.getInstance().player) return;

        RotationManager rm = GuizhouWare.rotationManager;
        if (rm == null || !rm.isMoveFixEnabled() || !rm.isRotating()) return;

        cir.setReturnValue(self.calculateViewVector(rm.getRotationPitch(), rm.getRotationYaw()));
    }
    @Inject(method = "spawnSprintParticle", at = @At("HEAD"), cancellable = true)
    private void guizhouware$noLandParticles(CallbackInfo ci) {
        if (NoRenderModule.isActive(m -> m.noLandParticle.getValue())) {
            ci.cancel();
        }
    }

    @Inject(method = "isCurrentlyGlowing", at = @At("RETURN"), cancellable = true)
    private void onIsCurrentlyGlowing(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;

        ShadersModule shaders = GuizhouWare.moduleManager.getModuleByClass(ShadersModule.class);
        if (shaders != null && shaders.isEnabled() && shaders.shouldShader(self)) {
            cir.setReturnValue(true);
            return;
        }

    }

    @Inject(method = "getTeamColor", at = @At("RETURN"), cancellable = true)
    private void onGetTeamColor(CallbackInfoReturnable<Integer> cir) {
        Entity self = (Entity) (Object) this;

        ShadersModule shaders = GuizhouWare.moduleManager.getModuleByClass(ShadersModule.class);
        if (shaders != null && shaders.isEnabled() && shaders.shouldShader(self)) {
            cir.setReturnValue(shaders.getRgbFor(self));
            return;
        }

    }

    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void cancelEntityPush(Entity other, CallbackInfo ci) {
        LocalPlayer localPlayer = Minecraft.getInstance().player;
        if (localPlayer == null) return;
        Object self = this;
        // Cheap identity test first: the collision query below must not run for every mob pair.
        if (self != localPlayer && other != localPlayer) return;
        VelocityModule velocity = GuizhouWare.moduleManager.getModuleByClass(VelocityModule.class);
        if (velocity == null || !velocity.isEnabled() || !velocity.entityPush.getValue() || !velocity.phaseConditionMet()) return;
        ci.cancel();
    }

    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void guizhouware$freecamTurn(double yaw, double pitch, CallbackInfo ci) {
        if ((Object) this != Minecraft.getInstance().player) return;
        if (dev.guizhou.features.modules.render.FreecamModule.consumeTurn(yaw, pitch)) ci.cancel();
    }
}
