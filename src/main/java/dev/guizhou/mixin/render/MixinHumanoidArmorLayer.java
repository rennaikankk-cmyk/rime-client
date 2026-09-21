package dev.guizhou.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.guizhou.features.modules.render.NoRenderModule;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21.1: armor layers render via render(PoseStack, MultiBufferSource, packedLight,
 * entity, 6x float) — no render-state indirection.
 */
@Mixin(HumanoidArmorLayer.class)
public class MixinHumanoidArmorLayer {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void guizhouware$noArmor(PoseStack poseStack, MultiBufferSource bufferSource,
                                     int packedLight, LivingEntity entity,
                                     float limbSwing, float limbSwingAmount,
                                     float partialTick, float ageInTicks,
                                     float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (NoRenderModule.isActive(m -> m.noArmor.getValue()) && entity.getType() == EntityType.PLAYER) {
            ci.cancel();
        }
    }
}
