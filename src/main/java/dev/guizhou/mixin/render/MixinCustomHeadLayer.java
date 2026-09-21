package dev.guizhou.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.guizhou.features.modules.render.NoRenderModule;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 1.21.1: head layers render directly against the living entity. */
@Mixin(CustomHeadLayer.class)
public class MixinCustomHeadLayer {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void guizhouware$noPlayerHead(PoseStack poseStack, MultiBufferSource bufferSource,
                                          int packedLight, LivingEntity entity,
                                          float limbSwing, float limbSwingAmount,
                                          float partialTick, float ageInTicks,
                                          float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (NoRenderModule.isActive(m -> m.noArmor.getValue()) && entity.getType() == EntityType.PLAYER) {
            ci.cancel();
        }
    }
}
