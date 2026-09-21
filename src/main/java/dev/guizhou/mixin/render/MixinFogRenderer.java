package dev.guizhou.mixin.render;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.guizhou.features.modules.render.NoRenderModule;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21.1: FogRenderer exposes setupFog(Camera, FogMode, float, boolean, float) instead
 * of the 1.21.11 fog UBO. NoFog pushes the fog start out to effectively disable
 * shader fog after vanilla setup.
 */
@Mixin(FogRenderer.class)
public class MixinFogRenderer {

    @Inject(method = "setupFog", at = @At("TAIL"))
    private static void guizhouware$noFog(net.minecraft.client.Camera camera,
                                          FogRenderer.FogMode fogMode,
                                          float renderDistance,
                                          boolean thickFog,
                                          float partialTick,
                                          CallbackInfo ci) {
        if (NoRenderModule.isActive(m -> m.noFog.getValue())) {
            RenderSystem.setShaderFogStart(Float.MAX_VALUE);
            RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        }
    }
}
