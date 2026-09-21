package dev.guizhou.mixin.render.gui;

import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.features.modules.render.NoRenderModule;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static dev.guizhou.util.traits.Util.EVENT_BUS;

@Mixin(Gui.class)
public abstract class MixinInGameHud {
    @Shadow
    protected abstract void renderTextureOverlay(GuiGraphics context, ResourceLocation texture, float alpha);

    @Shadow
    @Final
    private static ResourceLocation POWDER_SNOW_OUTLINE_LOCATION;

    @Inject(method = "render", at = @At("RETURN"))
    public void render(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
        if (Minecraft.getInstance().gui.getDebugOverlay().showDebugScreen()) return;

        Render2DEvent event = new Render2DEvent(context, tickCounter.getGameTimeDeltaPartialTick(true));
        EVENT_BUS.post(event);
    }

    @Inject(method = "renderEffects", at = @At("HEAD"), cancellable = true)
    private void guizhouware$noPotions(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
        if (NoRenderModule.isActive(m -> m.noPotIcon.getValue())) {
            ci.cancel();
        }
    }

    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void guizhouware$noCrosshair(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
        if (NoRenderModule.isActive(m -> m.noCrosshair.getValue())) {
            ci.cancel();
        }
    }

    @Inject(method = "renderSelectedItemName", at = @At("HEAD"), cancellable = true)
    private void guizhouware$noItemName(GuiGraphics context, CallbackInfo ci) {
        if (NoRenderModule.isActive(m -> m.noItemName.getValue())) {
            ci.cancel();
        }
    }

    @Inject(method = "renderPortalOverlay", at = @At("HEAD"), cancellable = true)
    private void guizhouware$noPortal(GuiGraphics context, float alpha, CallbackInfo ci) {
        if (NoRenderModule.isActive(m -> m.noPortal.getValue())) {
            ci.cancel();
        }
    }

    @Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true)
    private void guizhouware$noVignette(GuiGraphics context, Entity entity, CallbackInfo ci) {
        if (NoRenderModule.isActive(m -> m.noVignette.getValue())) {
            ci.cancel();
        }
    }

    @WrapOperation(
        require = 0, // Optional visual hook: a foreign overwrite may remove the vanilla invocation.
        method = "renderCameraOverlays",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Gui;renderTextureOverlay(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/resources/ResourceLocation;F)V"
        )
    )
    private void guizhouware$noCameraOverlay(Gui instance, GuiGraphics context, ResourceLocation texture, float alpha, Operation<Void> original) {
        if (NoRenderModule.isActive(m -> m.noPowderedSnow.getValue()) && POWDER_SNOW_OUTLINE_LOCATION.equals(texture)) {
            return;
        }
        if (NoRenderModule.isActive(m -> m.noPumpkin.getValue()) && texture.getPath().contains("pumpkin")) {
            return;
        }
        original.call(instance, context, texture, alpha);
    }
}
