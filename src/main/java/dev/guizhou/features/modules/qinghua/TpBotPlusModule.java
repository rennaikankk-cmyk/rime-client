package dev.guizhou.features.modules.qinghua;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.qinghua.util.PredictUtility;
import dev.guizhou.features.modules.qinghua.util.SortPriority;
import dev.guizhou.features.modules.qinghua.util.TargetUtils;
import dev.guizhou.features.settings.Setting;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;

/**
 * Port of Pattern TpBotPlus — teleport/velocity follow unchanged.
 * Intermediary class_* → Mojang; SimplePredictUtility → PredictUtility.predictPosition.
 */
public class TpBotPlusModule extends Module {
    public enum MoveMode { Teleport, Velocity }

    private final Setting<Boolean> toggleX = bool("X", true).described("Toggle X");
    private final Setting<Boolean> toggleY = bool("Y", false).described("Toggle Y");
    private final Setting<Boolean> toggleZ = bool("Z", true).described("Toggle Z");
    private final Setting<Double> relX = num("RelativeX", 0.0, -25.0, 25.0)
            .setVisibility(v -> toggleX.getValue());
    private final Setting<Double> relY = num("RelativeY", 0.0, -25.0, 25.0)
            .setVisibility(v -> toggleY.getValue());
    private final Setting<Double> relZ = num("RelativeZ", 0.0, -25.0, 25.0)
            .setVisibility(v -> toggleZ.getValue());
    private final Setting<Boolean> randTp = bool("RandomTp", false)
            .described("Randomize relative offset");
    private final Setting<Boolean> usePredict = bool("Predict", true)
            .described("Predict target movement");
    private final Setting<Integer> predictTicks = num("PredictTicks", 1, 1, 10)
            .setVisibility(v -> usePredict.getValue());
    private final Setting<Boolean> ignF = bool("IgnoreFriends", true)
            .described("Ignore friends");
    private final Setting<MoveMode> moveMode = mode("MoveMode", MoveMode.Teleport);
    private final Setting<Double> velocitySpeed = num("VelocitySpeed", 8.0, 0.01, 128.0)
            .setVisibility(v -> moveMode.getValue() == MoveMode.Velocity);

    private Entity target;

    public TpBotPlusModule() {
        super("TpBotPlus", "Teleport to player", Category.MOVEMENT);
    }

    @Subscribe
    private void onTick(PreTickEvent event) {
        if (mc.player == null) return;
        // Packet teleport every tick desyncs placement (crystal UseItemOn / server reach).
        // Yield while auto-crystal is on so place/break packets keep a stable eye position.
        var crystal = GuizhouWare.moduleManager == null ? null
                : GuizhouWare.moduleManager.getModuleByClass(
                        dev.guizhou.features.modules.combat.GuangxiCrystalModule.class);
        if (crystal != null && crystal.isEnabled()) return;
        if (!(this.target == null || !this.target.isAlive()
                || this.target instanceof LivingEntity living && living.isDeadOrDying())) {
            Vec3 targetPos = this.usePredict.getValue()
                    ? PredictUtility.predictPosition(this.target, this.predictTicks.getValue())
                    : this.target.position();
            double rx = this.relX.getValue();
            double ry = this.relY.getValue();
            double rz = this.relZ.getValue();
            if (this.randTp.getValue()) {
                if (this.toggleX.getValue()) rx *= Math.random() * 2.0 - 1.0;
                if (this.toggleY.getValue()) ry *= Math.random() * 2.0 - 1.0;
                if (this.toggleZ.getValue()) rz *= Math.random() * 2.0 - 1.0;
            }
            double targetX = this.toggleX.getValue() ? targetPos.x + rx : mc.player.getX();
            double targetY = this.toggleY.getValue() ? targetPos.y + ry : mc.player.getY();
            double targetZ = this.toggleZ.getValue() ? targetPos.z + rz : mc.player.getZ();
            Vec3 to = new Vec3(targetX, targetY, targetZ);
            Vec3 from = mc.player.position();
            if (this.moveMode.getValue() == MoveMode.Teleport) {
                this.doTeleport(from, to);
            } else {
                this.doVelocityMove(from, to);
            }
        } else {
            this.updateTarget();
        }
    }

    private void doTeleport(Vec3 from, Vec3 to) {
        if (mc.player == null || mc.player.connection == null) return;
        double distance = from.distanceTo(to);
        int steps = (int) Math.ceil(distance / 10.0);
        if (steps > 20) steps = 20;
        if (steps < 1) steps = 1;
        boolean hc = mc.player.horizontalCollision;
        for (int i = 1; i <= steps; ++i) {
            double t = (double) i / (double) steps;
            double px = from.x + (to.x - from.x) * t;
            double py = from.y + (to.y - from.y) * t;
            double pz = from.z + (to.z - from.z) * t;
            mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(px, py, pz, false, hc));
        }
        mc.player.setPos(to.x, to.y, to.z);
        mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(to.x, to.y, to.z, false, hc));
    }

    private void doVelocityMove(Vec3 from, Vec3 to) {
        Vec3 targetDir = to.subtract(from);
        double distance = targetDir.length();
        if (distance < 0.01) return;
        Vec3 finalDir = targetDir.normalize();
        double speed = this.velocitySpeed.getValue();
        if (distance < speed) speed = distance;
        mc.player.setDeltaMovement(finalDir.scale(speed));
    }

    private void updateTarget() {
        ArrayList<Entity> entities = new ArrayList<>();
        TargetUtils.getList(entities, entity -> {
            if (entity == mc.player) return false;
            if (!entity.isAlive()) return false;
            if (entity instanceof LivingEntity living && living.isDeadOrDying()) return false;
            if (entity instanceof Player player
                    && GuizhouWare.friendManager != null
                    && GuizhouWare.friendManager.isFriend(player)) {
                return !this.ignF.getValue();
            }
            return TargetUtils.matchesSelector(entity);
        }, SortPriority.LowestDistance, 1);
        this.target = entities.isEmpty() ? null : entities.get(0);
    }

    @Override
    public void onEnable() {
        this.updateTarget();
    }

    @Override
    public void onDisable() {
        this.target = null;
        if (mc.player != null && mc.player.connection != null) {
            mc.player.setDeltaMovement(Vec3.ZERO);
            boolean hc = mc.player.horizontalCollision;
            mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(), false, hc));
        }
    }

    @Override
    public String getDisplayInfo() {
        return this.target != null ? this.target.getName().getString() : null;
    }
}
