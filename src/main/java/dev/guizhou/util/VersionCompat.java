package dev.guizhou.util;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * 1.21.1 adapters for item/equipment queries that 1.21.5+ answers with data components.
 * 1.21.1 has no EQUIPPABLE or GLIDER component: equippability comes from the item type
 * (ArmorItem/ElytraItem both implement {@link Equipable}) and the glider is the elytra item.
 */
public final class VersionCompat {
    private VersionCompat() {}

    /** 1.21.5: EQUIPPABLE.slot() == CHEST && EQUIPPABLE.canBeEquippedBy(type). */
    public static boolean wearableChest(LivingEntity wearer, ItemStack stack) {
        return !stack.isEmpty() && stack.getCount() == 1
                && stack.getItem() instanceof Equipable equipable
                && equipable.getEquipmentSlot() == EquipmentSlot.CHEST;
    }

    /** 1.21.5: DataComponents.GLIDER present. On 1.21.1 the only glider is the elytra item. */
    public static boolean isGlider(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof ElytraItem;
    }

    /** 1.21.5: EQUIPPABLE + PREVENT_ARMOR_CHANGE, collapsed for the swap controllers. */
    public static boolean swappableChest(LivingEntity wearer, ItemStack stack) {
        return wearableChest(wearer, stack)
                && !EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
    }
}
