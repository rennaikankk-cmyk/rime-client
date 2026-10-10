package dev.guizhou.util.elytra;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Recognizes native maces and the explicit ViaBackwards pre-component fallback. */
public final class MaceCombat {
    private MaceCombat() {}

    public static boolean isMace(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.is(Items.MACE)) return true;
        return isMace(stack, DualViewSupport.status() == DualViewSupport.Status.SUPPORTED);
    }

    static boolean isMace(ItemStack stack, boolean legacyVia) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.is(Items.MACE)) return true;
        if (!legacyVia || !stack.is(Items.NETHERITE_AXE)) return false;
        var data = stack.get(DataComponents.CUSTOM_DATA);
        // ViaBackwards stores the original 1.20.5 item ID before mapping to an axe.
        // Never infer a weapon from its editable display name or from an ordinary axe.
        return data != null && data.copyTag().getInt("VB|Protocol1_20_5To1_20_3|id").orElse(-1) == 1093;
    }
}
