package dev.guizhou.util.combat;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Estimates main-hand damage without changing the player's live attributes. */
public final class CrystalWeaponDamage {
    private CrystalWeaponDamage() {}

    public static boolean canDamage(AttributeInstance current, ItemStack held, ItemStack candidate) {
        if (current == null) return false;
        AttributeInstance damage = new AttributeInstance(Attributes.ATTACK_DAMAGE, ignored -> {});
        damage.replaceFrom(current);
        held.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.equals(Attributes.ATTACK_DAMAGE)) damage.removeModifier(modifier.id());
        });
        // Silent swaps can precede the client's equipment-attribute refresh by a tick.
        damage.removeModifier(Item.BASE_ATTACK_DAMAGE_ID);
        candidate.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.equals(Attributes.ATTACK_DAMAGE)) damage.addOrUpdateTransientModifier(modifier);
        });
        double value = damage.getValue();
        return Double.isFinite(value) && value > 0.0;
    }
}
