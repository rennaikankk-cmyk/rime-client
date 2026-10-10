package dev.guizhou.features.modules.qinghua;

import dev.guizhou.event.impl.entity.player.TickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.elytra.SpearCombat;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Port of epsilon SpearKill — lunge math unchanged.
 * TargetManager → local player/mob scan; RotationUtils → setYRot/setXRot.
 */
public class SpearKillModule extends Module {
    public static SpearKillModule INSTANCE;

    public enum LungeMode { DirectionBased, Above }

    private final Setting<LungeMode> lungeMode = mode("LungeMode", LungeMode.DirectionBased);
    private final Setting<Double> maxRange = num("MaxRange", 6.0, 1.0, 16.0);
    private final Setting<Double> lungeStrength = num("LungeStrength", 3.7, 0.5, 15.0);
    private final Setting<Double> aboveHeight = num("AboveHeight", 10.0, 3.0, 30.0)
            .setVisibility(v -> lungeMode.getValue() == LungeMode.Above);
    private final Setting<Double> aboveHeightDistance = num("AboveHeightDistance", 3.0, 1.0, 10.0)
            .setVisibility(v -> lungeMode.getValue() == LungeMode.Above);
    private final Setting<Double> stopDistance = num("StopDistance", 0.6, 0.0, 10.0);
    private final Setting<Integer> chargeTimeModifier = num("ChargeTime", 100, 0, 100);
    private final Setting<Boolean> stopOnTarget = bool("StopOnTarget", true);
    private final Setting<Boolean> autoSwitch = bool("AutoSwitch", true);
    private final Setting<Boolean> rotate = bool("Rotate", true);
    private final Setting<Boolean> players = bool("Players", true);
    private final Setting<Boolean> mobs = bool("Mobs", false);
    private final Setting<Boolean> animals = bool("Animals", false);
    private final Setting<Boolean> villagers = bool("Villagers", false);
    private final Setting<Boolean> invisible = bool("Invisible", true);

    public LivingEntity killtarget;
    private boolean currentlyCharging;
    private Vec3 aboveTargetPos;
    private boolean firstPhase;

    public SpearKillModule() {
        super("SpearKill", "Spear Kill", Category.COMBAT);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        killtarget = null;
        currentlyCharging = false;
        aboveTargetPos = null;
        firstPhase = false;
    }

    @Override
    public void onDisable() {
        killtarget = null;
        currentlyCharging = false;
        aboveTargetPos = null;
        firstPhase = false;
    }

    @Subscribe
    public void onTick(TickEvent e) {
        updateTarget();
        currentlyCharging = isUsingSpear();

        if (currentlyCharging) {
            if (killtarget == null) updateTarget();
            if (killtarget != null && !killtarget.isAlive()) {
                if (stopOnTarget.getValue()) {
                    mc.player.setDeltaMovement(Vec3.ZERO);
                    mc.player.setSprinting(false);
                }
                killtarget = null;
                firstPhase = false;
                aboveTargetPos = null;
            }
            if (killtarget != null) lunge();
        } else {
            killtarget = null;
            firstPhase = false;
            aboveTargetPos = null;
        }
    }

