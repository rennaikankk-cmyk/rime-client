package dev.guizhou.mixin.entity;

import dev.guizhou.GuizhouWare;
import dev.guizhou.manager.RotationManager;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static dev.guizhou.util.traits.Util.mc;

@Mixin(value = LocalPlayer.class, priority = Integer.MAX_VALUE)
public class MixinLocalPlayerRotation {
    @Shadow private float xRotLast;

    @Unique private float guizhouware$savedYaw, guizhouware$savedPitch;
    @Unique private boolean guizhouware$spoofed;

    @Inject(method = "sendPosition", at = @At("HEAD"))
    private void guizhouware$spoofRotationHead(CallbackInfo ci) {
        guizhouware$spoofed = false;
        RotationManager rm = GuizhouWare.rotationManager;
        if (rm == null) return;

        boolean motion = rm.isRotating();
        boolean silent = rm.isSilentSyncRequired();
        if (!motion && !silent) return;

        guizhouware$savedYaw = mc.player.getYRot();
        guizhouware$savedPitch = mc.player.getXRot();

        float outYaw = guizhouware$savedYaw;
        float outPitch = guizhouware$savedPitch;

        if (motion) {
            outYaw = rm.getRotationYaw();
            outPitch = rm.getRotationPitch();
        } else {
            xRotLast -= 4;
            float f = (float) ((Math.random() * 2.0 - 1.0) * 0.001f);
            outPitch = Mth.clamp(outPitch + f, -90.0f, 90.0f);
        }

        mc.player.setYRot(outYaw);
        mc.player.setXRot(outPitch);
        guizhouware$spoofed = true;
    }

    @Inject(method = "sendPosition", at = @At("TAIL"))
    private void guizhouware$spoofRotationTail(CallbackInfo ci) {
        RotationManager rm = GuizhouWare.rotationManager;
        if (rm == null) return;

        if (guizhouware$spoofed) {
            mc.player.setYRot(guizhouware$savedYaw);
            mc.player.setXRot(guizhouware$savedPitch);
            guizhouware$spoofed = false;
        }

        rm.setSilentSyncRequired(false);
        rm.resetSilentTick();
    }
}
