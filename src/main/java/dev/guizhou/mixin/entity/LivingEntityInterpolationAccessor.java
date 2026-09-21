package dev.guizhou.mixin.entity;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Native 1.21.1 stores the received destination directly on LivingEntity. */
@Mixin(LivingEntity.class)
public interface LivingEntityInterpolationAccessor {
    @Accessor("lerpSteps") int guizhouware$lerpSteps();
    @Accessor("lerpX") double guizhouware$lerpX();
    @Accessor("lerpY") double guizhouware$lerpY();
    @Accessor("lerpZ") double guizhouware$lerpZ();
}
