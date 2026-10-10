package dev.guizhou.features.modules.qinghua;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.qinghua.util.SortPriority;
import dev.guizhou.features.modules.qinghua.util.TargetUtils;
import dev.guizhou.features.settings.Setting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Port of eternal ETPBot — follow / predict / velocity move unchanged.
 * Meteor Timer override omitted (no Timer module in rime). Entity list uses Targets.
 */
public class ETPBotModule extends Module {
    private final Setting<SortPriority> priority = mode("TargetPriority", SortPriority.LowestDistance)
            .described("Target sorting priority.");
    public final Setting<Double> targetRange = num("TargetRange", 500.0, 0.1, 2000.0)
            .described("Range to find target.");
    public final Setting<Double> speed = num("Speed", 1.0, 0.01, 20.0)
            .described("Horizontal follow speed.");
    public final Setting<Double> ySpeed = num("YSpeed", 1.0, 0.01, 20.0)
            .described("Vertical follow speed.");
    public final Setting<Double> xOffset = num("XOffset", 0.0, -10.0, 10.0);
    public final Setting<Double> yOffset = num("YOffset", 0.0, -10.0, 10.0);
    public final Setting<Double> zOffset = num("ZOffset", 0.0, -10.0, 10.0);
    private final Setting<Boolean> targetFriend = bool("TargetFriend", false);
    private final Setting<Boolean> usePredict = bool("UsePredict", true);
    private final Setting<Integer> predictTicks = num("PredictTicks", 5, 1, 40)
            .setVisibility(v -> usePredict.getValue());
    private final Setting<Double> pointInterval = num("PointInterval", 1.0, 1.0, 10.0);

    private Entity target;
    private final java.util.List<Vec3> predictedPath = new java.util.ArrayList<>();
    private Vec3 lastTargetPos = null;
    private Vec3 smoothVelocity = Vec3.ZERO;
    private int targetNotMovingTicks = 0;

    public ETPBotModule() {
        super("ETPBot", "Fly Follower.", Category.MOVEMENT);
    }

    @Override
    public void onDisable() {
        this.target = null;
        this.predictedPath.clear();
        this.lastTargetPos = null;
        this.smoothVelocity = Vec3.ZERO;
        this.targetNotMovingTicks = 0;
    }

    @Subscribe
    private void onTick(PreTickEvent event) {
        if (mc.player == null || mc.level == null) return;
        var crystal = GuizhouWare.moduleManager == null ? null
                : GuizhouWare.moduleManager.getModuleByClass(
                        dev.guizhou.features.modules.combat.GuangxiCrystalModule.class);
        if (crystal != null && crystal.isEnabled()) return;

        Entity newTarget = this.findTarget();
        this.predictedPath.clear();
        if (newTarget == null || !newTarget.equals(this.target)) {
            this.lastTargetPos = null;
            this.smoothVelocity = Vec3.ZERO;
            this.targetNotMovingTicks = 0;
        }

        this.target = newTarget;
        if (this.target != null) {
            this.computePrediction();
            this.doTpMove();
        }
    }

    private Entity findTarget() {
        java.util.List<Entity> entities = new java.util.ArrayList<>();
        TargetUtils.getList(entities, this::targetCheck, this.priority.getValue(), 1);
        return entities.isEmpty() ? null : entities.get(0);
    }

    private boolean targetCheck(Entity entity) {
        if (entity == mc.player) return false;
        if (entity instanceof LivingEntity living && (living.isDeadOrDying() || living.getHealth() <= 0.0F)) {
            return false;
        }
        if (entity instanceof Player player) {
            if (player.isDeadOrDying() || player.getHealth() <= 0.0F) return false;
            if (player.isCreative()) return false;
            boolean friend = GuizhouWare.friendManager != null && GuizhouWare.friendManager.isFriend(player);
            if (friend) {
                if (!this.targetFriend.getValue()) return false;
            } else if (!TargetUtils.matchesSelector(entity)) {
                return false;
            }
        } else if (!TargetUtils.matchesSelector(entity)) {
            return false;
        }
        double dist = mc.player.distanceTo(entity);
        return !(dist > this.targetRange.getValue());
    }

    private void computePrediction() {
        if (this.target == null) return;
        Vec3 currentPos = this.target.position();
        if (this.lastTargetPos != null) {
            Vec3 rawVelocity = currentPos.subtract(this.lastTargetPos);
            if (rawVelocity.lengthSqr() < 1.0E-4) {
                this.targetNotMovingTicks++;
            } else {
                this.targetNotMovingTicks = 0;
            }
            double alpha = 0.35;
            this.smoothVelocity = new Vec3(
                    this.smoothVelocity.x * (1.0 - alpha) + rawVelocity.x * alpha,
                    this.smoothVelocity.y * (1.0 - alpha) + rawVelocity.y * alpha,
                    this.smoothVelocity.z * (1.0 - alpha) + rawVelocity.z * alpha
            );
        }

        this.lastTargetPos = currentPos;
        Vec3 vel = this.targetNotMovingTicks > 3 ? Vec3.ZERO : this.smoothVelocity;
        double x = currentPos.x;
        double y = currentPos.y;
        double z = currentPos.z;
        int ticks = this.predictTicks.getValue();
        double interval = this.pointInterval.getValue();

        for (int tick = 0; tick < ticks; tick++) {
            x += vel.x;
            y += vel.y;
            z += vel.z;
            if (tick % (int) interval == 0) {
                this.predictedPath.add(new Vec3(x, y, z));
            }
        }

        Vec3 last = this.predictedPath.isEmpty() ? null : this.predictedPath.get(this.predictedPath.size() - 1);
        Vec3 finalPos = new Vec3(x, y, z);
        if (last == null || !last.equals(finalPos)) {
            this.predictedPath.add(finalPos);
        }
    }

    private void doTpMove() {
        if (this.target == null || mc.player == null || mc.level == null) return;
        double targetX;
        double targetY;
        double targetZ;
        if (this.usePredict.getValue() && !this.predictedPath.isEmpty()) {
            Vec3 predicted = this.predictedPath.get(this.predictedPath.size() - 1);
            targetX = predicted.x + this.xOffset.getValue();
            targetY = predicted.y + this.yOffset.getValue();
            targetZ = predicted.z + this.zOffset.getValue();
        } else {
            targetX = this.target.getX() + this.xOffset.getValue();
            targetY = this.target.getY() + this.yOffset.getValue();
            targetZ = this.target.getZ() + this.zOffset.getValue();
        }

        double dx = targetX - mc.player.getX();
        double dy = targetY - mc.player.getY();
        double dz = targetZ - mc.player.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        double velX = 0.0;
        double velZ = 0.0;
        if (horizontalDist > 0.01) {
            double moveX = Math.min(this.speed.getValue(), horizontalDist);
            velX = dx / horizontalDist * moveX;
            velZ = dz / horizontalDist * moveX;
        }

        double velY = 0.0;
        if (Math.abs(dy) > 0.01) {
            velY = Math.min(this.ySpeed.getValue(), Math.abs(dy));
            velY *= Math.signum(dy);
        }

        mc.player.setDeltaMovement(velX, velY, velZ);
    }

    @Override
    public String getDisplayInfo() {
        return this.target != null ? this.target.getName().getString() : null;
    }
}
