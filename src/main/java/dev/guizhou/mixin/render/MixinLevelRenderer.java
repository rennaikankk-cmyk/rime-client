package dev.guizhou.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.guizhou.event.impl.render.RenderBlockOutlineEvent;
import dev.guizhou.features.modules.render.NoRenderModule;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static dev.guizhou.util.traits.Util.EVENT_BUS;

/**
 * 1.21.1: the block outline is drawn through renderHitOutline and the darkness check
 * lives in doesMobEffectBlockSky (both kept from this version).
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {

    @Inject(method = "renderHitOutline", at = @At("HEAD"), cancellable = true)
    public void renderBlockOutline(PoseStack poseStack, VertexConsumer consumer, Entity entity,
                                   double x, double y, double z, BlockPos pos, BlockState state,
                                   CallbackInfo ci) {
        if (EVENT_BUS.post(new RenderBlockOutlineEvent())) {
            ci.cancel();
        }
    }

    @Inject(method = "doesMobEffectBlockSky", at = @At("HEAD"), cancellable = true)
    private void guizhouware$noDarkness(Camera camera, CallbackInfoReturnable<Boolean> cir) {
        if (NoRenderModule.isActive(m -> m.noDarkness.getValue())) {
            cir.setReturnValue(false);
        }
    }
}
