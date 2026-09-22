package dev.guizhou.util.elytra;

import dev.guizhou.GuizhouWare;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.PacketSendUtil;
import dev.guizhou.manager.HeartbeatManager;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;

import java.util.function.IntPredicate;

import static dev.guizhou.util.traits.Util.mc;

/** Owns only the visible selection and spear use initiated by elytra pursuit. */
public final class ElytraWeaponController {
    private final SlotOwnership slots = new SlotOwnership();
    private LocalPlayer player;
    private ClientLevel level;
    private ItemStack selectedStack;
    private ItemStack useStack;
    private boolean ownsUse;
    private int lastUseAttemptTick = Integer.MIN_VALUE;
    private int lastSlotAttemptTick = Integer.MIN_VALUE;

    public boolean update(boolean spear, boolean autoSwitch, boolean autoUse) {
        if (!HeartbeatManager.authorized()) { release(); return false; }
        if (player != mc.player || level != mc.level) {
            forget();
            player = mc.player;
            level = mc.level;
        }
        if (blocked()) {
            release();
            return false;
        }

        int selected = InventoryUtil.selected();
        if (!autoSwitch) slots.followManual(selected);
        if (!slots.observe(selected)) {
            stopOwnedUse();
            return false;
        }
        if (ownsUse && !usingOwnedStack()) forgetUse();

        // Pressing use transfers the existing charge to the player.
        if (ownsUse && mc.options.keyUse.isDown()) forgetUse();
        if (ownsUse && (!spear || !autoUse || SpearCombat.hasDamageWindowExpired(player))) {
            stopOwnedUse();
            if (spear && autoUse) return selectionSubmitted() && matches(player.getMainHandItem(), true);
        }

        boolean desiredHeld = matches(player.getMainHandItem(), spear);
        if (player.isUsingItem() && !ownsUse) return desiredHeld && selectionSubmitted();
        if (!desiredHeld) {
            if (!autoSwitch || mc.options.keyUse.isDown()) return false;
            int target = chooseHotbar(selected, slot -> matches(player.getInventory().getItem(slot), spear));
            if (target < 0) return false;
            stopOwnedUse();
            lastSlotAttemptTick = player.tickCount;
            InventoryUtil.swap(target);
            slots.select(selected, target);
            selectedStack = player.getMainHandItem();
        } else if (slots.controlledSlot < 0) {
            slots.select(selected, selected);
            selectedStack = player.getMainHandItem();
        }

        if (!selectionSubmitted()) {
            if (lastSlotAttemptTick == player.tickCount) return false;
            lastSlotAttemptTick = player.tickCount;
            PacketSendUtil.send(new ServerboundSetCarriedItemPacket(InventoryUtil.selected()));
            if (!selectionSubmitted()) return false;
        }

        if (spear && autoUse && !player.isUsingItem() && !mc.options.keyUse.isDown()
                && lastUseAttemptTick != player.tickCount) {
            lastUseAttemptTick = player.tickCount;
            ItemStack held = player.getMainHandItem();
            boolean[] accepted = {false};
            Consumer<Packet<?>> observer = packet -> {
                if (packet instanceof ServerboundUseItemPacket use && use.getHand() == InteractionHand.MAIN_HAND
                        && selectionSubmitted()) accepted[0] = true;
            };
            PacketSendUtil.addSubmissionListener(observer);
            try { mc.gameMode.useItem(player, InteractionHand.MAIN_HAND); }
            finally { PacketSendUtil.removeSubmissionListener(observer); }
            if (!accepted[0]) {
                if (player.isUsingItem() && player.getUseItem() == held) player.stopUsingItem();
                return false;
            }
            if (player.isUsingItem() && player.getUsedItemHand() == InteractionHand.MAIN_HAND
                    && player.getUseItem() == held && SpearCombat.isSpear(held)) {
                useStack = held;
                ownsUse = true;
            }
        }
        return matches(player.getMainHandItem(), spear);
    }

    private boolean selectionSubmitted() {
        return sameSession() && GuizhouWare.swapManager.serverSlot() == InventoryUtil.selected();
    }

    public boolean isHoldingUse() {
        return !blocked() && selectionSubmitted() && ownsUse && usingOwnedStack()
                && slots.controlledSlot == InventoryUtil.selected();
    }

    public void release() {
        if (sameSession()) {
            stopOwnedUse();
            boolean safeToRestore = mc.gameMode != null && mc.getConnection() != null
                    && mc.screen == null && player.containerMenu == player.inventoryMenu
                    && player.containerMenu.getCarried().isEmpty() && !player.isUsingItem()
                    && (GuizhouWare.swapManager == null || !GuizhouWare.swapManager.isLatched())
                    && player.getMainHandItem() == selectedStack;
            int restore = slots.release(InventoryUtil.selected(), safeToRestore);
            if (restore >= 0) InventoryUtil.swap(restore);
        }
        forget();
    }

    private boolean blocked() {
        return !sameSession() || mc.gameMode == null || mc.getConnection() == null
                || player.isDeadOrDying() || player.isSpectator() || mc.screen != null
                || player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()
                || (GuizhouWare.swapManager != null && GuizhouWare.swapManager.isLatched());
    }

    private boolean sameSession() {
        return player != null && level != null && player == mc.player && level == mc.level;
    }

    private boolean usingOwnedStack() {
        return sameSession() && player.isUsingItem()
                && player.getUsedItemHand() == InteractionHand.MAIN_HAND
                && player.getUseItem() == useStack && player.getMainHandItem() == useStack
                && SpearCombat.isSpear(useStack);
    }

    private void stopOwnedUse() {
        boolean releaseUse = ownsUse && usingOwnedStack();
        forgetUse();
        if (releaseUse && mc.gameMode != null && mc.getConnection() != null) {
            lastUseAttemptTick = player.tickCount;
            mc.gameMode.releaseUsingItem(player);
        }
    }

    private void forgetUse() {
        ownsUse = false;
        useStack = null;
    }

    private void forget() {
        forgetUse();
        slots.reset();
        player = null;
        level = null;
        selectedStack = null;
        lastUseAttemptTick = Integer.MIN_VALUE;
        lastSlotAttemptTick = Integer.MIN_VALUE;
    }

    private static boolean matches(ItemStack stack, boolean spear) {
        return spear ? SpearCombat.isSpear(stack) : dev.guizhou.util.elytra.MappedCombatItems.isMace(stack);
    }

    static int chooseHotbar(int selected, IntPredicate matches) {
        if (selected >= 0 && selected < 9 && matches.test(selected)) return selected;
        for (int slot = 0; slot < 9; slot++) {
            if (matches.test(slot)) return slot;
        }
        return -1;
    }

    static final class SlotOwnership {
        private int originalSlot = -1;
        private int controlledSlot = -1;
        private boolean manualOverride;

        boolean observe(int selected) {
            if (controlledSlot >= 0 && selected != controlledSlot) {
                originalSlot = -1;
                controlledSlot = -1;
                manualOverride = true;
            }
            return !manualOverride;
        }

        void select(int from, int to) {
            if (originalSlot < 0 && from != to) originalSlot = from;
            controlledSlot = to;
        }

        void followManual(int selected) {
            // Manual weapon mode owns item use, never the player's slot choice.
            originalSlot = -1;
            controlledSlot = selected;
            manualOverride = false;
        }

        int release(int selected, boolean safeToRestore) {
            int restore = !manualOverride && selected == controlledSlot && safeToRestore ? originalSlot : -1;
            reset();
            return restore;
        }

        void reset() {
            originalSlot = -1;
            controlledSlot = -1;
            manualOverride = false;
        }
    }
}
