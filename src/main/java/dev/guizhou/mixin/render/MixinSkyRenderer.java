package dev.guizhou.mixin.render;

import dev.guizhou.features.modules.render.SkyboxModule;
import dev.guizhou.util.render.SkyboxRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21.1: sky disc, celestial bodies, sunrise band and dark disc are all drawn inside
 * LevelRenderer.renderSky(Matrix4f, Matrix4f, float, Camera, boolean, Runnable).
 * The module replaces the whole sky with its full-screen shader (which also removes
 * the celestial bodies, matching the 1.21.11 replaceCelestial behaviour).
 */
@Mixin(LevelRenderer.class)
public class MixinSkyRenderer {

    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void guizhouware$replaceSky(Matrix4f frustumMatrix, Matrix4f projectionMatrix,
                                        float partialTick, Camera camera, boolean thickFog,
                                        Runnable skyFogSetup, CallbackInfo ci) {
        SkyboxModule module = SkyboxModule.getInstance();
        if (module != null && module.isEnabled()) {
            java.awt.Color c = module.auroraColor.getValue();
            if (SkyboxRenderer.render(module.mode.getValue(),
                    c.getRed() / 255.0f, c.getGreen() / 255.0f, c.getBlue() / 255.0f, frustumMatrix)) ci.cancel();
        }
    }
}
