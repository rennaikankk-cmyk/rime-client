package dev.guizhou.util.elytra;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.entity.player.TickEvent;
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
            EVENT_BUS.register(this);
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
        if (GuangxiElytraChaseModule.isMaceDiveActive()) cycle.abandon();
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
        if (GuangxiElytraChaseModule.isMaceDiveActive()) {
            cycle.abandon();
            finishMonitoring();
            return;
        }
        boolean requested = flight.wantsArmorSwap();
        if (!cycle.active() && requested && canGlide() && safeInventory() && player.isFallFlying()) {
            acquire();
        }
        cycle.beforeMovement(requested, safeInventory(), canGlide());
        finishMonitoring();
    }

    @Subscribe(priority = -200)
    private void onTick(TickEvent event) {
        // Chase runs after preparation and can take ownership of the same pair for a mace dive.
        if (GuangxiElytraChaseModule.isMaceDiveActive()) cycle.abandon();
        else cycle.afterMovement(flight.wantsArmorSwap(), safeInventory(), canGlide());
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
        for (int slot = 0; slot < 9; slot++) {
            if (slot == InventoryUtil.selected()) continue;
            ItemStack candidate = player.getInventory().getItem(slot);
            if (!chestArmor(candidate)) continue;
            armorSlot = slot;
            wings = chest.copy();
            armor = candidate.copy();
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
        if (!sameSession() || armorSlot < 0 || armorSlot > 8) return false;
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
        InventoryUtil.click(CHEST_MENU_SLOT, armorSlot, ClickType.SWAP);
        return ownsPair() && sameEquipment(expected, player.getItemBySlot(EquipmentSlot.CHEST));
    }

    private boolean safeInventory() {
        return mc.isSameThread() && sameSession() && mc.gameMode != null && mc.getConnection() != null
                && mc.screen == null && !player.isDeadOrDying() && !player.isSpectator()
                && !player.isUsingItem() && (!cycle.active() || armorSlot != InventoryUtil.selected())
                && player.containerMenu == player.inventoryMenu && player.containerMenu.getCarried().isEmpty()
                && (GuizhouWare.swapManager == null || !GuizhouWare.swapManager.isLatched());
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

    private boolean wearableChest(ItemStack stack) {
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        return !stack.isEmpty() && stack.getCount() == 1 && equippable != null
                && equippable.slot() == EquipmentSlot.CHEST && equippable.canBeEquippedBy(player.getType())
                && !EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
    }

    private boolean usableWings(ItemStack stack) {
        return wearableChest(stack) && stack.has(DataComponents.GLIDER)
                && (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage() - 1);
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
}
