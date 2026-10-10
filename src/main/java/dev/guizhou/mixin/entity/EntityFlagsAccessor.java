package dev.guizhou.mixin.entity;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Entity.class)
public interface EntityFlagsAccessor {
    @Accessor("DATA_SHARED_FLAGS_ID")
    static EntityDataAccessor<Byte> rime$sharedFlags() { throw new AssertionError(); }
}
