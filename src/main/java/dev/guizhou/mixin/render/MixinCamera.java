package dev.guizhou.mixin.render;

import dev.guizhou.features.modules.render.FreecamModule;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class MixinCamera {
    @Shadow private boolean detached;

    @Shadow protected abstract void setPosition(Vec3 position);

    @Shadow protected abstract void setRotation(float yRot, float xRot);

    /** Freecam: detached so the local player model renders and the first-person hand does not. */
    @Inject(method = "setup", at = @At("TAIL"))
    private void guizhouware$freecam(Level level, Entity entity, boolean detached, boolean mirror,
                                     float partialTick, CallbackInfo ci) {
        if (!FreecamModule.active()) return;
        this.detached = true;
        setRotation(FreecamModule.cameraYaw(), FreecamModule.cameraPitch());
        setPosition(FreecamModule.cameraPos(partialTick));
    }
}
