package dev.guizhou.mixin.render;

import com.mojang.blaze3d.opengl.GlBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GlBuffer.class)
public interface GLBufferAccessor
{
    @Accessor(value = "handle")
    int guizhouware$getHandle();
}
