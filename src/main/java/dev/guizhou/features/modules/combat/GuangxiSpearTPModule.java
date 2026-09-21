package dev.guizhou.features.modules.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.RotationRequest;
import dev.guizhou.util.MathUtil;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

/**
 * Spear TP-stab ported from OpenKalama SpearAttack: an out-and-back fake
 * movement burst that spoofs the server's velocity estimate.
 *
 * While holding a charged spear, one trigger fires a same-tick packet chain:
 * stepped PosRot packets walk our position out along the line AWAY from the
 * target (each step collision-checked), then a single packet snaps back to
 * our real position. The server derives velocity from the last position
 * delta — the return leg covers the full distance in one packet, so the
 * charged spear's kinetic (speed-scaled) damage lands at up to Range
 * blocks/tick of impact velocity. The client never moves; our own movement
 * packets are frozen for ServerDelay ticks so the real position cannot
 * overwrite the spoof before the attack processes.
 */
public class GuangxiSpearTPModule extends Module {

    public static final String ID = "GuangxiSpearTP";
    private static final int ROTATION_PRIORITY = 6;
    private static final int MIN_CHARGE_TICKS = 8;

    private final Setting<Double> range = num("Range", 50.0, 8.0, 90.0);
    private final Setting<Double> maxTp = num("MaxTp", 100.0, 16.0, 300.0);
    private final Setting<Double> stepSize = num("StepSize", 10.0, 2.0, 50.0);
    private final Setting<Integer> serverDelay = num("ServerDelay", 2, 0, 10);
    private final Setting<Boolean> requireCharge = bool("RequireCharge", true);
    private final Setting<Double> predictTicks = num("PredictTicks", 2.0, 0.0, 10.0);

    private int freezeTicks;
    private Vec3 aimPoint;

