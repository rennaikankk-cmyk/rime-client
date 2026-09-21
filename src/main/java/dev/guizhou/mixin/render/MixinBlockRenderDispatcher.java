package dev.guizhou.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.guizhou.features.modules.render.NoRenderModule;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 1.21.1: renderBatched carries no model part list or seed. */
@Mixin(BlockRenderDispatcher.class)
public class MixinBlockRenderDispatcher {

    @Inject(method = "renderBatched", at = @At("HEAD"), cancellable = true)
    private void guizhouware$noCrystalFire(BlockState state, BlockPos pos, BlockAndTintGetter level,
                                           PoseStack poseStack, VertexConsumer consumer,
                                           boolean checkSides, RandomSource random, CallbackInfo ci) {
        if (state.getBlock() instanceof BaseFireBlock
                && NoRenderModule.isActive(m -> m.noCrystalFire.getValue())) {
            ci.cancel();
        }
    }
}
