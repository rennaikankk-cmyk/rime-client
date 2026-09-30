package dev.guizhou.features.modules.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.combat.WeaponSelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;

/**
 * Keeps the best melee weapon in hand outside the aura's own attack windows.
 * Weapon ranking comes from the shared {@link WeaponSelector} chain (attack
 * damage + sharpness + mace density), so every attack path in the client
 * agrees on "best weapon" instead of each module hardcoding its own pick.
 */
public class AutoWeaponModule extends Module {

    private final Setting<Double> range = num("Range", 4.5, 1.0, 6.0);
    private final Setting<Integer> cooldown = num("CooldownMs", 150, 0, 1000);
    private final Setting<Boolean> crosshairFirst = bool("CrosshairFirst", true);
    private final Setting<Boolean> idleRestore = bool("IdleRestore", false);

    private long lastSwap;

    public AutoWeaponModule() {
        super("AutoWeapon", "Holds the best melee weapon whenever a living target is in reach, using the "
                + "shared WeaponSelector scoring (damage + sharpness + mace density). Complements AutoSword "
                + "without touching its attack logic.",
                Category.COMBAT);
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
        if (nullCheck() || mc.player == null) return;

        // Never fight the aura for the hotbar while it owns a swap.
        AutoSwordModule sword = GuizhouWare.moduleManager.getModuleByClass(AutoSwordModule.class);
        if (sword != null && sword.isEnabled()) return;
        // (crystal weapon-busy check is private; AutoSword guard above covers the aura path,
        //  and the crystal module swaps through its own latches.)

        long now = System.currentTimeMillis();
        if (now - lastSwap < cooldown.getValue()) return;

        LivingEntity target = pickTarget();
        if (target == null) return;

        Inventory inv = mc.player.getInventory();
        int best = WeaponSelector.bestHotbarSlot(inv, target);
        if (best != inv.getSelectedSlot()) {
            inv.setSelectedSlot(best);
            lastSwap = now;
        }
    }

    private LivingEntity pickTarget() {
        if (crosshairFirst.getValue() && mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit
                && hit.getEntity() instanceof LivingEntity living
                && living.distanceTo(mc.player) <= range.getValue()) {
            return living;
        }
        LivingEntity nearest = null;
        double nearestDist = range.getValue();
        for (var e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity living) || e == mc.player) continue;
            if (living.isDeadOrDying() || !living.isAttackable()) continue;
            double d = living.distanceTo(mc.player);
            if (d <= nearestDist) {
                nearest = living;
                nearestDist = d;
            }
        }
        return nearest;
    }
}
