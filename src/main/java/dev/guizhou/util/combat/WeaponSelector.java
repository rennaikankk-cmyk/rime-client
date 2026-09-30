package dev.guizhou.util.combat;

import dev.guizhou.util.EnchantmentUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * Shared melee weapon scoring, lifted out of the aura internals so every
 * attack path (aura, manual hits, future modules) ranks weapons the same way.
 * Attribute iteration mirrors AutoSword's proven damage estimate; the mace
 * density term reuses {@link MaceDamageScore}.
 */
public final class WeaponSelector {
    private WeaponSelector() {}

    /** Melee damage estimate of {@code stack} against {@code target} (null = generic). */
    public static double score(ItemStack stack, LivingEntity target) {
        if (stack == null || stack.isEmpty()) return -1.0;

        double base = 1.0; // fist
        ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (modifiers != null) {
            for (var entry : modifiers.modifiers()) {
                if (entry.attribute().is(Attributes.ATTACK_DAMAGE)) {
                    base += entry.modifier().amount();
                }
            }
        }

        double enchant = EnchantmentUtil.getLevel(Enchantments.SHARPNESS, stack) * 1.25
                + EnchantmentUtil.getLevel(Enchantments.SMITE, stack) * 2.5;

        double mace = 0.0;
        if (stack.getItem() instanceof net.minecraft.world.item.MaceItem) {
            int density = EnchantmentUtil.getLevel(Enchantments.DENSITY, stack);
            double fall = target != null ? Math.max(0.0, target.fallDistance) : 0.0;
            mace = MaceDamageScore.damage(0.0, 0.0,
                    fall > 1.5 ? 4.0 * fall : 0.0, fall, density);
        }

        return base + enchant + mace;
    }

    /** Highest-scoring hotbar slot for {@code target}; current slot if nothing beats it. */
    public static int bestHotbarSlot(Inventory inv, LivingEntity target) {
        int current = inv.getSelectedSlot();
        int best = current;
        double bestScore = score(inv.getItem(current), target);
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (i == current) continue;
            double s = score(inv.getItem(i), target);
            if (s > bestScore + 0.01) {
                best = i;
                bestScore = s;
            }
        }
        return best;
    }
}
