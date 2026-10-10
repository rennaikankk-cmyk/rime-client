package dev.guizhou.util.inventory;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.IntPredicate;

/**
 * Pure planning half of the Replenish module: every decision is made over a plain slot array
 * (player inventory indices, 40 = offhand; the live inventory list is read-only here) so the move execution in the module stays the only
 * place that touches the game. Ported from SlimefunHelper InvHelper's selection rules.
 */
public final class ReplenishPlanning {
    /** Inventory index of the offhand slot. */
    public static final int OFFHAND_INDEX = 40;
    public static final int INVENTORY_SIZE = 41;

    /** How a planned refill executes; the swap is one packet, the merge ends with an empty cursor. */
    public enum MoveKind { SWAP, QUICK_MOVE, PICKUP_MERGE }

    private ReplenishPlanning() {}

    /** Count at or above which a stack is considered stocked; always at least one item. */
    public static int threshold(int maxCount, int percent) {
        return Math.max(1, maxCount * percent / 100);
    }

    /**
     * Best source stack for a refill of {@code targetIndex}: main inventory before hotbar,
     * larger stacks first (a full stack refills with a single swap packet).
     * {@code protectedHotbar} is consulted for slots 0-8.
     */
    public static int findSource(List<ItemStack> slots, int targetIndex, ItemStack target,
                                 boolean allowHotbar, IntPredicate protectedHotbar) {
        int best = -1;
        int bestCount = 0;
        for (int i = 9; i < 36; i++) {
            int quality = sourceQuality(slots, i, targetIndex, target);
            if (quality > bestCount) {
                bestCount = quality;
                best = i;
            }
        }
        if (allowHotbar) {
            for (int i = 0; i < 9; i++) {
                if (protectedHotbar.test(i)) continue;
                int quality = sourceQuality(slots, i, targetIndex, target);
                if (quality > bestCount) {
                    bestCount = quality;
                    best = i;
                }
            }
        }
        return best;
    }

    private static int sourceQuality(List<ItemStack> slots, int slot, int targetIndex, ItemStack target) {
        if (slot == targetIndex) return -1;
        ItemStack stack = slots.get(slot);
        if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, target)) return -1;
        return stack.getCount();
    }

    /**
     * Largest same-item partial stack to merge {@code smallIndex} into, or -1. Protected hotbar
     * slots are never merged through; unstackables never qualify on either side.
     */
    public static int mergePartner(List<ItemStack> slots, int smallIndex, IntPredicate protectedHotbar) {
        if (smallIndex < 9 && protectedHotbar.test(smallIndex)) return -1;
        ItemStack small = slots.get(smallIndex);
        if (small.isEmpty() || small.getMaxStackSize() <= 1) return -1;
        int partner = -1;
        int partnerCount = -1;
        for (int j = 0; j < 36; j++) {
            if (j == smallIndex || j < 9 && protectedHotbar.test(j)) continue;
            ItemStack other = slots.get(j);
            if (other.isEmpty() || other.getMaxStackSize() <= 1 || other.getCount() >= other.getMaxStackSize()
                    || !ItemStack.isSameItemSameComponents(other, small)) continue;
            if (other.getCount() > partnerCount) {
                partnerCount = other.getCount();
                partner = j;
            }
        }
        return partner;
    }

    /**
     * Whether a pending use still allows one restock of its slot: the slot must still hold the
     * used item (or be empty with empty refill allowed) and sit below the restock threshold.
     */
    public static boolean pendingUseEligible(ItemStack current, ItemStack used, boolean refillEmpty, int threshold) {
        if (current.isEmpty()) return refillEmpty;
        if (!ItemStack.isSameItemSameComponents(current, used)) return false;
        return current.getCount() < threshold;
    }

    /**
     * Execution shape of one refill. An empty target or a full source is a single swap packet;
     * shift-click only lands in the hotbar from the main inventory; everything else merges.
     */
    public static MoveKind moveKind(ItemStack source, ItemStack target, boolean quickMoveAllowed) {
        if (target.isEmpty() || source.getCount() >= source.getMaxStackSize()) return MoveKind.SWAP;
        if (quickMoveAllowed) return MoveKind.QUICK_MOVE;
        return MoveKind.PICKUP_MERGE;
    }
}
