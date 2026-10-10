package dev.guizhou.features.modules.qinghua;

import dev.guizhou.event.impl.entity.player.TickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Port of eternal AntiMiss — totem hotbar force-select logic unchanged.
 */
public class AntiMissModule extends Module {
    public enum Mode { Silent, Normal }

    private final Setting<Mode> mode = mode("Mode", Mode.Normal)
            .described("Silent: send packet to switch. Normal: real switch.");
    private int prevSlot = -1;

    public AntiMissModule() {
        super("AntiMiss", "AntiMiss.", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        this.prevSlot = -1;
    }

    @Override
    public void onDisable() {
        if (mc.player != null) {
            if (this.mode.getValue() == Mode.Silent && this.prevSlot != -1 && mc.getConnection() != null) {
                mc.getConnection().send(new ServerboundSetCarriedItemPacket(this.prevSlot));
            }
            this.prevSlot = -1;
        }
    }

    @Override
    public String getDisplayInfo() {
        return this.mode.getValue().name();
    }

    @Subscribe
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (mc.screen instanceof CreativeModeInventoryScreen) return;
        if (!(mc.player.containerMenu instanceof InventoryMenu)) return;

        int totemSlot = this.findTotemInInventory();
        int emptyHotbarSlot = this.findEmptyHotbarSlot();
        if (totemSlot != -1 && emptyHotbarSlot != -1) {
            this.moveItem(totemSlot, emptyHotbarSlot);
        }

        int hotbarTotemSlot = this.findTotemInHotbar();
        if (hotbarTotemSlot != -1) {
            if (this.mode.getValue() == Mode.Silent) {
                if (mc.player.getInventory().getSelectedSlot() != hotbarTotemSlot && mc.getConnection() != null) {
                    if (this.prevSlot == -1) this.prevSlot = mc.player.getInventory().getSelectedSlot();
                    mc.getConnection().send(new ServerboundSetCarriedItemPacket(hotbarTotemSlot));
                }
            } else {
                mc.player.getInventory().setSelectedSlot(hotbarTotemSlot);
            }
        }
    }

    private int findTotemInInventory() {
        Inventory inv = mc.player.getInventory();
        for (int i = 9; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
    }

    private int findEmptyHotbarSlot() {
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).isEmpty()) return i;
        }
        return -1;
    }

    private int findTotemInHotbar() {
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
    }

    private void moveItem(int fromSlot, int toSlot) {
        if (mc.gameMode == null || mc.player == null) return;
        int syncId = mc.player.containerMenu.containerId;
        mc.gameMode.handleInventoryMouseClick(syncId, fromSlot, toSlot, ClickType.SWAP, mc.player);
    }
}
