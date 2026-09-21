package dev.guizhou.mixin.render;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Field access to the 1.21.1 composite state so SeeThroughRender can clone an
 * existing entity render type's full state (textures, shaders, transparency) and
 * only override the depth/write behaviour.
 */
@Mixin(RenderType.CompositeState.class)
public interface CompositeStateAccessor {

    @Accessor("textureState")
    RenderStateShard.EmptyTextureStateShard guizhouware$getTextureState();

    @Accessor("shaderState")
    RenderStateShard.ShaderStateShard guizhouware$getShaderState();

    @Accessor("transparencyState")
    RenderStateShard.TransparencyStateShard guizhouware$getTransparencyState();

    @Accessor("depthTestState")
    RenderStateShard.DepthTestStateShard guizhouware$getDepthTestState();

    @Accessor("cullState")
    RenderStateShard.CullStateShard guizhouware$getCullState();

    @Accessor("lightmapState")
    RenderStateShard.LightmapStateShard guizhouware$getLightmapState();

    @Accessor("overlayState")
    RenderStateShard.OverlayStateShard guizhouware$getOverlayState();

    @Accessor("layeringState")
    RenderStateShard.LayeringStateShard guizhouware$getLayeringState();

    @Accessor("outputState")
    RenderStateShard.OutputStateShard guizhouware$getOutputState();

    @Accessor("texturingState")
    RenderStateShard.TexturingStateShard guizhouware$getTexturingState();

    @Accessor("writeMaskState")
    RenderStateShard.WriteMaskStateShard guizhouware$getWriteMaskState();

    @Accessor("lineState")
    RenderStateShard.LineStateShard guizhouware$getLineState();

    @Accessor("colorLogicState")
    RenderStateShard.ColorLogicStateShard guizhouware$getColorLogicState();

    @Accessor("outlineProperty")
    RenderType.OutlineProperty guizhouware$getOutlineProperty();
}
