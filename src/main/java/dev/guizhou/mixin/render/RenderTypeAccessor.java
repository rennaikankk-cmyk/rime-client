package dev.guizhou.mixin.render;

import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 1.21.1: reads the composite state off concrete render types for state cloning. */
@Mixin(RenderType.CompositeRenderType.class)
public interface RenderTypeAccessor {
    @Accessor("state")
    RenderType.CompositeState guizhouware$getState();
}
