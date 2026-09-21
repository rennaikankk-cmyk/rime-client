package dev.guizhou.util.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.guizhou.features.modules.render.SkyboxModule;
import net.minecraft.client.renderer.ShaderInstance;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

/**
 * 1.21.1 skybox: one ShaderInstance per mode (position+colour full-screen quad,
 * no depth test). The GLSL effect math is shared with 1.21.11; the shader JSONs
 * declare the standard core uniforms plus GameTime, and the program is bound
 * manually before drawing.
 */
public final class SkyboxRenderer {

    private static final Map<SkyboxModule.Mode, ShaderInstance> SHADERS = new EnumMap<>(SkyboxModule.Mode.class);

    private SkyboxRenderer() {}

    public static void register(net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback.RegistrationContext context) throws IOException {
        SHADERS.clear();
        for (SkyboxModule.Mode mode : SkyboxModule.Mode.values()) {
            context.register(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    "guizhouware", "skybox_" + mode.name().toLowerCase(java.util.Locale.ROOT)),
                    DefaultVertexFormat.POSITION_COLOR, shader -> SHADERS.put(mode, shader));
        }
    }

    public static boolean render(SkyboxModule.Mode mode, float r, float g, float b, org.joml.Matrix4f view) {
        ShaderInstance shader = SHADERS.get(mode);
        if (shader == null) return false;

        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity().mul(view);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.depthMask(false);
        try {
        RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_ALWAYS);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(() -> shader);

        BufferBuilder buffer = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        buffer.addVertex(-1.0f, -1.0f, 0.0f).setColor(r, g, b, 1.0f);
        buffer.addVertex(1.0f, -1.0f, 0.0f).setColor(r, g, b, 1.0f);
        buffer.addVertex(1.0f, 1.0f, 0.0f).setColor(r, g, b, 1.0f);
        buffer.addVertex(-1.0f, 1.0f, 0.0f).setColor(r, g, b, 1.0f);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
        } finally {
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        modelView.popMatrix();
        RenderSystem.applyModelViewMatrix();
        }
        return true;
    }
}
