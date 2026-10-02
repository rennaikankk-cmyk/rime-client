package dev.guizhou.features.modules.player;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.entity.player.TickEvent;
import dev.guizhou.event.impl.network.DisconnectEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.combat.AutoTotemModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.HeartbeatManager;
import dev.guizhou.util.PacketSendUtil;
import dev.guizhou.util.compat.ClientCoexistence;
import dev.guizhou.util.compat.ForeignClientProbe;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.inventory.ItemUseTracker;
import dev.guizhou.util.inventory.ReplenishPlanning;
import dev.guizhou.util.lang.ClientLanguage;
import dev.guizhou.util.player.ScreenGate;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hotbar, offhand and stack upkeep, extended with SlimefunHelper InvHelper behaviour: a bounded
 * move budget per round, offhand top-up, partial-stack merging and a restock of the slot the
 * player just used an item from.
 *
 * <p>Coordination: never touches a hotbar slot SwapManager has latched (an AutoEat meal or a held
 * tool), the hand in active use, or the offhand while AutoTotem manages it; yields the whole pass
 * to a foreign client's inventory modules. Every move finishes with an empty cursor. The upstream
 * auto-drop list is deliberately not ported.
 */
public class ReplenishModule extends Module {

    /** Player inventory index of the offhand; container slot 45 in the inventory menu. */
    private static final int OFFHAND_INDEX = 40;
    private static final int OFFHAND_CONTAINER_SLOT = 45;
    private static final int STABILITY_TICKS = 10;
    private static final int PENDING_USE_TIMEOUT = 10;

    private final Setting<Integer> percentage = num("Percentage", 20, 10, 50);
    private final Setting<Boolean> alternative = bool("Alternative", false);
    private final Setting<Boolean> refillEmpty = bool("RefillEmpty", true);

    private final Setting<Integer> checkDelay = num("CheckDelay", 1, 1, 20);
    private final Setting<Integer> maxMoves = num("MaxMoves", 1, 1, 6);
    private final Setting<Boolean> useHotbarSource = bool("UseHotbarSource", false);
    private final Setting<Boolean> refillOffhand = bool("RefillOffhand", false);
    private final Setting<Boolean> refillOnUse = bool("RefillOnUse", false);
    private final Setting<Integer> onUsePercentage = num("OnUsePercentage", 12, 1, 50);
    private final Setting<Boolean> mergeStacks = bool("MergeStacks", false);
    private final Setting<Integer> mergePercentage = num("MergePercentage", 12, 1, 50);

    private final Map<Integer, Integer> hotbarTicks = new HashMap<>();
    private final Map<Integer, Item> lastHotbarItems = new HashMap<>();
    /** Last stack each slot held, so a slot that was used up can be refilled with the same item. */
    private final Map<Integer, ItemStack> templates = new HashMap<>();

    /** A slot the player just used an item from, awaiting one restock attempt. */
    private record PendingUse(int inventoryIndex, ItemStack used, int tick) {}
    private final Deque<PendingUse> pendingUses = new ArrayDeque<>();

    /** Hand state at tick start; a use that does not match it is a module swap, not player input. */
    private ItemStack preTickMain = ItemStack.EMPTY;
    private ItemStack preTickOffhand = ItemStack.EMPTY;
    private int preTickSelected = -1;

    private int roundTimer;

    public ReplenishModule() {
        super("Replenish", "Automatically refills items in the hotbar, including slots that were used up. "
                + "Can also restock the slot right after you use from it, top up the offhand, and merge "
                + "partial stacks, inside one bounded move budget.", Category.PLAYER);
        PacketSendUtil.addSubmissionListener(this::onUseSubmitted);
    }

    @Override
    public void onDisable() {
        resetState();
    }

    /** Runs before SwapManager (priority 2000) so later swaps are visible against this snapshot. */
    @Subscribe(priority = 3000)
    private void onPreTick(PreTickEvent event) {
        if (nullCheck()) {
            preTickSelected = -1;
            return;
        }
        preTickMain = mc.player.getMainHandItem().copy();
        preTickOffhand = mc.player.getOffhandItem().copy();
        preTickSelected = InventoryUtil.selected();
    }

