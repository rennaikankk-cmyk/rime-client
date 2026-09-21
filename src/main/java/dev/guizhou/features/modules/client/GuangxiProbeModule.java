package dev.guizhou.features.modules.client;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.TelemetryManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Anticheat boundary prober — measures the server's actual thresholds with
 * zero equipment: no crystals, no blocks, no items.
 *
 * Three oracles, all free:
 * - dig reach: START_DESTROY on a real block at controlled distance; the
 *   server's own destruction-progress / block-update packet is the accept bit
 * - entity attack reach: attack an armor stand (or passive mob) at distance;
 *   its hurt animation is the accept bit
 * - move tolerance: out-and-back fake position packet pairs at increasing
 *   distance; a correction teleport is the reject bit
 *
 * Runs as a state machine on enable, prints a boundary report, optionally
 * applies the calibrated ranges to GuangxiCrystal (preset -> CUSTOM) and
 * uploads the result via telemetry.
 */
public class GuangxiProbeModule extends Module {

    public static final String ID = "GuangxiProbe";

    private static final double DIG_MIN = 2.5, DIG_MAX = 7.0, DIG_STEP = 0.25;
    private static final int TRIALS_PER_STEP = 4;
    private static final int WAIT_TICKS = 8;

    private final Setting<Boolean> probeDig = bool("ProbeDigReach", true);
    private final Setting<Boolean> probeAttack = bool("ProbeAttackReach", true);
    private final Setting<Boolean> probeMove = bool("ProbeMoveTolerance", true);
    private final Setting<Boolean> applyResults = bool("ApplyResults", true);
    private final Setting<Double> safetyMargin = num("SafetyMargin", 0.3, 0.05, 1.0);

    private enum Phase {
        SCAN_BLOCKS, DIG, ATTACK, MOVE, REPORT
    }

    private Phase phase = Phase.REPORT;
    private int phaseTick;

    // dig oracle state
    private final Map<Double, List<BlockPos>> blockBuckets = new HashMap<>();
    private final List<Double> digSteps = new ArrayList<>();
    private int digStepIndex;
    private int digTrial;
    private final Map<Double, Integer> digAccepts = new HashMap<>();
    private BlockPos pendingDigPos;
    private boolean digAccepted;
    private int digWait;

    // attack oracle state
    private final List<Entity> attackTargets = new ArrayList<>();
    private double attackBoundary = -1;
    private Entity pendingAttackTarget;
    private boolean attackAccepted;
    private int attackWait;
    private double attackTryDist;

    // move oracle state
    private static final double[] MOVE_STEPS = {2, 4, 6, 8, 10, 12, 16, 20};
    private int moveStepIndex;
    private double moveBoundary = -1;
    private int moveWait;
    private boolean movePunished;

    private double digBoundary = -1;

    public GuangxiProbeModule() {
        super("GuangxiProbe", "Measures the server's anticheat boundaries (reach, move tolerance) with bare hands, then applies the optimal config.", Category.CLIENT);
    }

    @Override
    public void onEnable() {
        if (nullCheck()) {
            disable();
            return;
        }
        blockBuckets.clear();
        digSteps.clear();
        digAccepts.clear();
        attackTargets.clear();
        digStepIndex = 0;
        digTrial = 0;
        attackBoundary = -1;
        moveBoundary = -1;
        digBoundary = -1;
        moveStepIndex = 0;
        phase = Phase.SCAN_BLOCKS;
        phaseTick = 0;
        message("Probing anticheat boundaries — stand still, ~1 minute");
    }

    @Override
    public void onDisable() {
        pendingDigPos = null;
        pendingAttackTarget = null;
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (nullCheck()) return;
        phaseTick++;
        switch (phase) {
            case SCAN_BLOCKS -> tickScanBlocks();
            case DIG -> tickDig();
            case ATTACK -> tickAttack();
            case MOVE -> tickMove();
            case REPORT -> {}
        }
    }

    // ---------------------------------------------------------------
    // phase 1: bucket nearby blocks by eye distance
    // ---------------------------------------------------------------

