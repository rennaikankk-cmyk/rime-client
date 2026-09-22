package dev.guizhou.util.elytra;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.IntTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/** Read-only identity of a mace translated through Via 1.20.4. */
public final class MappedCombatItems {
    public static final String MACE_ID = "VB|Protocol1_20_5To1_20_3|id";
    private MappedCombatItems() { }
    public static boolean isMace(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.is(Items.MACE)) return true;
        if (!stack.is(Items.NETHERITE_AXE)) return false;
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null) return false;
        var root = custom.copyTag();
        return root.get(MACE_ID) instanceof IntTag && root.getInt(MACE_ID) == 1093;
    }
}
