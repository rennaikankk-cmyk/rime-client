package dev.guizhou.mixin.render;

import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 1.21.1: exposes a pass's effect so uniforms can be set programmatically. */
@Mixin(PostPass.class)
public interface PostPassAccessor {
    @Accessor("effect")
    EffectInstance guizhouware$getEffect();
}