    private void tickScanBlocks() {
        Vec3 eye = mc.player.getEyePosition(1.0F);
        BlockPos center = BlockPos.containing(mc.player.position());
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-8, -4, -8), center.offset(8, 6, 8))) {
            if (mc.level.getBlockState(pos).isAir()) continue;
            Vec3 blockCenter = Vec3.atCenterOf(pos);
            double dist = eye.distanceTo(blockCenter);
            if (dist < DIG_MIN - DIG_STEP || dist > DIG_MAX + DIG_STEP) continue;
            BlockHitResult hit = mc.level.clip(new ClipContext(
                    eye, blockCenter, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() == HitResult.Type.MISS
                    || !hit.getBlockPos().equals(pos)) {
                continue; // occluded — server-side LOS may differ, keep clear ones only
            }
            double bucket = Math.round(dist / DIG_STEP) * DIG_STEP;
            blockBuckets.computeIfAbsent(bucket, b -> new ArrayList<>()).add(pos.immutable());
        }
        for (double d = DIG_MIN; d <= DIG_MAX + 1e-9; d += DIG_STEP) {
            if (blockBuckets.containsKey(d) && blockBuckets.get(d).size() >= TRIALS_PER_STEP) {
                digSteps.add(d);
            }
        }
        // attack targets: armor stands first, then passive mobs — never players
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player || e.isSpectator()) continue;
            if (e instanceof ArmorStand
                    || e.getType() == EntityType.ARMOR_STAND
                    || e.getType() == EntityType.VILLAGER
                    || e.getType() == EntityType.COW || e.getType() == EntityType.SHEEP
                    || e.getType() == EntityType.PIG || e.getType() == EntityType.CHICKEN) {
                double dist = mc.player.getEyePosition(1.0F).distanceTo(e.getEyePosition(1.0F));
                if (dist >= 2.0 && dist <= 7.0) {
                    attackTargets.add(e);
                }
            }
        }
        phase = probeDig.getValue() && !digSteps.isEmpty() ? Phase.DIG : Phase.ATTACK;
        if (phase == Phase.DIG) {
            message("Found " + digSteps.size() + " distance buckets for dig probing");
        } else if (probeDig.getValue()) {
            message("No probeable blocks nearby, skipping dig test");
        }
        phaseTick = 0;
    }

    // ---------------------------------------------------------------
    // phase 2: dig reach sweep (start-destroy → observe → abort)
    // ---------------------------------------------------------------

    private void tickDig() {
        if (pendingDigPos != null) {
            digWait++;
            if (digAccepted || digWait >= WAIT_TICKS) {
                mc.getConnection().send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, pendingDigPos, Direction.UP));
                if (digAccepted) {
                    digAccepts.merge(digSteps.get(digStepIndex), 1, Integer::sum);
                }
                pendingDigPos = null;
                digTrial++;
            } else {
                return;
            }
        }
        if (digTrial >= TRIALS_PER_STEP) {
            digTrial = 0;
            digStepIndex++;
            if (digStepIndex >= digSteps.size()) {
                digBoundary = boundaryFrom(digSteps, digAccepts);
                phase = Phase.ATTACK;
                phaseTick = 0;
                return;
            }
        }
        double step = digSteps.get(digStepIndex);
        List<BlockPos> bucket = blockBuckets.get(step);
        BlockPos pos = bucket.get((digTrial + digStepIndex) % bucket.size());
        pendingDigPos = pos;
        digAccepted = false;
        digWait = 0;
        mc.getConnection().send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP));
    }

    /** boundary = furthest step with at least half the trials accepted */
    private double boundaryFrom(List<Double> steps, Map<Double, Integer> accepts) {
        double best = -1;
        for (double step : steps) {
            int ok = accepts.getOrDefault(step, 0);
            if (ok >= (TRIALS_PER_STEP + 1) / 2) {
                best = Math.max(best, step);
            }
        }
        return best;
    }

    // ---------------------------------------------------------------
    // phase 3: attack reach sweep against live entities
    // ---------------------------------------------------------------

    private void tickAttack() {
        if (pendingAttackTarget != null) {
            attackWait++;
            if (attackAccepted || attackWait >= 4) {
                if (attackAccepted && attackTryDist > attackBoundary) {
                    attackBoundary = attackTryDist;
                }
                pendingAttackTarget = null;
            } else {
                return;
            }
        }
        Entity target = nextAttackTarget();
        if (target == null) {
            if (probeMove.getValue()) {
                phase = Phase.MOVE;
                phaseTick = 0;
            } else {
                finish();
            }
            return;
        }
        pendingAttackTarget = target;
        attackAccepted = false;
        attackWait = 0;
        attackTryDist = mc.player.getEyePosition(1.0F).distanceTo(target.getEyePosition(1.0F));
        mc.getConnection().send(ServerboundInteractPacket.createAttackPacket(target, false));
    }

    /** ordered farthest-first so the sweep converges on the boundary */
    private Entity nextAttackTarget() {
        Entity best = null;
        double bestDist = -1;
        for (Entity e : attackTargets) {
            if (!e.isAlive()) continue;
            double dist = mc.player.getEyePosition(1.0F).distanceTo(e.getEyePosition(1.0F));
            if (dist > attackBoundary + 0.25 && dist > bestDist) {
                best = e;
                bestDist = dist;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------
    // phase 4: move tolerance (out-and-back pairs, watch for correction)
    // ---------------------------------------------------------------

    private void tickMove() {
        if (movePunished) {
            moveWait++;
            if (moveWait >= 8) {
                if (moveStepIndex > 0) {
                    moveBoundary = MOVE_STEPS[moveStepIndex - 1];
                }
                finish();
            }
            return;
        }
        if (moveWait < 8) {
            moveWait++;
            return;
        }
        if (moveStepIndex >= MOVE_STEPS.length) {
            moveBoundary = MOVE_STEPS[MOVE_STEPS.length - 1];
            finish();
            return;
        }
        double d = MOVE_STEPS[moveStepIndex++];
        Vec3 pos = mc.player.position();
        Vec3 dir = new Vec3(Math.sin(Math.toRadians(mc.player.getYRot() + 90)), 0,
                Math.cos(Math.toRadians(mc.player.getYRot() + 90)));
        if (dir.lengthSqr() < 1e-4) dir = new Vec3(1, 0, 0);
        Vec3 away = pos.add(dir.normalize().scale(d));
        // out-and-back in one tick: the server's move check (if any) answers
        // with a correction teleport; harmless on permissive servers
        mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(
                away.x, away.y, away.z, false));
        mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(
                pos.x, pos.y, pos.z, false));
        moveWait = 0;
    }

    // ---------------------------------------------------------------
    // report / apply
    // ---------------------------------------------------------------

    private void finish() {
        phase = Phase.REPORT;
        double placeSuggestion = digBoundary > 0 ? Math.min(6.0, digBoundary - safetyMargin.getValue()) : -1;
        double attackSuggestion = attackBoundary > 0 ? Math.min(6.0, attackBoundary - safetyMargin.getValue()) : -1;

        message("=== Probe report ===");
        message(String.format("dig reach boundary: %s", digBoundary > 0 ? String.format("%.2f", digBoundary) : "unknown"));
        message(String.format("attack reach boundary: %s", attackBoundary > 0 ? String.format("%.2f", attackBoundary) : "unknown"));
        message(String.format("move tolerance: %s blocks/packet", moveBoundary > 0 ? String.format("%.0f", moveBoundary) : (movePunished ? "0" : ">20")));

        if (applyResults.getValue()) {
            Module crystal = GuizhouWare.moduleManager.getModuleByName("GuangxiCrystal");
            if (crystal instanceof dev.guizhou.features.modules.combat.GuangxiCrystalModule crystalModule
                    && placeSuggestion > 0) {
                crystalModule.applyCalibratedRanges(placeSuggestion, attackSuggestion > 0 ? attackSuggestion : 3.0);
                message(String.format("Applied: PlaceRange=%.2f BreakRange=%.2f (RangePreset=CUSTOM)",
                        placeSuggestion, attackSuggestion > 0 ? attackSuggestion : 3.0));
            }
        }
        String moveAdvice = moveBoundary >= 10
                ? "direct-send TP viable (1.12-style)"
                : "use sandwich/fake-move mode for TP";
        message("TP advice: " + moveAdvice);

        com.google.gson.JsonObject fields = new com.google.gson.JsonObject();
        fields.addProperty("server", currentServer());
        fields.addProperty("dig", digBoundary);
        fields.addProperty("attack", attackBoundary);
        fields.addProperty("move", moveBoundary);
        TelemetryManager.record("probe_result", fields);

        disable();
    }

    private String currentServer() {
        return mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "singleplayer";
    }

    // ---------------------------------------------------------------
    // oracle signals (receive packets)
    // ---------------------------------------------------------------

    @Subscribe
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null) return;
        if (event.getPacket() instanceof ClientboundBlockDestructionPacket destruction
                && pendingDigPos != null
                && destruction.getId() == mc.player.getId()
                && destruction.getPos().equals(pendingDigPos)) {
            digAccepted = true;
        } else if (event.getPacket() instanceof ClientboundBlockUpdatePacket update
                && pendingDigPos != null
                && update.getPos().equals(pendingDigPos)
                && update.getBlockState().isAir()) {
            digAccepted = true; // instant-break blocks ack via the air update
        } else if (event.getPacket() instanceof ClientboundAnimatePacket animate
                && pendingAttackTarget != null
                && animate.getId() == pendingAttackTarget.getId()
                && animate.getAction() == 1 /* hurt */) {
            attackAccepted = true;
        } else if (event.getPacket() instanceof ClientboundPlayerPositionPacket position) {
            // correction teleport while we are mid move-probe = the move check bit back
            if (phase == Phase.MOVE && !movePunished && moveWait > 0) {
                double d = guizhouware$absolutePosition(position).distanceTo(mc.player.position());
                if (d > 1.0) {
                    movePunished = true;
                    moveWait = 0;
                }
            }
        }
    }

    private void message(String text) {
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("[Probe] " + text), false);
        }
    }

    @Override
    public String getDisplayInfo() {
        return switch (phase) {
            case SCAN_BLOCKS -> "Scanning";
            case DIG -> String.format("Dig %.2f", digSteps.isEmpty() ? 0 : digSteps.get(digStepIndex));
            case ATTACK -> String.format("Atk %.1f", attackTryDist);
            case MOVE -> moveStepIndex < MOVE_STEPS.length ? String.format("Move %.0f", MOVE_STEPS[moveStepIndex]) : "Move";
            case REPORT -> "Done";
        };
    }
private Vec3 guizhouware$absolutePosition(net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket packet) {
        var relative = packet.getRelativeArguments();
        double x = relative.contains(net.minecraft.world.entity.RelativeMovement.X) ? mc.player.getX() + packet.getX() : packet.getX();
        double y = relative.contains(net.minecraft.world.entity.RelativeMovement.Y) ? mc.player.getY() + packet.getY() : packet.getY();
        double z = relative.contains(net.minecraft.world.entity.RelativeMovement.Z) ? mc.player.getZ() + packet.getZ() : packet.getZ();
        return new Vec3(x, y, z);
    }
}
