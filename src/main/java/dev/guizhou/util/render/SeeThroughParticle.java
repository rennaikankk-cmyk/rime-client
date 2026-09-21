package dev.guizhou.util.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;

/**
 * 1.21.1 adapter for the 1.21.9 SingleQuadParticle.Layer with a no-depth pipeline.
 * 1.21.1 ParticleRenderType has only begin(Tesselator, TextureManager) returning the
 * builder; the engine renders the built mesh itself, so disabling the depth test in
 * begin is all that is needed for through-wall particles.
 */
public final class SeeThroughParticle {
    private static boolean changedDepth;
    private SeeThroughParticle() {}

    public static void finishPass() {
        if (changedDepth) {
            RenderSystem.enableDepthTest();
            changedDepth = false;
        }
    }

    public static final ParticleRenderType SEE_THROUGH_PARTICLE_SHEET = new ParticleRenderType() {
        @Override
        public BufferBuilder begin(Tesselator tesselator, TextureManager textureManager) {
            RenderSystem.setShader(GameRenderer::getParticleShader);
            RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_PARTICLES);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableDepthTest();
            changedDepth = true;
            RenderSystem.depthMask(false);
            return tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        }

        @Override
        public String toString() {
            return "guizhouware_see_through_particle_sheet";
        }
    };
}
