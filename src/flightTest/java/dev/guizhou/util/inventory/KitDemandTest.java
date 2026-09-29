package dev.guizhou.util.inventory;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Headless checks of the kit counting rules ported from SlimefunHelper KitReplenish. */
public final class KitDemandTest {
    private static int checks;

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        counting();
        shortageRules();
        shulkerValue();
        System.out.println("KitDemandTest passed: " + checks + " checks");
    }

    private static void counting() {
        List<ItemStack> slots = emptySlots();
        slots.set(0, stack(Items.ENDER_PEARL, 8));
        slots.set(20, stack(Items.ENDER_PEARL, 8));
        slots.set(40, stack(Items.ENDER_PEARL, 4));
        slots.set(9, stack(Items.OBSIDIAN, 32));
        Map<String, Integer> totals = KitDemand.count(slots);
        check(totals.get(KitDemand.id(Items.ENDER_PEARL)) == 20, "pearls across slots and offhand sum");
        check(totals.get(KitDemand.id(Items.OBSIDIAN)) == 32, "obsidian counted");
        check(totals.size() == 2, "empty slots do not count");
        check(KitDemand.count(emptySlots()).isEmpty(), "empty inventory has no demand entries");
    }

    private static void shortageRules() {
        Map<String, Integer> kit = Map.of(
                KitDemand.id(Items.ENDER_PEARL), 16,
                KitDemand.id(Items.OBSIDIAN), 64,
                KitDemand.id(Items.GOLDEN_APPLE), 4);
        Map<String, Integer> have = Map.of(
                KitDemand.id(Items.ENDER_PEARL), 20,
                KitDemand.id(Items.OBSIDIAN), 64);
        Map<String, Integer> missing = KitDemand.shortage(kit, have);
        check(missing.size() == 1, "over-stock and satisfied items leave the shortage");
        check(missing.get(KitDemand.id(Items.GOLDEN_APPLE)) == 4, "missing gap is the difference");
        check(KitDemand.shortage(Map.of(), have).isEmpty(), "no kit means no shortage");
    }

    private static void shulkerValue() {
        check(!KitDemand.isSupplyShulker(stack(Items.OBSIDIAN, 32)), "plain stack is not a supply box");
        ItemStack emptyBox = new ItemStack(Items.SHULKER_BOX);
        check(KitDemand.isSupplyShulker(emptyBox), "a shulker box item is a candidate");
        check(KitDemand.boxValue(emptyBox, Map.of(KitDemand.id(Items.ENDER_PEARL), 5)) == 0,
                "an empty box is never worth opening");
        check(!KitDemand.isSupplyShulker(stack(Items.CHEST, 1)), "chest item is not a shulker box");

        ItemStack box = new ItemStack(Blocks.SHULKER_BOX);
        var contents = net.minecraft.world.item.component.ItemContainerContents.fromItems(
                java.util.List.of(stack(Items.ENDER_PEARL, 8), stack(Items.OBSIDIAN, 64), stack(Items.BREAD, 3)));
        box.set(net.minecraft.core.component.DataComponents.CONTAINER, contents);
        check(KitDemand.isSupplyShulker(box), "shulker with contents is a supply box");

        Map<String, Integer> shortage = Map.of(
                KitDemand.id(Items.ENDER_PEARL), 5,
                KitDemand.id(Items.OBSIDIAN), 10,
                KitDemand.id(Items.GOLDEN_APPLE), 2);
        check(KitDemand.boxValue(box, shortage) == 5 + 10, "box value counts only the missing part");
        check(KitDemand.boxValue(box, Map.of()) == 0, "no shortage means no value");
        check(KitDemand.boxValue(stack(Items.OBSIDIAN, 5), shortage) == 0, "non-box value is zero");
    }

    private static List<ItemStack> emptySlots() {
        List<ItemStack> slots = new ArrayList<>();
        for (int i = 0; i < ReplenishPlanning.INVENTORY_SIZE; i++) slots.add(ItemStack.EMPTY);
        return slots;
    }

    private static ItemStack stack(Item item, int count) {
        ItemStack stack = new ItemStack(item);
        stack.setCount(count);
        return stack;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
