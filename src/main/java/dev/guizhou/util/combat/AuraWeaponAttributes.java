package dev.guizhou.util.combat;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

public final class AuraWeaponAttributes {
    private AuraWeaponAttributes() {}

    /** Preserve effects/armor/modded attributes while replacing only the main-hand modifiers. */
    public static double value(Player player, ItemStack replacement, Holder<Attribute> attribute) {
        AttributeInstance actual = player.getAttribute(attribute);
        if (actual == null) return Double.NaN;
        AttributeInstance scratch = new AttributeInstance(attribute, ignored -> {});
        scratch.setBaseValue(actual.getBaseValue());
        for (var modifier : actual.getModifiers()) scratch.addOrUpdateTransientModifier(modifier);
        modifiers(player.getMainHandItem()).forEach(EquipmentSlot.MAINHAND, (type, modifier) -> {
            if (type.equals(attribute)) scratch.removeModifier(modifier.id());
        });
        modifiers(replacement).forEach(EquipmentSlot.MAINHAND, (type, modifier) -> {
            if (type.equals(attribute)) scratch.addOrUpdateTransientModifier(modifier);
        });
        return scratch.getValue();
    }

    private static ItemAttributeModifiers modifiers(ItemStack stack) {
        return stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
    }
}