    public GuangxiSpearTPModule() {
        super("GuangxiSpearTP", "One-shot spear TP stab: out-and-back movement burst spoofs impact velocity for the charged spear.", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        // one-shot: run on the tick we were toggled, disable happens below or on failure
        if (nullCheck()) {
            disable();
            return;
        }
        execute();
    }

    @Override
    public void onDisable() {
        freezeTicks = 0;
    }

    private void execute() {
        if (requireCharge.getValue() && !chargedSpearReady()) {
            dev.guizhou.features.commands.Command.sendMessage("GuangxiSpearTP needs a charged spear (hold use until charged)");
            disable();
            return;
        }

        Player target = findTarget();
        if (target == null) {
            disable();
            return;
        }

        Vec3 eye = mc.player.getEyePosition(1.0F);
        aimPoint = target.position().add(0, target.getBbHeight() * 0.85, 0)
                .add(target.getDeltaMovement().scale(predictTicks.getValue()));
        Vec3 aimDir = aimPoint.subtract(eye);
        double distToTarget = aimDir.length();
        if (distToTarget < 1.0E-4) {
            disable();
            return;
        }
        aimDir = aimDir.normalize();

        // spoof platform: a collision-free point on the ray AWAY from the
        // target; the return leg from there is our fabricated impact speed
        Vec3 tpPoint = findTpPoint(aimDir, distToTarget);
        if (tpPoint == null) {
            dev.guizhou.features.commands.Command.sendMessage("GuangxiSpearTP found no clear TP lane");
            disable();
            return;
        }

        float[] angles = MathUtil.calcAngle(eye, aimPoint);
        float yaw = angles[0];
        float pitch = angles[1];

        // face the target for the whole burst (PosRot carries rotation)
        mc.player.connection.send(buildMove(mc.player.position(), yaw, pitch));
        Vec3 cursor = mc.player.position();
        Vec3 total = tpPoint.subtract(cursor);
        double len = total.length();
        Vec3 step = total.scale(stepSize.getValue() / Math.max(len, 1.0E-6));
        while (cursor.distanceTo(tpPoint) > stepSize.getValue()) {
            cursor = cursor.add(step);
            mc.player.connection.send(buildMove(cursor, yaw, pitch));
        }
        // the return leg: one packet covering the full distance back — this
        // delta is the impact velocity the server will use for the stab
        mc.player.connection.send(buildMove(mc.player.position(), yaw, pitch));

        // rotation bookkeeping + the stab itself, inside the freeze window
        if (!GuizhouWare.rotationManager.trySubmit(new RotationRequest(
                ID, ROTATION_PRIORITY, yaw, pitch, RotationRequest.Mode.SILENT, false, true))) {
            disable();
            return;
        }
        mc.gameMode.attack(mc.player, target);
        mc.player.swing(InteractionHand.MAIN_HAND);

        freezeTicks = serverDelay.getValue() + 1;
        // self-disable once the burst is out; the freeze listener below keeps
        // running because the module stays registered until disable() — so
        // schedule the disable for when the freeze expires
    }

    @Subscribe
    private void onPacketSend(PacketEvent.Send event) {
        if (freezeTicks <= 0) {
            return;
        }
        if (event.getPacket() instanceof ServerboundMovePlayerPacket) {
            // keep the real position from overwriting the spoofed velocity
            event.cancel();
        }
    }

    @Subscribe
    private void onPreTick(dev.guizhou.event.impl.entity.player.PreTickEvent event) {
        if (nullCheck()) return;
        if (freezeTicks > 0 && --freezeTicks == 0) {
            disable();
        }
    }

    private ServerboundMovePlayerPacket buildMove(Vec3 pos, float yaw, float pitch) {
        return new ServerboundMovePlayerPacket.PosRot(
                pos.x, pos.y, pos.z, yaw, pitch, false, false);
    }

    private boolean chargedSpearReady() {
        return GuangxiElytraChaseModule.isUsingSpear(mc.player)
                && mc.player.getTicksUsingItem() >= MIN_CHARGE_TICKS;
    }

    private Player findTarget() {
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        Vec3 eye = mc.player.getEyePosition(1.0F);
        for (Player player : mc.level.players()) {
            if (player == mc.player || player.isSpectator() || !player.isAlive()) continue;
            if (GuizhouWare.friendManager.isFriend(player)) continue;
            double dist = mc.player.distanceToSqr(player);
            if (dist > range.getValue() * range.getValue()) continue;
            Vec3 targetEye = player.getEyePosition(1.0F);
            if (eye.distanceToSqr(targetEye) <= 4.0) continue;
            BlockHitResult hit = mc.level.clip(new ClipContext(
                    eye, targetEye, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() != HitResult.Type.MISS) continue;
            if (dist < bestDist) {
                best = player;
                bestDist = dist;
            }
        }
        return best;
    }

    /**
     * scan the away-ray for a collision-free platform with a clear stepped
     * lane out and back (port of findValidTpPosition's shape: distance
     * descending by 2, lateral offsets 0, ±1..±9 on the horizontal side axis)
     */
    private Vec3 findTpPoint(Vec3 aimDir, double distToTarget) {
        Vec3 flat = new Vec3(-aimDir.z, 0, aimDir.x); // horizontal side axis
        if (flat.lengthSqr() < 1.0E-4) {
            flat = new Vec3(1, 0, 0);
        }
        flat = flat.normalize();
        Vec3 away = aimDir.scale(-1.0);
        Vec3 origin = mc.player.position();
        double maxTpSq = maxTp.getValue() * maxTp.getValue();

        double[] laterals = new double[19];
        laterals[0] = 0.0;
        for (int i = 1; i < 10; i++) {
            laterals[i * 2 - 1] = i;
            laterals[i * 2] = -i;
        }

        for (double d = range.getValue(); d > distToTarget; d -= 2.0) {
            for (double lat : laterals) {
                Vec3 candidate = origin.add(away.scale(d)).add(flat.scale(lat));
                if (candidate.subtract(origin).horizontalDistanceSqr() > maxTpSq) continue;
                if (!fitsPlayer(candidate)) continue;
                if (laneClear(origin, candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private boolean fitsPlayer(Vec3 pos) {
        AABB box = new AABB(pos.x - 0.3, pos.y, pos.z - 0.3, pos.x + 0.3, pos.y + 1.8, pos.z + 0.3);
        return mc.level.noCollision(mc.player, box);
    }

    /** stepped lane check: sample the straight line at step intervals */
    private boolean laneClear(Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        double len = delta.length();
        if (len < 1.0E-6) return true;
        int steps = Math.max(1, (int) Math.ceil(len / (stepSize.getValue() * 0.9)));
        Vec3 step = delta.scale(1.0 / steps);
        Vec3 cursor = from;
        for (int i = 0; i < steps; i++) {
            cursor = cursor.add(step);
            if (!fitsPlayer(cursor)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String getDisplayInfo() {
        return freezeTicks > 0 ? "Burst" : "Ready";
    }
}
