package dev.guizhou.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.guizhou.features.modules.render.NoRenderModule;
import dev.guizhou.util.player.InsideBlockUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenEffectRenderer.class)
public class MixinScreenEffectRenderer {
    @Inject(method = "renderFire", at = @At("HEAD"), cancellable = true)
    private static void guizhouware$noFire(PoseStack matrices, MultiBufferSource vertexConsumers, TextureAtlasSprite sprite, CallbackInfo ci) {
        if (NoRenderModule.isActive(m -> m.noFire.getValue())) {
            ci.cancel();
        }
    }

    @Inject(method = "renderWater", at = @At("HEAD"), cancellable = true)
    private static void guizhouware$noWater(Minecraft client, PoseStack matrices, MultiBufferSource vertexConsumers, CallbackInfo ci) {
        if (NoRenderModule.isActive(m -> m.noLiquid.getValue())) {
            ci.cancel();
        }
    }

    /**
     * Block-in-face overlay. NoRender.NoWall cancels it always; pearl-phase (and any other
     * inside-block state) must also cancel it so the camera can see past the wall after clipping in.
     */
    @Inject(method = "renderTex", at = @At("HEAD"), cancellable = true)
    private static void guizhouware$noWall(TextureAtlasSprite sprite, PoseStack matrices, MultiBufferSource vertexConsumers, CallbackInfo ci) {
        if (NoRenderModule.isActive(m -> m.noWall.getValue()) || InsideBlockUtil.isInsideBlock()) {
            ci.cancel();
        }
    }
}
