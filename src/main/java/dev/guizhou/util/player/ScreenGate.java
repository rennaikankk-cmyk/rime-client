package dev.guizhou.util.player;

import dev.guizhou.features.gui.GuizhouWareGui;
import dev.guizhou.features.gui.HudEditorScreen;
import dev.guizhou.util.traits.Util;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

/**
 * Which open screens still allow safety upkeep (totems, surround, refills).
 *
 * <p>Chat, the ClickGUI and the HUD editor never change {@code containerMenu}, so the player's
 * inventory menu stays the one the server expects clicks and placements against. Any real
 * container, or an item held on the cursor, changes the click protocol and must stop upkeep.
 */
public final class ScreenGate implements Util {
    private ScreenGate() {}

    /** Screens that overlay the world without owning input to the player inventory. */
    public static boolean passiveOverlay() {
        return mc.screen instanceof ChatScreen
                || mc.screen instanceof GuizhouWareGui
                || mc.screen instanceof HudEditorScreen;
    }

    private static boolean playerMenuIdle() {
        return mc.player != null && mc.player.containerMenu == mc.player.inventoryMenu
                && mc.player.inventoryMenu.getCarried().isEmpty();
    }

    /** World actions (placing, attacking) while no screen or only a passive overlay is open. */
    public static boolean allowsWorldUpkeep() {
        return playerMenuIdle() && (mc.screen == null || passiveOverlay());
    }

    /**
     * Inventory-menu clicks. {@code allowInventoryScreen} lets offhand refills run while the
     * player's own inventory is open, which vanilla also permits.
     */
    public static boolean allowsInventoryUpkeep(boolean allowInventoryScreen) {
        if (!playerMenuIdle()) return false;
        return mc.screen == null || passiveOverlay()
                || allowInventoryScreen && mc.screen instanceof InventoryScreen;
    }
}