    /** A player-initiated item use is a refill trigger for the slot it consumed from. */
    private void onUseSubmitted(Packet<?> packet) {
        // The packet listener bypasses the event bus, so disabled state is checked here, first.
        if (!isEnabled() || !refillOnUse.getValue()) return;
        if (mc.player == null || !mc.isSameThread()) return;
        InteractionHand hand;
        if (packet instanceof ServerboundUseItemPacket use) hand = use.getHand();
        else if (packet instanceof ServerboundUseItemOnPacket use) hand = use.getHand();
        else return;
        // Module actions manage their own items; restocking under them races their swaps.
        if (ItemUseTracker.moduleUseInFlight()) return;
        ItemStack used = mc.player.getItemInHand(hand);
        if (used.isEmpty()) return;
        // Ghost-hand guard: a use is the player's own only if the hand still holds what the tick started with.
        if (!ItemStack.isSameItemSameComponents(used, hand == InteractionHand.MAIN_HAND ? preTickMain : preTickOffhand)) return;
        int index = hand == InteractionHand.MAIN_HAND ? InventoryUtil.selected() : OFFHAND_INDEX;
        if (hand == InteractionHand.MAIN_HAND && preTickSelected != index) return;
        pendingUses.removeIf(pending -> pending.inventoryIndex() == index);
        pendingUses.addLast(new PendingUse(index, used.copy(), mc.player.tickCount));
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (nullCheck()) return;
        if (!HeartbeatManager.authorized()) {
            HeartbeatManager.notifyDeniedOnce();
            pendingUses.clear();
            return;
        }
        if (!ScreenGate.allowsInventoryUpkeep(false)) return;
        if (ClientCoexistence.blocks(ForeignClientProbe.Domain.INVENTORY)) return;
        if (mc.player.isDeadOrDying()) return;

        int budget = maxMoves.getValue();
        budget = runPendingUses(budget);

        trackHotbarStability();
        if (++roundTimer < Math.max(1, checkDelay.getValue())) return;
        roundTimer = 0;

        budget = refillHotbar(budget);
        budget = topUpOffhand(budget);
        mergePass(budget);
    }

    @Subscribe
    private void onDisconnect(DisconnectEvent event) {
        resetState();
    }

    private void resetState() {
        hotbarTicks.clear();
        lastHotbarItems.clear();
        templates.clear();
        pendingUses.clear();
        roundTimer = 0;
    }

    /** One-shot restock of the slot the player just used, one tick after the use packet. */
    private int runPendingUses(int budget) {
        if (pendingUses.isEmpty() || budget <= 0) return budget;
        int now = mc.player.tickCount;
        // Same-tick entries wait one tick for the client to apply the use; stale ones are dropped.
        pendingUses.removeIf(pending -> now == pending.tick() || now - pending.tick() >= PENDING_USE_TIMEOUT);
        List<ItemStack> slots = inventorySnapshot();
        while (budget > 0 && !pendingUses.isEmpty()) {
            PendingUse pending = pendingUses.removeFirst();
            int index = pending.inventoryIndex();
            if (index == OFFHAND_INDEX && offhandBusy()) continue;
            if (index < 9 && isProtectedHotbarSlot(index)) continue;
            ItemStack current = stackAt(index);
            if (!ReplenishPlanning.pendingUseEligible(current, pending.used(), refillEmpty.getValue(),
                    ReplenishPlanning.threshold(current.getMaxStackSize(), onUsePercentage.getValue()))) continue;
            int source = ReplenishPlanning.findSource(slots, index, current.isEmpty() ? pending.used() : current,
                    false, this::isProtectedHotbarSlot);
            if (source == -1) continue;
            moveTo(index, source);
            budget--;
        }
        return budget;
    }

