package dev.guizhou.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.guizhou.features.modules.render.NoRenderModule;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChestRenderer.class)
public class MixinChestRenderer {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void guizhouware$tileEntityRange(BlockEntity blockEntity, float partialTick,
                                              PoseStack matrices, MultiBufferSource bufferSource,
                                              int packedLight, int packedOverlay, CallbackInfo ci) {
        NoRenderModule module = NoRenderModule.getInstance();
        if (module == null || !module.isEnabled() || module.tileEntity.getValue() < 2) return;

        Vec3 cameraPos = net.minecraft.client.Minecraft.getInstance().getEntityRenderDispatcher().camera.getPosition();
        double distanceSquared = blockEntity.getBlockPos().distToCenterSqr(cameraPos);
        double maxDistance = Math.pow(module.tileEntity.getValue(), 2);
        if (distanceSquared > maxDistance) {
            ci.cancel();
        }
    }
}
