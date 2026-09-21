package dev.guizhou.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.render.ShadersModule;
import dev.guizhou.util.render.HandShaderChain;
import dev.guizhou.util.render.HandShaderRender;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {

    // Native 1.21.1 owns this overlay in GameRenderer, not Gui.
    @Inject(method = "renderConfusionOverlay", at = @At("HEAD"), cancellable = true)
    private void guizhouware$noNausea(net.minecraft.client.gui.GuiGraphics graphics, float strength, CallbackInfo ci) {
        if (dev.guizhou.features.modules.render.NoRenderModule.isActive(m -> m.noNausea.getValue())) ci.cancel();
    }

    /**
     * 1.21.1: hands are drawn inside GameRenderer.renderItemInHand(Camera, float,
     * Matrix4f); composite the hand shader chain right after that returns.
     */
    @Inject(method = "renderItemInHand", at = @At("RETURN"))
    private void handShader$composite(net.minecraft.client.Camera camera, float partialTick,
                                      Matrix4f projectionMatrix, CallbackInfo ci) {
        ShadersModule mod = GuizhouWare.moduleManager.getModuleByClass(ShadersModule.class);
        if (mod == null || !mod.wantsHandShader()) return;
        if (HandShaderChain.get(mod.handOutline.getValue(), mod.getHandThickness(), mod.handFill.getValue(),
                mod.handGlow.getValue(), mod.getHandGlowRadius(), mod.getHandGlowIntensity())) {
            HandShaderChain.processAndComposite(partialTick);
        }
    }

    @Inject(method = "displayItemActivation", at = @At("HEAD"), cancellable = true)
    private void guizhouware$noTotem(ItemStack floatingItem, CallbackInfo ci) {
        if (floatingItem.is(Items.TOTEM_OF_UNDYING) && dev.guizhou.features.modules.render.NoRenderModule.isActive(m -> m.noTotem.getValue())) {
            ci.cancel();
        }
    }

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void guizhouware$noBob(PoseStack matrices, float tickDelta, CallbackInfo ci) {
        if (dev.guizhou.features.modules.render.NoRenderModule.isActive(m -> m.noBob.getValue())) {
            ci.cancel();
        }
    }

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void guizhouware$noTilt(PoseStack matrices, float tickDelta, CallbackInfo ci) {
        if (dev.guizhou.features.modules.render.NoRenderModule.isActive(m -> m.noTilt.getValue())) {
            ci.cancel();
        }
    }
}
