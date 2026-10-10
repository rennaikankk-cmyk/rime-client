package dev.guizhou.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.guizhou.features.modules.render.SkyboxModule;
import dev.guizhou.util.render.SkyboxRenderer;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.world.level.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRenderer.class)
public class MixinSkyRenderer {

    @Inject(method = "renderSkyDisc", at = @At("HEAD"), cancellable = true)
    private void guizhouware$replaceSky(int skyColor, CallbackInfo ci) {
        SkyboxModule module = SkyboxModule.getInstance();
        if (module != null && module.isEnabled()) {
            java.awt.Color c = module.auroraColor.getValue();
            SkyboxRenderer.render(module.mode.getValue(),
                    c.getRed() / 255.0f, c.getGreen() / 255.0f, c.getBlue() / 255.0f);
            ci.cancel();
        }
    }

    @Inject(method = "renderSunMoonAndStars", at = @At("HEAD"), cancellable = true)
    private void guizhouware$cancelCelestial(PoseStack poseStack, float sunAngle, float moonAngle, float starAngle,
                                          MoonPhase moonPhase, float rainBrightness, float starBrightness, CallbackInfo ci) {
        if (SkyboxModule.isActive(m -> m.replaceCelestial.getValue())) {
            ci.cancel();
        }
    }

    @Inject(method = "renderSunriseAndSunset", at = @At("HEAD"), cancellable = true)
    private void guizhouware$cancelSunrise(PoseStack poseStack, float sunAngle, int color, CallbackInfo ci) {
        if (SkyboxModule.isActive(m -> m.replaceCelestial.getValue())) {
            ci.cancel();
        }
    }

    @Inject(method = "renderDarkDisc", at = @At("HEAD"), cancellable = true)
    private void guizhouware$cancelDarkDisc(CallbackInfo ci) {
        if (SkyboxModule.isActive(m -> m.replaceCelestial.getValue())) {
            ci.cancel();
        }
    }
}