    /** Template and stability counters advance every tick so the wait does not stretch with CheckDelay. */
    private void trackHotbarStability() {
        for (int hotbarSlot = 0; hotbarSlot < 9; hotbarSlot++) {
            ItemStack stack = mc.player.getInventory().getItem(hotbarSlot);
            Item currentItem = stack.isEmpty() ? null : stack.getItem();
            if (!stack.isEmpty()) templates.put(hotbarSlot, stack.copyWithCount(1));
            if (lastHotbarItems.getOrDefault(hotbarSlot, null) != currentItem) {
                hotbarTicks.put(hotbarSlot, 0);
                lastHotbarItems.put(hotbarSlot, currentItem);
            } else {
                hotbarTicks.merge(hotbarSlot, 1, Integer::sum);
            }
        }
    }

    private int refillHotbar(int budget) {
        List<ItemStack> slots = inventorySnapshot();
        for (int hotbarSlot = 0; hotbarSlot < 9 && budget > 0; hotbarSlot++) {
            if (isProtectedHotbarSlot(hotbarSlot)) continue;
            ItemStack stack = mc.player.getInventory().getItem(hotbarSlot);
            if (hotbarTicks.getOrDefault(hotbarSlot, 0) < STABILITY_TICKS) continue;
            ItemStack target = stack;
            if (stack.isEmpty()) {
                if (!refillEmpty.getValue()) continue;
                target = templates.get(hotbarSlot);
                if (target == null) continue;
            } else {
                if (stack.getCount() >= ReplenishPlanning.threshold(stack.getMaxStackSize(), percentage.getValue())) continue;
            }
            int invSlot = ReplenishPlanning.findSource(slots, hotbarSlot, target,
                    useHotbarSource.getValue(), this::isProtectedHotbarSlot);
            if (invSlot != -1) {
                moveTo(hotbarSlot, invSlot);
                budget--;
                continue;
            }
            // Nothing left to refill with: forget the template so the slot is free for new items.
            if (stack.isEmpty()) templates.remove(hotbarSlot);
        }
        return budget;
    }

    /** The offhand stays with AutoTotem whenever it is enabled; it owns that slot. */
    private boolean offhandBusy() {
        if (mc.player.isUsingItem() && mc.player.getUsedItemHand() == InteractionHand.OFF_HAND) return true;
        if (ClientCoexistence.blocks(ForeignClientProbe.Domain.OFFHAND)) return true;
        Module totem = GuizhouWare.moduleManager == null ? null
                : GuizhouWare.moduleManager.getModuleByClass(AutoTotemModule.class);
        return totem != null && totem.isEnabled();
    }

    private int topUpOffhand(int budget) {
        if (budget <= 0 || !refillOffhand.getValue() || offhandBusy()) return budget;
        ItemStack offhand = mc.player.getOffhandItem();
        if (offhand.isEmpty() || offhand.getMaxStackSize() <= 1) return budget;
        if (offhand.getCount() >= ReplenishPlanning.threshold(offhand.getMaxStackSize(), percentage.getValue())) return budget;
        int source = ReplenishPlanning.findSource(inventorySnapshot(), OFFHAND_INDEX,
                offhand, false, this::isProtectedHotbarSlot);
        if (source == -1) return budget;
        moveTo(OFFHAND_INDEX, source);
        return budget - 1;
    }

    /** Merges a stack below MergePercentage into the largest same-item partial stack. */
    private int mergePass(int budget) {
        if (!mergeStacks.getValue()) return budget;
        List<ItemStack> slots = inventorySnapshot();
        for (int i = 0; i < 36 && budget > 0; i++) {
            if (isProtectedHotbarSlot(i)) continue;
            ItemStack small = slots.get(i);
            if (small.isEmpty() || small.getMaxStackSize() <= 1) continue;
            if (small.getCount() >= ReplenishPlanning.threshold(small.getMaxStackSize(), mergePercentage.getValue())) continue;
            int partner = ReplenishPlanning.mergePartner(slots, i, this::isProtectedHotbarSlot);
            if (partner == -1) continue;
            mergeInto(partner, i);
            budget--;
        }
        return budget;
    }

