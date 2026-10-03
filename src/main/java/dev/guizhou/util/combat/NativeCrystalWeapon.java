package dev.guizhou.util.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.combat.GuangxiCrystalModule;
import dev.guizhou.features.modules.combat.GuangxiTPModule;
import dev.guizhou.manager.HeartbeatManager;
import dev.guizhou.util.inventory.*;
import dev.guizhou.util.traits.Util;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import java.util.EnumSet;
import java.util.function.BooleanSupplier;

/** 1.21.1 refreshes held attack attributes during ticks, not while handling slot packets. */
public final class NativeCrystalWeapon implements Util {
    private static final NativeCrystalWeapon INSTANCE = new NativeCrystalWeapon();
    private Object player, level;
    private int startedTick, originalSlot, inventorySlot = -1, heldSlot;
    private long startedAt;
    private ItemStack original = ItemStack.EMPTY, weapon = ItemStack.EMPTY;
    private boolean pending;
    private NativeCrystalWeapon() { EVENT_BUS.register(this, "NativeCrystalWeapon"); }
    public static boolean pending() { return INSTANCE.pending; }
    public static boolean ownsHand() { return INSTANCE.pending && GuizhouWare.swapManager.isLatched(INSTANCE); }
    public static boolean capable(ItemStack stack) {
        var damage = new net.minecraft.world.entity.ai.attributes.AttributeInstance(Attributes.ATTACK_DAMAGE, ignored -> {});
        damage.replaceFrom(mc.player.getAttribute(Attributes.ATTACK_DAMAGE));
        // Native 1.21.1 does not reliably synchronize the attack-attribute effect modifiers.
        // Reconcile the known effect metadata by id; never double-apply an existing modifier.
        for (var effect : mc.player.getActiveEffects()) effect.getEffect().value().createModifiers(effect.getAmplifier(), (attribute, modifier) -> {
            if (attribute.equals(Attributes.ATTACK_DAMAGE)) damage.addOrUpdateTransientModifier(modifier);
        });
        return CrystalWeaponDamage.canDamage(damage, mc.player.getMainHandItem(), stack);
    }
    public static boolean run(EnumSet<ResultType> scope, BooleanSupplier action) {
        if (!HeartbeatManager.authorized() || mc.player == null || mc.level == null || !mc.isSameThread()) return false;
        return INSTANCE.execute(scope, action);
    }
    private boolean execute(EnumSet<ResultType> scope, BooleanSupplier action) {
        if (pending) {
            if (player != mc.player || level != mc.level || !GuizhouWare.swapManager.isLatched(this)
                    || InventoryUtil.selected() != heldSlot || !capable(mc.player.getMainHandItem())) { cleanup(); return false; }
            if (mc.player.tickCount - startedTick < 2 || System.currentTimeMillis() - startedAt < 150
                    || mc.player.getAttributeValue(Attributes.ATTACK_DAMAGE) <= 0) return false;
            try { return action.getAsBoolean(); } finally { cleanup(); }
        }
        if (capable(mc.player.getMainHandItem()) && mc.player.getAttributeValue(Attributes.ATTACK_DAMAGE) > 0)
            return action.getAsBoolean();
        if (scope.isEmpty() || mc.player.isUsingItem() || mc.player.containerMenu != mc.player.inventoryMenu
                || GuizhouWare.swapManager.isLatched()) return false;
        Result found = InventoryUtil.find(NativeCrystalWeapon::capable, scope);
        if (!found.found()) return false;
        player = mc.player; level = mc.level; originalSlot = InventoryUtil.selected();
        original = mc.player.getMainHandItem().copy(); weapon = found.stack().copy(); inventorySlot = -1;
        if (found.type() == ResultType.INVENTORY) {
            inventorySlot = found.slot();
            InventoryUtil.swapToHotbarSlot(inventorySlot, originalSlot);
            found = new Result(originalSlot, mc.player.getMainHandItem(), ResultType.HOTBAR);
        }
        heldSlot = found.slot(); pending = true; startedTick = mc.player.tickCount; startedAt = System.currentTimeMillis();
        if (!GuizhouWare.swapManager.latch(this, found)) cleanup();
        return false;
    }
    private void cleanup() {
        if (player == mc.player && level == mc.level && mc.player != null) {
            // Do not undo a manual slot change or another inventory owner's edits.
            if (InventoryUtil.selected() == heldSlot) GuizhouWare.swapManager.release(this);
            else GuizhouWare.swapManager.abandon(this);
            if (inventorySlot >= 0 && mc.player.containerMenu == mc.player.inventoryMenu
                    && ItemStack.isSameItemSameComponents(mc.player.getInventory().getItem(originalSlot), weapon)
                    && ItemStack.isSameItemSameComponents(mc.player.getInventory().getItem(inventorySlot), original))
                InventoryUtil.swapToHotbarSlot(inventorySlot, originalSlot);
        } else GuizhouWare.swapManager.abandon(this);
        pending = false; inventorySlot = -1; player = level = null;
    }
    @Subscribe private void tick(PreTickEvent event) {
        if (!pending) return;
        var crystal = GuizhouWare.moduleManager.getModuleByClass(GuangxiCrystalModule.class);
        var tp = GuizhouWare.moduleManager.getModuleByClass(GuangxiTPModule.class);
        if (player != mc.player || level != mc.level || !HeartbeatManager.authorized()
                || mc.player.isUsingItem() || System.currentTimeMillis() - startedAt > 2000
                || !GuizhouWare.swapManager.isLatched(this)
                || !(crystal.isEnabled() || tp.isEnabled())) cleanup();
    }
}
