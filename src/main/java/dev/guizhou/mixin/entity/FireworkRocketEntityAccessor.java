package dev.guizhou.mixin.entity;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.OptionalInt;

@Mixin(FireworkRocketEntity.class)
public interface FireworkRocketEntityAccessor {

    @Accessor("life")
    int guizhouware$getLife();

    @Accessor("lifetime")
    int guizhouware$getLifetime();

    @Accessor("attachedToEntity")
    LivingEntity guizhouware$getAttachedToEntity();

    @Accessor("DATA_ATTACHED_TO_TARGET")
    static EntityDataAccessor<OptionalInt> guizhouware$getAttachedTargetData() {
        throw new AssertionError();
    }

    @Accessor("DATA_ID_FIREWORKS_ITEM")
    static EntityDataAccessor<ItemStack> guizhouware$getFireworksItemData() {
        throw new AssertionError();
    }
}
