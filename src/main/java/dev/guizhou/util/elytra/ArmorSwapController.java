package dev.guizhou.util.elytra;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.entity.player.TickEvent;
import dev.guizhou.event.impl.entity.player.UpdateWalkingPlayerEvent;
import dev.guizhou.event.Stage;
import dev.guizhou.event.impl.network.DisconnectEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.combat.GuangxiElytraChaseModule;
import dev.guizhou.features.modules.movement.GuangxiElytraModule;
import dev.guizhou.util.inventory.InventoryUtil;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.equipment.Equippable;

import static dev.guizhou.util.traits.Util.EVENT_BUS;
import static dev.guizhou.util.traits.Util.mc;

/** Main-thread adapter; recovery stays registered when the flight module is disabled. */
public final class ArmorSwapController {
    private static final int CHEST_MENU_SLOT = 6;
    private final GuangxiElytraModule flight;
    private final ArmorSwapCycle cycle = new ArmorSwapCycle();
    private LocalPlayer player;
    private ClientLevel level;
    private int armorSlot = -1;
    private ItemStack wings = ItemStack.EMPTY;
    private ItemStack armor = ItemStack.EMPTY;
    private boolean registered;

    public ArmorSwapController(GuangxiElytraModule flight) {
        this.flight = flight;
    }

    public void monitor() {
        if (!registered) {
        EVENT_BUS.register(this, "ArmorSwapController");
            registered = true;
        }
    }

    public boolean active() {
        return cycle.active();
    }

    public boolean rocketWindowOpen() {
        return cycle.rocketWindowOpen();
    }

    public void stop() {
        if (foreignArmor() || GuangxiElytraChaseModule.isMaceDiveActive()) cycle.abandon();
        else cycle.stop(safeInventory());
        finishMonitoring();
    }

    @Subscribe(priority = 150)
    private void onPreTick(PreTickEvent event) {
        if (!sameSession()) {
            cycle.abandon();
            player = mc.player;
            level = mc.level;
            armorSlot = -1;
            wings = ItemStack.EMPTY;
            armor = ItemStack.EMPTY;
        }
        if (foreignArmor() || GuangxiElytraChaseModule.isMaceDiveActive()) {
            cycle.abandon();
            finishMonitoring();
            return;
        }
        boolean preserve = preservesOwnedMaceChestplate();
        boolean requested = flight.wantsArmorSwap() || preserve;
        if (!cycle.active() && requested && canGlide() && safeInventory() && player.isFallFlying()) {
            acquire();
        }
        cycle.beforeMovement(requested, safeInventory(), canGlide(), flight.holdChestplateForBoost(),
                dev.guizhou.features.modules.movement.ArmorFlyModule.serverPaced(), preserve);
        finishMonitoring();
    }

    @Subscribe(priority = -200)
    private void onTick(TickEvent event) {
        // Chase runs after preparation and can take ownership of the same pair for a mace dive.
        if (foreignArmor() || GuangxiElytraChaseModule.isMaceDiveActive()) cycle.abandon();
        else cycle.afterMovement(flight.wantsArmorSwap() || preservesOwnedMaceChestplate(),
                safeInventory(), canGlide(), flight.rocketSentThisTick());
        finishMonitoring();
    }

    /** Close the normal wing window immediately after the movement packet. The
     * tail listener remains responsible for recovery and windows without a send.
     * An unsafe early opportunity must not turn a deferred return into recovery. */
    @Subscribe(priority = Integer.MAX_VALUE)
    private void onPostMovement(UpdateWalkingPlayerEvent event) {
        if (foreignArmor()) { cycle.abandon(); return; }
        if (event.getStage() != Stage.POST || !cycle.active()
                || GuangxiElytraChaseModule.isMaceDiveActive()
                || !flight.wantsArmorSwap() || !safeInventory() || !canGlide()) return;
        cycle.afterMovement(true, true, true, flight.rocketSentThisTick());
        finishMonitoring();
    }

    @Subscribe
    private void onDisconnect(DisconnectEvent event) {
        LocalPlayer departedPlayer = player;
        ClientLevel departedLevel = level;
        mc.execute(() -> {
            if (player != departedPlayer || level != departedLevel) return;
            cycle.abandon();
            player = null;
            level = null;
            armorSlot = -1;
            wings = ItemStack.EMPTY;
            armor = ItemStack.EMPTY;
            finishMonitoring();
        });
    }

