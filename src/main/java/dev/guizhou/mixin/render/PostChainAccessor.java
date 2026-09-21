package dev.guizhou.mixin.render;

import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** 1.21.1: exposes the pass list of a programmatic post chain. */
@Mixin(PostChain.class)
public interface PostChainAccessor {
    @Accessor("passes")
    List<PostPass> guizhouware$getPasses();
}
