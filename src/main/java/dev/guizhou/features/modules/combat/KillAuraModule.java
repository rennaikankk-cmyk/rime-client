package dev.guizhou.features.modules.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.client.TargetsModule;
import dev.guizhou.features.settings.Setting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.Comparator;
import java.util.function.Predicate;

/**
 * Multi-target melee attacks on top of AutoSword. AutoSword stays registered and
 * still attacks on its own when this module is off. While this module is on it is
 * the only one that swings, using AutoSword's range, weapon swap, hit check and TP path.
 */
public class KillAuraModule extends Module {
    public enum TargetMode { GLOBAL, CUSTOM }

    private final Setting<AutoSwordModule.TargetSort> sort = mode("Sort", AutoSwordModule.TargetSort.DISTANCE);
    private final Setting<Integer> targets = num("Targets", 1, 1, 3);
    private final Setting<Boolean> rotate = bool("Rotate", true);
    private final Setting<Boolean> noAc = bool("NoAC", false)
            .described("无反作弊服使用。只检查距离和遮挡，关闭 Rotate 时不等待服务端视角命中。");
    private final Setting<TargetMode> targetMode = mode("TargetMode", TargetMode.GLOBAL).setPage("Targets")
            .described("GLOBAL 使用全局 Targets；CUSTOM 使用杀戮自己的目标开关。好友和假人过滤始终生效。");
    private final Setting<Boolean> players = bool("Players", true).setPage("Targets").setVisibility(v -> custom());
    private final Setting<Boolean> armorStands = bool("ArmorStands", false).setPage("Targets").setVisibility(v -> custom());
    private final Setting<Boolean> hostiles = bool("Hostiles", false).setPage("Targets").setVisibility(v -> custom());
    private final Setting<Boolean> neutrals = bool("Neutrals", false).setPage("Targets").setVisibility(v -> custom());
    private final Setting<Boolean> passives = bool("Passives", false).setPage("Targets").setVisibility(v -> custom());
    private final Setting<Boolean> projectiles = bool("Projectiles", true).setPage("Targets")
            .described("默认拦截附近可反弹的火球、风弹和潜影贝子弹，不等待武器、设置冷却或 TPS 同步。");
    private final Setting<Integer> projectileLimit = num("ProjectileLimit", 8, 1, 32).setPage("Targets")
            .setVisibility(v -> projectiles.getValue());
    private final Predicate<Entity> targetFilter = this::validTarget;

    public KillAuraModule() {
        super("KillAura", "Melee aura with an independent target policy and immediate projectile defence.", Category.COMBAT);
    }

    private boolean custom() { return targetMode.getValue() == TargetMode.CUSTOM; }

    @Override public void onEnable() {
        AutoSwordModule sword = GuizhouWare.moduleManager.getModuleByClass(AutoSwordModule.class);
        if (sword != null) sword.setAuraActive(true);
    }

    @Override public void onDisable() {
        AutoSwordModule sword = GuizhouWare.moduleManager.getModuleByClass(AutoSwordModule.class);
        if (sword != null) sword.setAuraActive(false);
    }

    private boolean validTarget(Entity entity) {
        if (entity == mc.player || !(entity instanceof LivingEntity living) || living.isDeadOrDying()
                || entity.isRemoved() || entity.isSpectator()) return false;
        if (!custom()) {
            TargetsModule policy = GuizhouWare.moduleManager.getModuleByClass(TargetsModule.class);
            return policy != null && policy.isValidTarget(entity);
        }
        if (entity instanceof Player player) return players.getValue() && TargetsModule.isAllowedPlayer(player);
        return armorStands.getValue() && entity instanceof ArmorStand
                || hostiles.getValue() && TargetsModule.isHostile(entity)
                || neutrals.getValue() && TargetsModule.isNeutral(entity)
                || passives.getValue() && TargetsModule.isPassive(entity);
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) {
            dev.guizhou.manager.HeartbeatManager.notifyDeniedOnce();
            return;
        }
        AutoSwordModule sword = GuizhouWare.moduleManager.getModuleByClass(AutoSwordModule.class);
        if (sword == null || !sword.prepareAttackTick()) return;
        // Defence precedes the melee cooldown and uses a separate budget without swapping or teleporting.
        if (projectiles.getValue()) {
            List<Entity> incoming = mc.level.getEntities((Entity) null, mc.player.getBoundingBox().inflate(4),
                    AutoSwordModule::deflectableProjectile);
            incoming.sort(Comparator.comparingDouble(mc.player::distanceToSqr));
            int hits = 0;
            for (Entity projectile : incoming) {
                if (sword.tryDeflect(projectile, rotate.getValue(), noAc.getValue()) && ++hits >= projectileLimit.getValue()) break;
            }
        }
        if (GuangxiElytraChaseModule.isWeaponControlActive() || sword.coolingDown()) return;
        List<Entity> found = sword.selectTargets(sort.getValue(), targets.getValue(), targetFilter);
        for (Entity target : found) {
            if (sword.coolingDown()) break;
            sword.tryAttack(target, rotate.getValue(), noAc.getValue());
        }
    }
}