    private void acquire() {
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (!usableWings(chest)) return;
        int best = -1;
        double bestScore = -1;
        for (int slot = 0; slot < 36; slot++) {
            if (slot == InventoryUtil.selected()) continue;
            ItemStack candidate = player.getInventory().getItem(slot);
            if (!chestArmor(candidate)) continue;
            if (candidate.isDamageableItem() && candidate.getMaxDamage() - candidate.getDamageValue()
                    < dev.guizhou.features.modules.movement.ArmorFlyModule.minimumDurability()) continue;
            double score = candidate.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
                    net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY)
                    .compute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR, 0, EquipmentSlot.CHEST);
            if (score <= bestScore) continue;
            best = slot; bestScore = score;
        }
        if (best != -1) {
            armorSlot = best;
            wings = chest.copy();
            armor = player.getInventory().getItem(best).copy();
            cycle.begin(new ArmorSwapCycle.Access() {
                @Override public boolean intact() { return ownsPair(); }
                @Override public boolean wearingWings() {
                    return sameEquipment(wings, player.getItemBySlot(EquipmentSlot.CHEST));
                }
                @Override public boolean swap() { return swapPair(); }
                @Override public boolean gliding() { return sameSession() && player.isFallFlying(); }
                @Override public boolean startGliding() {
                    if (!canGlide() || !safeInventory() || !usableWings(player.getItemBySlot(EquipmentSlot.CHEST))
                            || !player.tryToStartFallFlying()) return false;
                    mc.getConnection().send(new ServerboundPlayerCommandPacket(
                            player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                    return true;
                }
                @Override public void restored() { flight.finishArmorSwapRecovery(); }
            });
            return;
        }
    }

    private boolean ownsPair() {
        if (!sameSession() || armorSlot < 0 || armorSlot >= 36) return false;
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        ItemStack stored = player.getInventory().getItem(armorSlot);
        return sameEquipment(wings, chest) && sameEquipment(armor, stored)
                || sameEquipment(armor, chest) && sameEquipment(wings, stored);
    }

    private boolean swapPair() {
        if (!safeInventory() || !ownsPair()) return false;
        ItemStack stored = player.getInventory().getItem(armorSlot);
        var chestSlot = player.inventoryMenu.getSlot(CHEST_MENU_SLOT);
        if (!chestSlot.mayPickup(player) || !chestSlot.mayPlace(stored)) return false;
        ItemStack expected = stored.copy();
        if (armorSlot < 9) InventoryUtil.click(CHEST_MENU_SLOT, armorSlot, ClickType.SWAP);
        else {
            InventoryUtil.click(armorSlot, 0, ClickType.PICKUP);
            if (!sameEquipment(expected, player.containerMenu.getCarried())) return false;
            InventoryUtil.click(CHEST_MENU_SLOT, 0, ClickType.PICKUP);
            InventoryUtil.click(armorSlot, 0, ClickType.PICKUP);
        }
        return ownsPair() && sameEquipment(expected, player.getItemBySlot(EquipmentSlot.CHEST));
    }

    private boolean safeInventory() {
        return !foreignArmor() && mc.isSameThread() && sameSession() && mc.gameMode != null && mc.getConnection() != null
                && mc.screen == null && !player.isDeadOrDying() && !player.isSpectator()
                && canSwapWhileUsing() && (!cycle.active() || armorSlot != InventoryUtil.selected())
                && player.containerMenu == player.inventoryMenu && player.containerMenu.getCarried().isEmpty()
                && (GuizhouWare.swapManager == null || !GuizhouWare.swapManager.isLatched());
    }

    private boolean canSwapWhileUsing() {
        boolean using = player.isUsingItem();
        ItemStack used = player.getUseItem();
        return preservesHeldUse(using, used.has(DataComponents.KINETIC_WEAPON)
                        || GuangxiElytraChaseModule.isEatingRetreatRequested()
                        && dev.guizhou.util.player.EatUtil.isGoldenApple(used), used,
                using ? player.getItemInHand(player.getUsedItemHand()) : ItemStack.EMPTY);
    }

    static boolean preservesHeldUse(boolean using, boolean allowedUse, Object used, Object held) {
        // This controller swaps only the chest and a non-selected hotbar slot. Neither hand
        // changes. Spear charging can continue; a retreat meal permits owned wings recovery.
        return !using || allowedUse && used != null && used == held;
    }

    private boolean canGlide() {
        return sameSession() && !player.onGround() && !player.isInWater() && !player.isInLava()
                && !player.isPassenger() && !player.getAbilities().flying && !player.isDeadOrDying()
                && !player.isSpectator() && !player.hasEffect(MobEffects.LEVITATION)
                && (!cycle.active() || usableWings(wingsInPair()));
    }

    private ItemStack wingsInPair() {
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        return sameEquipment(wings, chest) ? chest : player.getInventory().getItem(armorSlot);
    }

    private boolean sameSession() {
        return player != null && level != null && player == mc.player && level == mc.level;
    }

    private boolean preservesOwnedMaceChestplate() {
        return cycle.active() && flight.armorFlyRequested()
                && GuangxiElytraChaseModule.preservesMaceDescent();
    }

    private boolean wearableChest(ItemStack stack) {
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        return !stack.isEmpty() && stack.getCount() == 1 && equippable != null
                && equippable.slot() == EquipmentSlot.CHEST && equippable.canBeEquippedBy(player.getType())
                && !EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
    }

    private boolean usableWings(ItemStack stack) {
        return wearableChest(stack) && stack.has(DataComponents.GLIDER)
                && (!stack.isDamageableItem() || stack.getMaxDamage() - stack.getDamageValue()
                    >= dev.guizhou.features.modules.movement.ArmorFlyModule.minimumDurability());
    }

    private boolean chestArmor(ItemStack stack) {
        return wearableChest(stack) && !stack.has(DataComponents.GLIDER)
                && stack.has(DataComponents.ATTRIBUTE_MODIFIERS);
    }

    private static boolean sameEquipment(ItemStack expected, ItemStack actual) {
        if (expected.isEmpty() || actual.isEmpty()) return false;
        ItemStack first = expected.copy();
        ItemStack second = actual.copy();
        first.remove(DataComponents.DAMAGE);
        second.remove(DataComponents.DAMAGE);
        return ItemStack.matches(first, second);
    }

    private void finishMonitoring() {
        if (!cycle.active() && !flight.isEnabled() && !flight.armorFlyRequested() && registered) {
            EVENT_BUS.unregister(this);
            registered = false;
        }
    }

    private static boolean foreignArmor() {
        return dev.guizhou.util.compat.ClientCoexistence.blocks(dev.guizhou.util.compat.ForeignClientProbe.Domain.ARMOR);
    }
}
