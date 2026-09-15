package dev.guizhou.mixin.render;

import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(RenderSetup.class)
public interface RenderSetupAccessor {
    @Accessor("textures")
    Map<String, RenderSetup.TextureBinding> guizhouware$getTextures();

    @Accessor("textureTransform")
    TextureTransform guizhouware$getTextureTransform();

    @Accessor("outputTarget")
    OutputTarget guizhouware$getOutputTarget();

    @Accessor("outlineProperty")
    RenderSetup.OutlineProperty guizhouware$getOutlineProperty();

    @Accessor("useLightmap")
    boolean guizhouware$useLightmap();

    @Accessor("useOverlay")
    boolean guizhouware$useOverlay();

    @Accessor("affectsCrumbling")
    boolean guizhouware$affectsCrumbling();

    @Accessor("sortOnUpload")
    boolean guizhouware$sortOnUpload();

    @Accessor("bufferSize")
    int guizhouware$getBufferSize();

    @Accessor("layeringTransform")
    LayeringTransform guizhouware$getLayeringTransform();
}
