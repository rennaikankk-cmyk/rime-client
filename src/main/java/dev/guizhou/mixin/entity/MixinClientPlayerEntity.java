package dev.guizhou.mixin.entity;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.guizhou.GuizhouWare;
import dev.guizhou.event.Stage;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.entity.player.TickEvent;
import dev.guizhou.event.impl.entity.player.UpdateWalkingPlayerEvent;
import dev.guizhou.features.modules.movement.VelocityModule;
import dev.guizhou.features.modules.movement.NoSlowModule;
import dev.guizhou.features.modules.movement.SprintModule;
import dev.guizhou.features.modules.render.NoRenderModule;
import dev.guizhou.features.modules.world.ScaffoldModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static dev.guizhou.util.traits.Util.EVENT_BUS;

@Mixin(LocalPlayer.class)
public class MixinClientPlayerEntity {

    @Inject(method = "tick", at = @At("HEAD"))
    private void preTickHook(CallbackInfo ci) {
        EVENT_BUS.post(new PreTickEvent());
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void tickHook(CallbackInfo ci) {
        EVENT_BUS.post(new TickEvent());
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;tick()V", shift = At.Shift.AFTER))
    private void tickHook2(CallbackInfo ci) {
        EVENT_BUS.post(new UpdateWalkingPlayerEvent(Stage.PRE));
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;sendPosition()V", shift = At.Shift.AFTER))
    private void tickHook3(CallbackInfo ci) {
        EVENT_BUS.post(new UpdateWalkingPlayerEvent(Stage.POST));
    }

    @Inject(
        method = "aiStep",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/Input;tick(ZF)V", shift = At.Shift.AFTER)
    )
    private void scaffold$suppressSneakAfterInputTick(CallbackInfo ci) {
        ScaffoldModule scaffold = GuizhouWare.moduleManager.getModuleByClass(ScaffoldModule.class);
        if (scaffold == null || !scaffold.isEnabled()) return;

        LocalPlayer self = (LocalPlayer) (Object) this;
        if (!self.input.shiftKeyDown) return;

        self.input.shiftKeyDown = false;
    }

    @Inject(
        method = "aiStep",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/Input;tick(ZF)V", shift = At.Shift.AFTER)
    )
    private void sprint$applyBeforeJump(CallbackInfo ci) {
        SprintModule sprint = GuizhouWare.moduleManager.getModuleByClass(SprintModule.class);
        if (sprint != null && sprint.wantsSprint()) {
            ((LocalPlayer) (Object) this).setSprinting(true);
        }
    }

    @Inject(
        method = "aiStep",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/Input;tick(ZF)V", shift = At.Shift.AFTER)
    )
    private void guizhouware$moveFixAfterInputTick(CallbackInfo ci) {
        var rotation = GuizhouWare.rotationManager;
        if (rotation == null || !rotation.isRotating() || !rotation.isMoveFixEnabled()) return;

        LocalPlayer self = (LocalPlayer)(Object) this;
        net.minecraft.client.player.Input real = self.input;
        net.minecraft.client.player.Input fixed = rotation.computeMoveFixInput(real, self.getYRot());
        if (fixed == real) return;

        // 1.21.1: apply the fixed impulses directly to the live input object.
        self.input.leftImpulse = fixed.leftImpulse;
        self.input.forwardImpulse = fixed.forwardImpulse;
        self.input.left = fixed.left;
        self.input.right = fixed.right;
        self.input.up = fixed.up;
        self.input.down = fixed.down;
        self.input.jumping = fixed.jumping;
        self.input.shiftKeyDown = fixed.shiftKeyDown;

    }

    @Inject(method = "moveTowardsClosestSpace", at = @At("HEAD"), cancellable = true)
    private void cancelBlockPush(double x, double z, CallbackInfo ci) {
        VelocityModule velocity = GuizhouWare.moduleManager.getModuleByClass(VelocityModule.class);
        if (velocity != null && velocity.isEnabled() && velocity.blockPush.getValue() && velocity.phaseConditionMet()) {
            ci.cancel();
        }
    }

    // 1.21.1: the use-item input slowdown lives in aiStep — a single isUsingItem()
    // call gates the branch scaling input.leftImpulse/forwardImpulse by 0.2f (and
    // resetting sprintTriggerTime). MixinExtras here has no ModifyConstant, so the
    // gate itself is inverted: returning false skips the entire slowdown branch.
    @ModifyExpressionValue(
        method = "aiStep",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z"),
        require = 1
    )
    private boolean noSlow$eatSpeed(boolean original) {
        return original && !NoSlowModule.shouldCancelConsumeSlow();
    }

    @Inject(method = "isMovingSlowly", at = @At("HEAD"), cancellable = true)
    private void fastCrawl(CallbackInfoReturnable<Boolean> cir) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (NoSlowModule.isActive(m -> self.isVisuallyCrawling() && m.crawl.getValue()
                || self.isCrouching() && m.sneak.getValue())) {
            cir.setReturnValue(false);
        }
    }

    @ModifyExpressionValue(
        require = 0, // Portal screens are optional and may be replaced by another client.
        method = "handleConfusionTransitionEffect",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;isPauseScreen()Z")
    )
    private boolean guizhouware$portalGui(boolean original) {
        if (NoRenderModule.isActive(m -> m.portalGui.getValue())) {
            return false;
        }
        return original;
    }
}