    private void updateTarget() {
        if (killtarget != null && !killtarget.isAlive()) killtarget = null;

        if (killtarget == null || autoSwitch.getValue()) {
            double rangeValue = maxRange.getValue();
            LivingEntity best = null;
            double bestDist = Double.MAX_VALUE;
            if (mc.level == null || mc.player == null) {
                killtarget = null;
                return;
            }
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (!(entity instanceof LivingEntity living) || !living.isAlive()) continue;
                if (living == mc.player) continue;
                if (!invisible.getValue() && living.isInvisible()) continue;
                boolean ok = false;
                if (players.getValue() && living instanceof Player) ok = true;
                if (mobs.getValue() && living instanceof Mob && !(living instanceof Animal) && !(living instanceof Villager)) ok = true;
                if (animals.getValue() && living instanceof Animal) ok = true;
                if (villagers.getValue() && living instanceof Villager) ok = true;
                if (!ok) continue;
                double dist = mc.player.distanceTo(living);
                if (dist > rangeValue || dist >= bestDist) continue;
                bestDist = dist;
                best = living;
            }
            killtarget = best;
        }
    }

    private boolean isUsingSpear() {
        if (mc.player == null) return false;
        ItemStack stack = mc.player.getUseItem();
        return SpearCombat.isSpear(stack) || isVanillaSpear(stack.getItem());
    }

    private static boolean isVanillaSpear(Item item) {
        return item == Items.WOODEN_SPEAR || item == Items.STONE_SPEAR || item == Items.COPPER_SPEAR
                || item == Items.IRON_SPEAR || item == Items.GOLDEN_SPEAR || item == Items.DIAMOND_SPEAR
                || item == Items.NETHERITE_SPEAR;
    }

    private int getReadyTicks(Item item) {
        int value;
        if (item == Items.WOODEN_SPEAR) value = 14;
        else if (item == Items.STONE_SPEAR || item == Items.GOLDEN_SPEAR) value = 13;
        else if (item == Items.COPPER_SPEAR) value = 12;
        else if (item == Items.IRON_SPEAR) value = 11;
        else if (item == Items.DIAMOND_SPEAR) value = 9;
        else if (item == Items.NETHERITE_SPEAR) value = 7;
        else value = 10;
        return Math.round(value * (chargeTimeModifier.getValue() / 100.0f));
    }

    private void lunge() {
        if (killtarget == null || mc.player == null) return;

        int readyTicks = getReadyTicks(mc.player.getUseItem().getItem());

        if (rotate.getValue()) rotateToTarget(killtarget);

        int useTime = mc.player.getTicksUsingItem();
        if (useTime > readyTicks) {
            AABB playerBox = mc.player.getBoundingBox().inflate(stopDistance.getValue());
            AABB targetBox = killtarget.getBoundingBox();
            boolean atTarget = playerBox.intersects(targetBox);

            if (atTarget) {
                if (stopOnTarget.getValue()) {
                    killtarget = null;
                    mc.player.setDeltaMovement(Vec3.ZERO);
                    mc.player.setSprinting(false);
                }
                firstPhase = false;
                aboveTargetPos = null;
                return;
            }

            double lungeSpeed = lungeStrength.getValue();
            Vec3 viewDir;

            switch (lungeMode.getValue()) {
                case DirectionBased -> {
                    viewDir = killtarget.getBoundingBox().getCenter().subtract(mc.player.position()).normalize();
                    mc.player.setSprinting(true);
                    mc.player.setDeltaMovement(viewDir.x * lungeSpeed, viewDir.y * lungeSpeed, viewDir.z * lungeSpeed);
                }
                case Above -> {
                    if (!firstPhase || aboveTargetPos == null) {
                        Vec3 targetCenter = killtarget.getBoundingBox().getCenter();
                        aboveTargetPos = new Vec3(targetCenter.x, targetCenter.y + aboveHeight.getValue(), targetCenter.z);
                        firstPhase = true;
                    }
                    Vec3 playerPos = mc.player.position();
                    double distToAbove = playerPos.distanceTo(aboveTargetPos);
                    if (distToAbove < aboveHeightDistance.getValue()) {
                        viewDir = killtarget.getBoundingBox().getCenter().subtract(playerPos).normalize();
                        firstPhase = false;
                    } else {
                        viewDir = aboveTargetPos.subtract(playerPos).normalize();
                    }
                    mc.player.setSprinting(true);
                    mc.player.setDeltaMovement(viewDir.x * lungeSpeed, viewDir.y * lungeSpeed, viewDir.z * lungeSpeed);
                }
            }
        }
    }

    private void rotateToTarget(LivingEntity target) {
        if (target == null || mc.player == null) return;
        Vec3 eyes = mc.player.getEyePosition();
        Vec3 aim = target.getBoundingBox().getCenter();
        double dx = aim.x - eyes.x;
        double dy = aim.y - eyes.y;
        double dz = aim.z - eyes.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) -(Mth.atan2(dy, dist) * (180.0 / Math.PI));
        mc.player.setYRot(yaw);
        mc.player.setYHeadRot(yaw);
        mc.player.setXRot(pitch);
    }
}
