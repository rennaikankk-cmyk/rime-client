package dev.guizhou.features.modules.extra;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.settings.Setting;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

public final class ChestStealerModule extends UtilityModule {
    private final Setting<Integer> delay = num("DelayTicks", 5, 1, 40);
    private final Setting<Boolean> shulkersOnly = bool("ShulkersOnly", false);
    private final Setting<Boolean> closeEmpty = bool("CloseEmpty", false);
    private int nextTick;
    public ChestStealerModule() { super("ChestStealer", "Quick-moves storage contents with a bounded delay and an optional shulker filter.", Category.PLAYER); }
    @Override protected void resetSession() { nextTick = 0; }
    @Override protected void tick() {
        var menu = mc.player.containerMenu;
        if (!(menu instanceof net.minecraft.world.inventory.ChestMenu
                || menu instanceof net.minecraft.world.inventory.ShulkerBoxMenu
                || menu instanceof net.minecraft.world.inventory.HopperMenu
                || menu instanceof net.minecraft.world.inventory.DispenserMenu)
                || !menu.getCarried().isEmpty() || GuizhouWare.swapManager.isLatched() || mc.player.tickCount < nextTick) return;
        boolean empty = true;
        for (var slot : menu.slots) {
            if (slot.container == mc.player.getInventory() || !slot.hasItem()) continue;
            empty = false;
            if (shulkersOnly.getValue() && !(slot.getItem().getItem() instanceof net.minecraft.world.item.BlockItem item
                    && item.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock)) continue;
            boolean room = false;
            ItemStack stack = slot.getItem();
            for (int i = 0; i < 36; i++) {
                ItemStack own = mc.player.getInventory().getItem(i);
                if (own.isEmpty() || ItemStack.isSameItemSameComponents(own, stack) && own.getCount() < own.getMaxStackSize()) { room = true; break; }
            }
            if (!room) continue;
            mc.gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 0, ClickType.QUICK_MOVE, mc.player);
            nextTick = mc.player.tickCount + delay.getValue(); return;
        }
        if (empty && closeEmpty.getValue()) mc.player.closeContainer();
    }
}
