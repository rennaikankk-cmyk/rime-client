package dev.guizhou.util.elytra;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.movement.GuangxiElytraModule;
import dev.guizhou.util.inventory.InventoryUtil;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.equipment.Equippable;

import static dev.guizhou.util.traits.Util.EVENT_BUS;
import static dev.guizhou.util.traits.Util.mc;

/** Exchanges only an owned chest/hotbar pair; vanilla alone supplies fall distance. */
public final class MaceDiveController {
    private static final int CHEST_MENU_SLOT = 6;
    private final MaceDiveState state = new MaceDiveState();
    private LocalPlayer player;
    private ClientLevel level;
    private ItemStack wings = ItemStack.EMPTY;
    private ItemStack armor = ItemStack.EMPTY;
    private int armorSlot = -1;
    private int selectedSlot = -1;
    private long lastRecoveryAttempt = Long.MIN_VALUE;
    private boolean registered;

    public boolean begin() {
        if (!mc.isSameThread()) return false;
        if (!sameSession()) forget();
        if (isActive()) return false;
        player = mc.player;
        level = mc.level;
        if (!safeInventory() || player.isUsingItem() || !player.isFallFlying() || player.onGround()
                || player.isInWater() || player.isInLava() || player.isPassenger()
                || player.getAbilities().flying || player.getDeltaMovement().y >= -0.05
                || !(player.getMainHandItem().getItem() instanceof MaceItem)) return false;
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (!usableWings(chest) || !player.inventoryMenu.getSlot(CHEST_MENU_SLOT).mayPickup(player)) return false;
        int candidate = -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (chestArmor(stack) && player.inventoryMenu.getSlot(CHEST_MENU_SLOT).mayPlace(stack)) {
                candidate = slot;
                break;
            }
        }
        if (candidate < 0) return false;
        armorSlot = candidate;
        selectedSlot = InventoryUtil.selected();
        wings = chest.copy();
        armor = player.getInventory().getItem(armorSlot).copy();
        state.begin(player.tickCount);
        if (!registered) {
            EVENT_BUS.register(this);
            registered = true;
        }
        InventoryUtil.click(CHEST_MENU_SLOT, armorSlot, ClickType.SWAP);
        if (!ownsPair()) {
            forget();
            return false;
        }
        return true;
    }

    public boolean isActive() {
        return sameSession() && state.phase() != MaceDiveState.Phase.IDLE;
    }

    public MaceDiveState.Phase phase() {
        return isActive() ? state.phase() : MaceDiveState.Phase.IDLE;
    }

    public boolean canAttack() {
        return isActive() && ownsPair() && safeInventory() && !player.isUsingItem()
                && InventoryUtil.selected() == selectedSlot
                && !player.onGround() && !player.isInWater() && !player.isInLava() && !player.isPassenger()
                && player.getMainHandItem().getItem() instanceof MaceItem
                && state.canAttack(player.isFallFlying(), player.getDeltaMovement().y < -0.05,
                MaceItem.canSmashAttack(player));
    }

    /** Recovery remains subscribed after the owning combat module is disabled. */
    public void requestRecovery() {
        if (!mc.isSameThread()) return;
        state.requestRecovery();
        service();
    }

    @Subscribe(priority = 200)
    private void onPreTick(PreTickEvent event) {
        service();
    }

    private void service() {
        if (state.phase() == MaceDiveState.Phase.IDLE) return;
        if (!sameSession() || player.isDeadOrDying() || player.isSpectator()) {
            forget();
            return;
        }
        if (!ownsPair()) {
            // A manual equipment or inventory change ends ownership of both slots.
            forget();
            return;
        }
        boolean mustRecover = !safeInventory() || player.isUsingItem() || InventoryUtil.selected() != selectedSlot
                || !(player.getMainHandItem().getItem() instanceof MaceItem)
                || player.onGround() || player.isInWater() || player.isInLava() || player.isPassenger()
                || player.getAbilities().flying;
        state.observe(player.tickCount, player.isFallFlying(), mustRecover);
        if (state.phase() != MaceDiveState.Phase.RECOVERING || !safeInventory()
                || !recoverySlotAvailable(InventoryUtil.selected(), armorSlot)) return;
        if (lastRecoveryAttempt == player.tickCount) return;
        lastRecoveryAttempt = player.tickCount;
        if (!player.inventoryMenu.getSlot(CHEST_MENU_SLOT).mayPickup(player)
                || !player.inventoryMenu.getSlot(CHEST_MENU_SLOT).mayPlace(player.getInventory().getItem(armorSlot))) return;
        InventoryUtil.click(CHEST_MENU_SLOT, armorSlot, ClickType.SWAP);
        if (sameEquipment(wings, player.getItemBySlot(EquipmentSlot.CHEST))
                && sameEquipment(armor, player.getInventory().getItem(armorSlot))) {
            forget();
            if (GuizhouWare.moduleManager != null) {
                var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
                if (flight != null) flight.finishDiveRecovery();
            }
        }
    }

    private boolean ownsPair() {
        return sameSession() && armorSlot >= 0
                && sameEquipment(armor, player.getItemBySlot(EquipmentSlot.CHEST))
                && sameEquipment(wings, player.getInventory().getItem(armorSlot));
    }

    private boolean safeInventory() {
        return sameSession() && mc.gameMode != null && mc.getConnection() != null && mc.screen == null
                && !player.isDeadOrDying() && !player.isSpectator()
                && player.containerMenu == player.inventoryMenu && player.containerMenu.getCarried().isEmpty()
                && (GuizhouWare.swapManager == null || !GuizhouWare.swapManager.isLatched());
    }

    private boolean sameSession() {
        return player != null && level != null && player == mc.player && level == mc.level;
    }

    static boolean recoverySlotAvailable(int selected, int wingsSlot) {
        // Equipment recovery may run during food use, but must never replace the held stack.
        return selected >= 0 && selected < 9 && wingsSlot >= 0 && wingsSlot < 9 && selected != wingsSlot;
    }

    private boolean chestArmor(ItemStack stack) {
        return wearableChest(stack) && !stack.has(DataComponents.GLIDER)
                && !EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
    }

    private boolean wearableChest(ItemStack stack) {
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        return !stack.isEmpty() && stack.getCount() == 1 && equippable != null
                && equippable.slot() == EquipmentSlot.CHEST && equippable.canBeEquippedBy(player.getType());
    }

    private boolean usableWings(ItemStack stack) {
        return wearableChest(stack) && stack.has(DataComponents.GLIDER)
                && (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage() - 1);
    }

    static boolean sameEquipment(ItemStack expected, ItemStack actual) {
        if (expected.isEmpty() || actual.isEmpty()) return false;
        ItemStack left = expected.copy();
        ItemStack right = actual.copy();
        left.remove(DataComponents.DAMAGE);
        right.remove(DataComponents.DAMAGE);
        return ItemStack.matches(left, right);
    }

    private void forget() {
        state.reset();
        player = null;
        level = null;
        wings = ItemStack.EMPTY;
        armor = ItemStack.EMPTY;
        armorSlot = -1;
        selectedSlot = -1;
        lastRecoveryAttempt = Long.MIN_VALUE;
    }
}