    /** Slots another module or the player is actively using must not be rearranged under them. */
    private boolean isProtectedHotbarSlot(int hotbarSlot) {
        if (hotbarSlot > 8) return false;
        if (GuizhouWare.swapManager != null && GuizhouWare.swapManager.latchedSlot() == hotbarSlot) return true;
        return mc.player.isUsingItem() && mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND
                && InventoryUtil.selected() == hotbarSlot;
    }

    /**
     * The one move primitive every refill goes through, behind one authorization boundary.
     * A full source stack is a single swap packet; a partial one is merged with pickup clicks
     * that always end with an empty cursor.
     */
    private void moveTo(int targetIndex, int sourceIndex) {
        if (!HeartbeatManager.authorized()) return;
        if (!InventoryUtil.cursor().isEmpty()) return;
        ItemStack source = mc.player.getInventory().getItem(sourceIndex);
        ItemStack target = stackAt(targetIndex);
        if (source.isEmpty()) return;
        if (!target.isEmpty() && !ItemStack.isSameItemSameComponents(target, source)) return; // stale plan

        boolean offhand = targetIndex == OFFHAND_INDEX;
        switch (ReplenishPlanning.moveKind(source, target, !offhand && alternative.getValue() && sourceIndex >= 9)) {
            case SWAP -> {
                if (offhand) InventoryUtil.swapToOffhand(sourceIndex);
                else InventoryUtil.click(containerSlotOf(sourceIndex), targetIndex, ClickType.SWAP);
            }
            case QUICK_MOVE -> InventoryUtil.click(containerSlotOf(sourceIndex), 0, ClickType.QUICK_MOVE);
            case PICKUP_MERGE -> {
                int sourceSlot = containerSlotOf(sourceIndex);
                int targetSlot = offhand ? OFFHAND_CONTAINER_SLOT : containerSlotOf(targetIndex);
                InventoryUtil.click(sourceSlot, 0, ClickType.PICKUP);
                InventoryUtil.click(targetSlot, 0, ClickType.PICKUP);
                if (!InventoryUtil.cursor().isEmpty()) InventoryUtil.click(sourceSlot, 0, ClickType.PICKUP);
            }
        }
    }

    /** Merges {@code smallIndex} into {@code largeIndex}, returning any remainder to its slot. */
    private void mergeInto(int largeIndex, int smallIndex) {
        if (!HeartbeatManager.authorized()) return;
        if (!InventoryUtil.cursor().isEmpty()) return;
        int largeSlot = containerSlotOf(largeIndex);
        int smallSlot = containerSlotOf(smallIndex);
        InventoryUtil.click(smallSlot, 0, ClickType.PICKUP);
        InventoryUtil.click(largeSlot, 0, ClickType.PICKUP);
        if (!InventoryUtil.cursor().isEmpty()) InventoryUtil.click(smallSlot, 0, ClickType.PICKUP);
    }

    /** Read-only snapshot of all 41 inventory slots (index 40 = offhand) for the pure planner. */
    private List<ItemStack> inventorySnapshot() {
        List<ItemStack> slots = new ArrayList<>(ReplenishPlanning.INVENTORY_SIZE);
        for (int i = 0; i < ReplenishPlanning.INVENTORY_SIZE; i++) slots.add(mc.player.getInventory().getItem(i));
        return slots;
    }

    private static ItemStack stackAt(int inventoryIndex) {
        return inventoryIndex == OFFHAND_INDEX ? mc.player.getOffhandItem()
                : mc.player.getInventory().getItem(inventoryIndex);
    }

    /** Inventory index to slot index in the player's own inventory menu (hotbar 36-44, offhand 45). */
    private static int containerSlotOf(int inventoryIndex) {
        if (inventoryIndex == OFFHAND_INDEX) return OFFHAND_CONTAINER_SLOT;
        return inventoryIndex < 9 ? inventoryIndex + 36 : inventoryIndex;
    }

    @Override
    public String getDisplayInfo() {
        String foreign = ClientCoexistence.reason(ForeignClientProbe.Domain.INVENTORY);
        if (foreign != null) return ClientLanguage.text("让行：") + foreign;
        return null;
    }
}
