package dev.guizhou.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.guizhou.GuizhouWare;
import dev.guizhou.manager.RotationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21.1: no extractRenderState stage. The local player's display rotation is faked by
 * saving the rotation-relevant fields around the render call and writing the
 * RotationManager's interpolated values, so body/head follow silent rotations like the
 * render-state override did on 1.21.11.
 */
@Mixin(LivingEntityRenderer.class)
public class MixinLivingEntityRenderer {

    @Unique
    private float[] guizhouware$saved;

    @Unique
    private boolean guizhouware$overridden;

    @Inject(method = "render", at = @At("HEAD"))
    private void guizhouware$renderMotionRotationHead(LivingEntity entity, float entityYaw,
                                                      float partialTicks, PoseStack poseStack,
                                                      MultiBufferSource bufferSource, int packedLight,
                                                      CallbackInfo ci) {
        if (entity != Minecraft.getInstance().player) return;
        RotationManager rm = GuizhouWare.rotationManager;
        if (rm == null || !rm.isRotating()) return;

        float yaw = rm.getRenderYaw(partialTicks);
        float pitch = rm.getRenderPitch(partialTicks);
        guizhouware$saved = new float[]{
                entity.yBodyRot, entity.yBodyRotO,
                entity.getYRot(), entity.yRotO,
                entity.getYHeadRot(), entity.yHeadRotO,
                entity.getXRot(), entity.xRotO
        };
        entity.yBodyRot = yaw;
        entity.yBodyRotO = yaw;
        entity.setYRot(yaw);
        entity.yRotO = yaw;
        entity.setYHeadRot(yaw);
        entity.yHeadRotO = yaw;
        entity.setXRot(pitch);
        entity.xRotO = pitch;
        guizhouware$overridden = true;
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void guizhouware$renderMotionRotationTail(LivingEntity entity, float entityYaw,
                                                      float partialTicks, PoseStack poseStack,
                                                      MultiBufferSource bufferSource, int packedLight,
                                                      CallbackInfo ci) {
        if (!guizhouware$overridden) return;
        guizhouware$overridden = false;
        float[] saved = guizhouware$saved;
        entity.yBodyRot = saved[0];
        entity.yBodyRotO = saved[1];
        entity.setYRot(saved[2]);
        entity.yRotO = saved[3];
        entity.setYHeadRot(saved[4]);
        entity.yHeadRotO = saved[5];
        entity.setXRot(saved[6]);
        entity.xRotO = saved[7];
    }
}
