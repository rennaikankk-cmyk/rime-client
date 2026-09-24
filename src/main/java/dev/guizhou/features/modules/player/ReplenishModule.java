package dev.guizhou.features.modules.player;

import dev.guizhou.event.impl.entity.player.TickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.player.ScreenGate;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

public class ReplenishModule extends Module {

    private final Setting<Integer> percentage = num("Percentage", 20, 10, 50);
    private final Setting<Boolean> alternative = bool("Alternative", false);
    private final Setting<Boolean> refillEmpty = bool("RefillEmpty", true);

    private final Map<Integer, Integer> hotbarTicks = new HashMap<>();
    private final Map<Integer, Item> lastHotbarItems = new HashMap<>();
    /** Last stack each slot held, so a slot that was used up can be refilled with the same item. */
    private final Map<Integer, ItemStack> templates = new HashMap<>();

    public ReplenishModule() {
        super("Replenish", "Automatically refills items in the hotbar, including slots that were used up.", Category.PLAYER);
    }

    @Override
    public void onDisable() {
        hotbarTicks.clear();
        lastHotbarItems.clear();
        templates.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (nullCheck()) return;
        if (!ScreenGate.allowsInventoryUpkeep(false)) return;

        for (int hotbarSlot = 0; hotbarSlot < 9; hotbarSlot++) {
            ItemStack stack = mc.player.getInventory().getItem(hotbarSlot);
            Item currentItem = stack.isEmpty() ? null : stack.getItem();
            if (!stack.isEmpty()) templates.put(hotbarSlot, stack.copyWithCount(1));

            if (lastHotbarItems.getOrDefault(hotbarSlot, null) != currentItem) {
                hotbarTicks.put(hotbarSlot, 0);
                lastHotbarItems.put(hotbarSlot, currentItem);
            } else {
                hotbarTicks.put(hotbarSlot, hotbarTicks.getOrDefault(hotbarSlot, 0) + 1);
            }

            if (hotbarTicks.getOrDefault(hotbarSlot, 0) < 10) continue;

            ItemStack target = stack;
            if (stack.isEmpty()) {
                if (!refillEmpty.getValue()) continue;
                target = templates.get(hotbarSlot);
                if (target == null) continue;
            } else {
                int maxCount = stack.getMaxStackSize();
                int minCount = Math.max(1, (int) (maxCount * (percentage.getValue() / 100f)));
                if (stack.getCount() >= minCount) continue;
            }

            int invSlot = findInventorySlotToReplenish(target);
            if (invSlot != -1) {
                swap(hotbarSlot, invSlot, stack.isEmpty());
                return;
            }
            // Nothing left to refill with: forget the template so the slot is free for new items.
            if (stack.isEmpty()) templates.remove(hotbarSlot);
        }
    }

    private int findInventorySlotToReplenish(ItemStack target) {
        for (int i = 9; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;

            if (!ItemStack.isSameItemSameComponents(stack, target)) continue;
            return i;
        }
        return -1;
    }

    private void swap(int hotbarSlot, int invSlot, boolean emptyTarget) {
        int realHotbarSlot = hotbarSlot + 36;

        if (emptyTarget) {
            // An empty hotbar slot takes the whole stack with one number-key swap.
            InventoryUtil.click(invSlot, hotbarSlot, ClickType.SWAP);
        } else if (alternative.getValue()) {
            InventoryUtil.click(invSlot, 0, ClickType.QUICK_MOVE);
        } else {
            InventoryUtil.click(invSlot, 0, ClickType.PICKUP);
            InventoryUtil.click(realHotbarSlot, 0, ClickType.PICKUP);
            InventoryUtil.click(invSlot, 0, ClickType.PICKUP);
        }
    }
}
