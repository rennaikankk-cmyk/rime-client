package dev.guizhou.util.inventory;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

import static dev.guizhou.util.inventory.ReplenishPlanning.MoveKind;
import static dev.guizhou.util.inventory.ReplenishPlanning.OFFHAND_INDEX;

/** Headless checks of the Replenish planning rules ported from SlimefunHelper InvHelper. */
public final class ReplenishPlanningTest {
    private static int checks;

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        thresholds();
        findSourceRules();
        mergePartnerRules();
        pendingUseRules();
        moveKindRules();
        System.out.println("ReplenishPlanningTest passed: " + checks + " checks");
    }

    private static void thresholds() {
        check(ReplenishPlanning.threshold(64, 20) == 12, "64-stack at 20 percent");
        check(ReplenishPlanning.threshold(64, 12) == 7, "64-stack at 12 percent");
        check(ReplenishPlanning.threshold(16, 20) == 3, "16-stack at 20 percent");
        check(ReplenishPlanning.threshold(1, 50) == 1, "unstackable still needs one item");
        check(ReplenishPlanning.threshold(64, 1) == 1, "threshold never drops below one");
    }

    private static void findSourceRules() {
        List<ItemStack> slots = emptySlots();
        slots.set(0, stack(Items.OBSIDIAN, 3));
        slots.set(10, stack(Items.OBSIDIAN, 12));
        slots.set(20, stack(Items.OBSIDIAN, 64));
        check(ReplenishPlanning.findSource(slots, 0, slots.get(0), false, i -> false) == 20,
                "largest inventory stack wins");

        slots = emptySlots();
        slots.set(0, stack(Items.OBSIDIAN, 3));
        slots.set(10, stack(Items.OBSIDIAN, 64));
        check(ReplenishPlanning.findSource(slots, OFFHAND_INDEX, slots.get(0), false, i -> false) == 10,
                "offhand target sources from the inventory");

        slots = emptySlots();
        slots.set(2, stack(Items.OBSIDIAN, 5));
        check(ReplenishPlanning.findSource(slots, 0, stack(Items.OBSIDIAN, 1), false, i -> false) == -1,
                "hotbar is not a source unless requested");
        check(ReplenishPlanning.findSource(slots, 0, stack(Items.OBSIDIAN, 1), true, i -> i == 2) == -1,
                "protected hotbar slot is never a source");
        check(ReplenishPlanning.findSource(slots, 0, stack(Items.OBSIDIAN, 1), true, i -> false) == 2,
                "hotbar source allowed on request");

        slots = emptySlots();
        ItemStack plain = stack(Items.GOLDEN_APPLE, 3);
        ItemStack named = stack(Items.GOLDEN_APPLE, 3);
        named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("kit"));
        slots.set(0, plain);
        slots.set(10, named);
        check(ReplenishPlanning.findSource(slots, 0, plain, false, i -> false) == -1,
                "component mismatch is not a source");
    }

    private static void mergePartnerRules() {
        List<ItemStack> slots = emptySlots();
        slots.set(30, stack(Items.ARROW, 5));
        slots.set(12, stack(Items.ARROW, 10));
        slots.set(15, stack(Items.ARROW, 30));
        check(ReplenishPlanning.mergePartner(slots, 30, i -> false) == 15, "largest partial stack wins");

        slots.set(15, stack(Items.ARROW, 64));
        check(ReplenishPlanning.mergePartner(slots, 30, i -> false) == 12, "full stacks are not partners");

        slots = emptySlots();
        slots.set(30, new ItemStack(Items.DIAMOND_SWORD));
        check(ReplenishPlanning.mergePartner(slots, 30, i -> false) == -1, "unstackables never merge");

        slots = emptySlots();
        slots.set(30, stack(Items.ARROW, 5));
        slots.set(3, stack(Items.ARROW, 20));
        check(ReplenishPlanning.mergePartner(slots, 30, i -> i == 3) == -1, "protected hotbar is no partner");
        check(ReplenishPlanning.mergePartner(slots, 3, i -> i == 3) == -1, "protected small stack never merges");
    }

    private static void pendingUseRules() {
        ItemStack used = stack(Items.ENDER_PEARL, 16);
        check(ReplenishPlanning.pendingUseEligible(stack(Items.ENDER_PEARL, 3), used, true, 7),
                "low stack after use is eligible");
        check(!ReplenishPlanning.pendingUseEligible(stack(Items.ENDER_PEARL, 7), used, true, 7),
                "stack at the threshold waits");
        check(!ReplenishPlanning.pendingUseEligible(stack(Items.SNOWBALL, 2), used, true, 7),
                "a slot holding another item is a ghost hand");
        check(ReplenishPlanning.pendingUseEligible(ItemStack.EMPTY, used, true, 7),
                "used-up slot refills from the pending template");
        check(!ReplenishPlanning.pendingUseEligible(ItemStack.EMPTY, used, false, 7),
                "used-up slot respects empty refill disabled");
    }

    private static void moveKindRules() {
        check(ReplenishPlanning.moveKind(stack(Items.OBSIDIAN, 64), ItemStack.EMPTY, false) == MoveKind.SWAP,
                "empty target takes a swap packet");
        check(ReplenishPlanning.moveKind(stack(Items.OBSIDIAN, 64), stack(Items.OBSIDIAN, 3), false) == MoveKind.SWAP,
                "full source takes a swap packet");
        check(ReplenishPlanning.moveKind(stack(Items.OBSIDIAN, 12), stack(Items.OBSIDIAN, 3), true) == MoveKind.QUICK_MOVE,
                "partial source shift-clicks when allowed");
        check(ReplenishPlanning.moveKind(stack(Items.OBSIDIAN, 12), stack(Items.OBSIDIAN, 3), false) == MoveKind.PICKUP_MERGE,
                "partial source merges with pickup clicks otherwise");
    }

    private static List<ItemStack> emptySlots() {
        List<ItemStack> slots = new ArrayList<>();
        for (int i = 0; i < ReplenishPlanning.INVENTORY_SIZE; i++) slots.add(ItemStack.EMPTY);
        return slots;
    }

    private static ItemStack stack(net.minecraft.world.item.Item item, int count) {
        ItemStack stack = new ItemStack(item);
        stack.setCount(count);
        return stack;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
