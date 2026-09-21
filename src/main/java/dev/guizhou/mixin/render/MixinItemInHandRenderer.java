package dev.guizhou.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.render.CrystalHandModule;
import dev.guizhou.features.modules.render.ShadersModule;
import dev.guizhou.features.modules.render.ViewModelModule;
import dev.guizhou.mixin.entity.EntityRotationAccessor;
import dev.guizhou.util.render.HandShaderRender;
import dev.guizhou.util.render.SilhouetteBufferSource;
import dev.guizhou.util.render.TeeBufferSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public class MixinItemInHandRenderer {

    @Unique
    private boolean handShader$capturing;

    @Unique
    private boolean viewModel$scaled;

    @Shadow
    private float mainHandHeight;
    @Shadow
    private float oMainHandHeight;
    @Shadow
    private float offHandHeight;
    @Shadow
    private float oOffHandHeight;

    @Unique
    private float noSway$savedXBob;
    @Unique
    private float noSway$savedXBobO;
    @Unique
    private float noSway$savedYBob;
    @Unique
    private float noSway$savedYBobO;

    @Inject(method = "renderHandsWithItems", at = @At("HEAD"))
    private void noSway$pre(float partialTick, PoseStack poseStack,
                            MultiBufferSource.BufferSource bufferSource, LocalPlayer player,
                            int packedLight, CallbackInfo ci) {
        if (!ViewModelModule.isActive(m -> m.noSway.getValue())) return;
        LocalPlayer self = Minecraft.getInstance().player;
        if (self == null) return;
        EntityRotationAccessor acc = (EntityRotationAccessor) self;
        noSway$savedXBob = acc.guizhouware$getXBob();
        noSway$savedXBobO = acc.guizhouware$getXBobO();
        noSway$savedYBob = acc.guizhouware$getYBob();
        noSway$savedYBobO = acc.guizhouware$getYBobO();
        float xRot = self.getXRot();
        float yRot = self.getYRot();
        acc.guizhouware$setXBob(xRot);
        acc.guizhouware$setXBobO(xRot);
        acc.guizhouware$setYBob(yRot);
        acc.guizhouware$setYBobO(yRot);
    }

    @Inject(method = "renderHandsWithItems", at = @At("RETURN"))
    private void noSway$post(float partialTick, PoseStack poseStack,
                             MultiBufferSource.BufferSource bufferSource, LocalPlayer player,
                             int packedLight, CallbackInfo ci) {
        if (!ViewModelModule.isActive(m -> m.noSway.getValue())) return;
        LocalPlayer self = Minecraft.getInstance().player;
        if (self == null) return;
        EntityRotationAccessor acc = (EntityRotationAccessor) self;
        acc.guizhouware$setXBob(noSway$savedXBob);
        acc.guizhouware$setXBobO(noSway$savedXBobO);
        acc.guizhouware$setYBob(noSway$savedYBob);
        acc.guizhouware$setYBobO(noSway$savedYBobO);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void oldAnimation$tick(CallbackInfo ci) {
        if (!ViewModelModule.isActive(m -> m.oldAnimation.getValue())) return;
        mainHandHeight = 1.0f;
        oMainHandHeight = 1.0f;
        offHandHeight = 1.0f;
        oOffHandHeight = 1.0f;
    }

    @Inject(
        method = "renderArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At("HEAD")
    )
    private void viewModel$push(AbstractClientPlayer player, float partialTick, float interpolatedPitch,
                                InteractionHand hand, float swingProgress, ItemStack stack, float equipProgress,
                                PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                                CallbackInfo ci) {
        viewModel$scaled = false;
        ViewModelModule mod = ViewModelModule.getInstance();
        if (mod == null || !mod.isEnabled()) return;
        viewModel$scaled = true;

        HumanoidArm arm = hand == InteractionHand.MAIN_HAND
                ? player.getMainArm()
                : player.getMainArm().getOpposite();
        float sign = arm == HumanoidArm.RIGHT ? 1f : -1f;

        poseStack.pushPose();
        poseStack.translate(mod.posX.getValue() * sign, mod.posY.getValue(), mod.posZ.getValue());
        float s = mod.scale.getValue().floatValue();
        poseStack.translate(0.56 * sign, -0.52, -0.72);
        poseStack.scale(s, s, s);
        poseStack.translate(-0.56 * sign, 0.52, 0.72);
    }

    @Inject(
        method = "renderArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At("RETURN")
    )
    private void viewModel$pop(AbstractClientPlayer player, float partialTick, float interpolatedPitch,
                               InteractionHand hand, float swingProgress, ItemStack stack, float equipProgress,
                               PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                               CallbackInfo ci) {
        if (!viewModel$scaled) return;
        viewModel$scaled = false;
        poseStack.popPose();
    }

    @ModifyArg(
        method = "renderHandsWithItems",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"
        ),
        index = 5
    )
    private ItemStack crystalHand$modifyRenderedItem(ItemStack original) {
        CrystalHandModule mod = GuizhouWare.moduleManager.getModuleByClass(CrystalHandModule.class);
        if (mod == null || !mod.isEnabled()) return original;
        return mod.getDisplayStack(original);
    }

    @ModifyVariable(method = "renderHandsWithItems", at = @At("HEAD"), argsOnly = true)
    private MultiBufferSource.BufferSource handShader$wrapCollector(MultiBufferSource.BufferSource original) {
        handShader$capturing = false;
        ShadersModule mod = GuizhouWare.moduleManager.getModuleByClass(ShadersModule.class);
        if (mod == null || !mod.wantsHandShader()
                || (!mod.handOutline.getValue() && !mod.handFill.getValue() && !mod.handGlow.getValue())) {
            return original;
        }
        SilhouetteBufferSource secondary = HandShaderRender.beginCapture(mod.getHandRgb());
        if (secondary == null) return original;

        handShader$capturing = true;
        HandShaderRender.capturePaused = false;
        return new TeeWrappedBufferSource(original, secondary);
    }

    @Inject(method = "renderHandsWithItems", at = @At("TAIL"))
    private void handShader$flush(float partialTick, PoseStack poseStack,
                                  MultiBufferSource.BufferSource bufferSource, LocalPlayer player,
                                  int packedLight, CallbackInfo ci) {
        if (!handShader$capturing) return;
        handShader$capturing = false;
        HandShaderRender.capturePaused = false;
        HandShaderRender.flush();
    }

    /** Bridges MultiBufferSource into the BufferSource-typed method parameter.
     * 1.21.1 BufferSource is a class, so extend it; the shared builder is never
     * written to because every accessor delegates. */
    @Unique
    static final class TeeWrappedBufferSource extends MultiBufferSource.BufferSource {
        private static final com.mojang.blaze3d.vertex.ByteBufferBuilder UNUSED =
                new com.mojang.blaze3d.vertex.ByteBufferBuilder(256);
        private final MultiBufferSource.BufferSource primary;
        private final MultiBufferSource secondary;

        TeeWrappedBufferSource(MultiBufferSource.BufferSource primary, MultiBufferSource secondary) {
            super(UNUSED, new java.util.LinkedHashMap<>());
            this.primary = primary;
            this.secondary = secondary;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer getBuffer(net.minecraft.client.renderer.RenderType renderType) {
            return new dev.guizhou.util.render.DualVertexConsumer(
                    primary.getBuffer(renderType), secondary.getBuffer(renderType));
        }

        @Override
        public void endLastBatch() {
            primary.endLastBatch();
        }

        @Override
        public void endBatch() {
            primary.endBatch();
        }

        @Override
        public void endBatch(net.minecraft.client.renderer.RenderType renderType) {
            primary.endBatch(renderType);
        }
    }
}
