package dev.guizhou.util.inventory;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure counting half of kit resupply, ported from SlimefunHelper KitReplenish: how much of a
 * saved kit the current inventory is missing, and how much of that missing part one shulker box
 * covers. Works over slot snapshots so the rules stay testable without a world.
 */
public final class KitDemand {

    private KitDemand() {}

    public static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /** Totals per item id over a slot snapshot (player indices, 40 = offhand). */
    public static Map<String, Integer> count(List<ItemStack> slots) {
        Map<String, Integer> totals = new HashMap<>();
        for (ItemStack stack : slots) {
            if (stack.isEmpty()) continue;
            totals.merge(id(stack.getItem()), stack.getCount(), Integer::sum);
        }
        return totals;
    }

    /** Kit target minus what is held; only positive gaps remain. */
    public static Map<String, Integer> shortage(Map<String, Integer> kit, Map<String, Integer> have) {
        Map<String, Integer> missing = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : kit.entrySet()) {
            int gap = entry.getValue() - have.getOrDefault(entry.getKey(), 0);
            if (gap > 0) missing.put(entry.getKey(), gap);
        }
        return missing;
    }

    /** Any shulker box item is a candidate supply box; its contents decide whether it is worth opening. */
    public static boolean isSupplyShulker(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem block)) return false;
        return block.getBlock() instanceof ShulkerBoxBlock;
    }

    /** How much of the shortage one box covers, counting only items actually missing. */
    public static int boxValue(ItemStack box, Map<String, Integer> shortage) {
        if (!isSupplyShulker(box)) return 0;
        ItemContainerContents contents = box.get(DataComponents.CONTAINER);
        if (contents == null) return 0;
        int value = 0;
        for (ItemStack item : contents.nonEmptyItems()) {
            Integer missing = shortage.get(id(item.getItem()));
            if (missing != null) value += Math.min(missing, item.getCount());
        }
        return value;
    }
}
