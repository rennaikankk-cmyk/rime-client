package dev.guizhou.features.modules.world;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.network.AttackBlockEvent;
import dev.guizhou.event.impl.render.Render3DEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.PlacementManager;
import dev.guizhou.manager.RotationRequest;
import dev.guizhou.util.InteractionUtil;
import dev.guizhou.util.MathUtil;
import dev.guizhou.util.PacketSendUtil;
import dev.guizhou.util.diagnostics.ClientDiagnostics;
import dev.guizhou.util.inventory.Result;
import dev.guizhou.util.inventory.ResultType;
import dev.guizhou.util.inventory.SwapMode;
import dev.guizhou.util.inventory.SwapPriority;
import dev.guizhou.util.render.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.awt.Color;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public class GuangxiMineModule extends Module implements MineApi {

    private static final int DECOY_Y_OFFSET = 2000;

    private static final double GRIM_MIN_EYE = 0.4;
    private static final double GRIM_MAX_EYE = 1.62;

    /** how far inside a face the BreakAhead ray has to exit for it to count as straight-through. */
    private static final double BREAK_AHEAD_EDGE = 0.05;

    private final Setting<Double> threshold = num("Threshold", 0.7, 0.1, 1.0);
    private final Setting<Integer> fudgeTicks = num("FudgeTicks", 1, 0, 5);
    private final Setting<Integer> breakDelay = num("BreakDelay", 6, 0, 10);
    private final Setting<Boolean> pauseOnEat = bool("PauseOnEat", true);
    private final Setting<Boolean> onlyOnGround = bool("OnlyOnGround", false);
    private final Setting<Boolean> pauseOnFlying = bool("PauseOnFlying", false);
    private final Setting<Boolean> pauseInFluid = bool("PauseInFluid", false);
    private final Setting<Boolean> startRotate = bool("StartRotate", false);
    private final Setting<Boolean> endRotate = bool("EndRotate", false);
    private final Setting<ToolSwitch> swapAction = mode("SwapAction", ToolSwitch.Silent);
    private final Setting<Integer> holdTicks = num("HoldTicks", 10, 1, 40);
    private final Setting<Integer> retryTicks = num("RetryTicks", 2, 1, 20);
    private final Setting<Integer> retryAttempts = num("RetryAttempts", 6, 1, 20);
    private final Setting<Boolean> decoy = bool("GrimDecoy", true);
    private final Setting<Boolean> doubleBreak = bool("DoubleBreak", true);
    private final Setting<Boolean> breakAhead = bool("BreakAhead", false);
    private final Setting<Boolean> rebreak = bool("Rebreak", true);
    private final Setting<Boolean> swing = bool("Swing", true);
    private final Setting<Double> range = num("Range", 5.0, 1.0, 7.0);

    private final Setting<Boolean> render = bool("Render", true).setPage("Render");
    private final Setting<Float> lineWidth = num("LineWidth", 2.0f, 0.5f, 5.0f).setPage("Render");
    private final Setting<Color> lineColor = color("LineColor", 194, 192, 182, 150).setPage("Render");
    private final Setting<Color> sideColor = color("SideColor", 224, 138, 108, 40).setPage("Render");
    private final Setting<Color> primaryColor = color("PrimaryColor", 224, 138, 108, 60).setPage("Render");

    private BlockPos pos;
    private Direction direction;
    private int ticks;
    private double progress;
    private boolean started;
    private boolean finished;
    private boolean observedAir;
    private final MiningRetryWindow retry = new MiningRetryWindow();
    private final Object primaryToolOwner = new Object();
    private final Object secondaryToolOwner = new Object();
    private int primaryHeldTicks;
    private int secondaryHeldTicks;
    private boolean temporaryToolSwap;

    /** True only during the synchronous silent tool/restore transaction, never a visible hold. */
    public boolean isTemporaryToolSwap() {
        return mc.isSameThread() && temporaryToolSwap;
    }
    private net.minecraft.client.multiplayer.ClientLevel sessionLevel;
    private net.minecraft.client.player.LocalPlayer sessionPlayer;

    private BlockPos secondaryPos;
    private int secondaryTicks;
    private boolean secondaryHolding;
    private double secondaryProgress;

    private int stopCooldown;

    private long lastStopMs;
    // Time from the first locally accepted STOP to observing air; this is not a server ACK.
    private long primaryStopNanos;
    private long secondaryStopNanos;
    private long stopRecordingId;
    private double delayBalance;

    private BlockPos rebreakHoldPos;
    private int rebreakHoldTicks;
    private Object oneShotOwner;
    private BlockPos oneShotPos;

    public interface MineFinishListener { void onMineFinish(BlockPos pos); }

    private final CopyOnWriteArrayList<MineFinishListener> finishListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<MineFinishListener> submissionListeners = new CopyOnWriteArrayList<>();
    private final PlacementManager.PlacementListener blockListener = this::onBlockObserved;

    /** Early notification for predictive preplace; does not imply server confirmation. */
    public void addSubmissionListener(MineFinishListener l) { submissionListeners.addIfAbsent(l); }
    public void removeSubmissionListener(MineFinishListener l) { submissionListeners.remove(l); }

    public void addFinishListener(MineFinishListener l) { finishListeners.addIfAbsent(l); }

    public void removeFinishListener(MineFinishListener l) { finishListeners.remove(l); }

    private void fireFinish(BlockPos target) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
        for (MineFinishListener l : finishListeners) l.onMineFinish(target);
    }

    private void fireSubmitted(BlockPos target) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
        for (MineFinishListener l : submissionListeners) l.onMineFinish(target);
    }

    public GuangxiMineModule() {
        super("GuangxiMine", "Packet mines the clicked block at best-tool speed behind a Grim decoy, then rebreaks it.", Category.WORLD);

        breakAhead.setVisibility(v -> doubleBreak.getValue());
        lineWidth.setVisibility(v -> render.getValue());
        lineColor.setVisibility(v -> render.getValue());
        sideColor.setVisibility(v -> render.getValue());
        primaryColor.setVisibility(v -> render.getValue());
    }

    @Override
    public void onEnable() {
        ensureSession();
        GuizhouWare.placementManager.addListener(blockListener);
    }

    @Override
    public void onDisable() {
        GuizhouWare.placementManager.removeListener(blockListener);
        if (!nullCheck() && mc.level == sessionLevel && mc.player == sessionPlayer
                && pos != null && started && !finished) {
            abortBreak();
        }
        clearSecondary();
        clearMine();
        rebreakHoldPos = null;
        rebreakHoldTicks = 0;
        stopCooldown = 0;
        sessionLevel = null;
        sessionPlayer = null;
    }

    @Override
    public String getDisplayInfo() {
        String extra = secondaryPos != null ? " +1" : "";
        if (pos == null) return secondaryPos != null ? "+1" : null;
        if (!started) return "wait" + extra;
        return (finished ? "rebreak" : (int) (Math.min(progress / threshold.getValue(), 1) * 100) + "%") + extra;
    }

    @Subscribe
    private void onAttackBlock(AttackBlockEvent event) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
        if (nullCheck()) return;
        ensureSession();
        if (mc.player.isCreative() || mc.player.isSpectator()) return;

        event.cancel();

        if (isMining(event.getPos())) return;
        if (!inMineRange(event.getPos()) || !InteractionUtil.canBreak(event.getPos(), event.getState())) return;

        // start the block behind first so it gets demoted into the secondary slot and
        // finishes on its own while the clicked block runs as the primary break.
        BlockPos ahead = breakAheadPos(event.getPos());
        if (ahead != null) startMine(ahead, validFace(ahead));

        startMine(event.getPos(), event.getDirection());
    }

    /**
     * Traces the look ray through the clicked block and returns the block it continues into,
     * or null if it leaves through an edge / into something we can't mine. Straight-on mining
     * (any of the 8 compass directions) exits cleanly through a face, while grazing a corner
     * lands too close to the face border and is rejected so we don't waste the second slot.
     */
    private BlockPos breakAheadPos(BlockPos target) {
        if (!breakAhead.getValue() || !doubleBreak.getValue()) return null;
        // only when both slots are free, so we never disturb a break already in progress
        if (secondaryPos != null || (pos != null && started && !finished)) return null;

        Vec3 eye = mc.player.getEyePosition();
        Vec3 dir = mc.player.getLookAngle();
        AABB box = new AABB(target);

        double tEnter = Double.NEGATIVE_INFINITY;
        double tExit = Double.POSITIVE_INFINITY;
        Direction exitFace = null;

        for (Direction.Axis axis : Direction.Axis.values()) {
            double d = axis.choose(dir.x, dir.y, dir.z);
            double o = axis.choose(eye.x, eye.y, eye.z);
            double min = axis.choose(box.minX, box.minY, box.minZ);
            double max = axis.choose(box.maxX, box.maxY, box.maxZ);
            if (Math.abs(d) < 1.0E-7) {
                if (o < min || o > max) return null;
                continue;
            }
            double t1 = (min - o) / d;
            double t2 = (max - o) / d;
            double near = Math.min(t1, t2);
            double far = Math.max(t1, t2);
            if (near > tEnter) tEnter = near;
            if (far < tExit) {
                tExit = far;
                exitFace = Direction.fromAxisAndDirection(axis,
                        d > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
            }
        }
        if (exitFace == null || tEnter > tExit || tExit <= 0) return null;

        // reject exits that hug the border of the face, those are corner grazes
        Vec3 exit = eye.add(dir.scale(tExit));
        for (Direction.Axis axis : Direction.Axis.values()) {
            if (axis == exitFace.getAxis()) continue;
            double p = axis.choose(exit.x, exit.y, exit.z) - axis.choose(target.getX(), target.getY(), target.getZ());
            if (p < BREAK_AHEAD_EDGE || p > 1 - BREAK_AHEAD_EDGE) return null;
        }

        BlockPos ahead = target.relative(exitFace);
        if (!inMineRange(ahead)) return null;
        BlockState state = mc.level.getBlockState(ahead);
        if (state.isAir() || !InteractionUtil.canBreak(ahead, state)) return null;
        return ahead;
    }

    private boolean startMine(BlockPos target, Direction dir) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return false;
        // A manual retarget supersedes an owned one-shot. Never let its later cleanup
        // cancel the user's replacement target or leave the power block in rebreak.
        if (oneShotOwner != null) {
            if (started && !finished) abortBreak();
            clearMine();
        }
        if (pos != null && started && !finished) {
            if (doubleBreak.getValue() && secondaryPos == null) {
                // Keep the active break when its STOP/tool swap is refused.
                if (!demote()) return false;
            } else {
                abortBreak();
            }
        }

        GuizhouWare.swapManager.release(primaryToolOwner);
        primaryHeldTicks = 0;
        pos = target.immutable();
        primaryStopNanos = 0;
        direction = dir;
        ticks = 0;
        progress = 0;
        started = false;
        finished = false;
        observedAir = false;
        retry.reset();

        if (!paused() && stopCooldown == 0 && canBegin()) begin();
        return true;
    }

    private boolean canBegin() {
        long delay = System.currentTimeMillis() - lastStopMs;
        if (delay >= 275) return true; // grim decays the balance instead
        double cost = (300 - delay) * (decoy.getValue() ? 2 : 1);
        return delayBalance + cost <= 900;
    }

    private void trackStarts(int starts) {
        long delay = System.currentTimeMillis() - lastStopMs;
        for (int i = 0; i < starts; i++) {
            if (delay >= 275) delayBalance *= 0.9;
            else delayBalance += 300 - delay;
        }
        delayBalance = Mth.clamp(delayBalance, -1000, 1000);
    }

    @Override
    public boolean isAvailable() {
        return dev.guizhou.manager.HeartbeatManager.authorized()
                && isEnabled() && !nullCheck() && !mc.player.isCreative() && !mc.player.isSpectator();
    }

    @Override
    public boolean requestBreak(BlockPos target) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return false;
        if (!mc.isSameThread() || !isAvailable() || target == null) return false;
        ensureSession();
        if (isMining(target)) return true;
        if (oneShotOwner != null) return false;
        if (!inMineRange(target)) return false;
        BlockState state = mc.level.getBlockState(target);
        if (state.isAir() || !InteractionUtil.canBreak(target, state)) return false;
        if (pos != null && !finished) {
            if (!started) return false;
            if (!doubleBreak.getValue() || secondaryPos != null) return false;
        }
        return startMine(target, validFace(target));
    }

    /** An owned single break ends on observed air, without leaving a rebreak on a power block. */
    public boolean requestBreakOnce(BlockPos target, Object owner) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized() || owner == null || !mc.isSameThread()) return false;
        if (!isAvailable()) return false;
        ensureSession();
        if (oneShotOwner != null) return oneShotOwner == owner && target != null && target.equals(oneShotPos);
        if (isMining(target) || !hasFreePrimary()) return false;
        if (!requestBreak(target)) return false;
        oneShotOwner = owner;
        oneShotPos = target.immutable();
        return true;
    }

    /** Releases only the caller's operation. Revocation and disabling must still reach cleanup. */
    public void cancelBreakOnce(Object owner) {
        if (!mc.isSameThread() || owner == null || oneShotOwner != owner) return;
        if (!nullCheck() && mc.level == sessionLevel && mc.player == sessionPlayer && started && !finished) abortBreak();
        clearMine();
    }

    @Override
    public boolean isMining(BlockPos target) {
        return target != null && (target.equals(pos) || target.equals(secondaryPos));
    }

    @Override
    public BlockPos getRebreakPos() {
        return finished && rebreak.getValue() ? pos : null;
    }

    @Override
    public boolean hasFreePrimary() {
        return oneShotOwner == null && (pos == null || finished);
    }

    @Override
    public boolean hasFreeSecondary() {
        return oneShotOwner == null && doubleBreak.getValue() && secondaryPos == null;
    }

    @Override
    public boolean inMineRange(BlockPos target) {
        return !nullCheck() && target != null
                && mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(target)) <= range.getValue();
    }

    /** The primary mine target, finished or not. */
    public BlockPos getPrimaryPos() {
        return pos;
    }

    /** The secondary (double break) target, or null. */
    public BlockPos getSecondaryPos() {
        return secondaryPos;
    }

    /**
     * Suppresses the rebreak of {@code target} for {@code ticks} ticks, so a module can drop
     * something into the hole (AutoMine's GlassPush) before we take the block back out.
     */
    public void holdRebreak(BlockPos target, int ticks) {
        rebreakHoldPos = target != null ? target.immutable() : null;
        rebreakHoldTicks = target != null ? ticks : 0;
    }

    public void collectMiningPositions(Set<BlockPos> out, double minProgress) {
        if (pos != null && started && (finished || progress >= minProgress)) out.add(pos);
        if (secondaryPos != null && !nullCheck()) {
            BlockState state = mc.level.getBlockState(secondaryPos);
            if (!state.isAir()) {
                double delta = InteractionUtil.getBreakDelta(
                        mc.player.getInventory().getItem(bestSlot(state, secondaryPos)), state, secondaryPos);
                if (delta > 0 && secondaryTicks * delta >= minProgress) out.add(secondaryPos);
            }
        }
    }

    private boolean demote() {
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return false;
        if (!stopBreak(bestSlot(state, pos), false)) return false;
        secondaryPos = pos;
        secondaryStopNanos = primaryStopNanos;
        primaryStopNanos = 0;
        secondaryTicks = ticks;
        secondaryHolding = false;
        secondaryHeldTicks = 0;
        secondaryProgress = Math.min(progress, 1);
        return true;
    }

    private void begin() {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
        if (paused() || !rotateFor(pos, startRotate.getValue())) return;
        if (faceMargin(direction, targetBox(pos)) <= 0) {
            direction = validFace(pos);
        }
        if (!sendAction(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, direction)) return;
        started = true;
        trackStarts(decoy.getValue() ? 2 : 1);
        if (decoy.getValue()) {
            BlockPos decoyPos = pos.below(DECOY_Y_OFFSET);
            sendAction(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, decoyPos, validFace(decoyPos));
        }
        if (swing.getValue()) {
            mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        }
    }

    @Subscribe
    private void onTick(PreTickEvent event) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) {
            stopUnauthorizedMining();
            dev.guizhou.manager.HeartbeatManager.notifyDeniedOnce();
            return;
        }
        ensureSession();
        if (nullCheck()) return;
        if (mc.player.isDeadOrDying() || mc.player.isCreative() || mc.player.isSpectator()) {
            clearSecondary();
            clearMine();
            return;
        }

        if (stopCooldown > 0) stopCooldown--;
        if (rebreakHoldTicks > 0) rebreakHoldTicks--;

        tickSecondary();

        if (pos == null) return;

        if (mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > range.getValue()) {
            if (started && !finished) abortBreak();
            clearMine();
            return;
        }

        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir() && started) {
            onBlockObserved(pos, true);
            if (!rebreak.getValue()) clearMine();
            return;
        }
        if (paused()) {
            GuizhouWare.swapManager.release(primaryToolOwner);
            primaryHeldTicks = 0;
            return;
        }
        if (GuizhouWare.swapManager.isLatched(primaryToolOwner) && ++primaryHeldTicks >= holdTicks.getValue()) {
            GuizhouWare.swapManager.release(primaryToolOwner);
            primaryHeldTicks = 0;
            return; // Yield the selected slot for a tick instead of immediately reclaiming it.
        }
        if (observedAir) {
            observedAir = false;
            retry.reset();
        }

        if (!started) {
            if (state.isAir() || !InteractionUtil.canBreak(pos, state)) {
                clearMine();
                return;
            }
            if (stopCooldown > 0 || !canBegin()) return;
            direction = validFace(pos);
            begin();
            return;
        }

        if (!finished) {
            int slot = bestSlot(state, pos);
            double delta = InteractionUtil.getBreakDelta(mc.player.getInventory().getItem(slot), state, pos);
            if (delta <= 0) {
                abortBreak();
                clearMine();
                return;
            }

            ticks++;
            progress = Math.max(ticks - fudgeTicks.getValue(), 0) * delta;
            if (progress >= threshold.getValue()) {
                finished = stopBreak(slot);
                if (finished) {
                    retry.submitted();
                    fireSubmitted(pos);
                }
            }
            return;
        }

        if (rebreakHoldTicks > 0 && pos.equals(rebreakHoldPos)) return;
        retry.tick();
        if (!retry.ready(retryTicks.getValue())) return;
        if (retry.exhausted(retryAttempts.getValue())) {
            ClientDiagnostics.count("mine.retry.exhausted", 1);
            primaryStopNanos = 0;
            // Rejected early STOPs must eventually return to a fresh START, not loop forever.
            abortBreak();
            GuizhouWare.swapManager.release(primaryToolOwner);
            started = finished = false;
            ticks = 0;
            progress = 0;
            retry.reset();
            return;
        }
        if (stopBreak(bestSlot(state, pos))) {
            ClientDiagnostics.count("mine.retry.submitted", 1);
            boolean first = retry.age() == 0;
            retry.submitted();
            if (first) fireSubmitted(pos);
        }
    }

    private void tickSecondary() {
        if (secondaryPos == null) return;
        if (!inMineRange(secondaryPos)) { clearSecondary(); return; }

        BlockState state = mc.level.getBlockState(secondaryPos);
        if (state.isAir()) {
            BlockPos completed = secondaryPos;
            recordObservedAir(true);
            clearSecondary();
            fireFinish(completed);
            return;
        }
        if (paused()) {
            GuizhouWare.swapManager.release(secondaryToolOwner);
            secondaryHolding = false;
            return;
        }

        secondaryTicks++;
        int slot = bestSlot(state, secondaryPos);
        double delta = InteractionUtil.getBreakDelta(mc.player.getInventory().getItem(slot), state, secondaryPos);
        if (delta <= 0) {
            clearSecondary();
            return;
        }

        int expected = Mth.ceil(1.0 / delta) - 1;
        secondaryProgress = Math.min(secondaryTicks * delta, 1);

        if (secondaryTicks > expected + holdTicks.getValue()) {
            clearSecondary();
            return;
        }

        secondaryHolding = GuizhouWare.swapManager.isLatched(secondaryToolOwner);
        if (secondaryHolding && GuizhouWare.swapManager.latchedSlot() != slot) {
            GuizhouWare.swapManager.release(secondaryToolOwner);
            secondaryHolding = false;
        }
        if (secondaryHolding && ++secondaryHeldTicks >= holdTicks.getValue()) {
            clearSecondary();
            return;
        }
        if (secondaryTicks >= expected - 1 && !secondaryHolding && swapAction.getValue() != ToolSwitch.Manual) {
            Result result = new Result(slot, mc.player.getInventory().getItem(slot), ResultType.HOTBAR);
            if (GuizhouWare.swapManager.latch(secondaryToolOwner, result)) {
                secondaryHolding = true;
            }
        }
    }

    private void clearSecondary() {
        secondaryStopNanos = 0;
        GuizhouWare.swapManager.release(secondaryToolOwner);
        secondaryPos = null;
        secondaryTicks = 0;
        secondaryHolding = false;
        secondaryProgress = 0;
        secondaryHeldTicks = 0;
    }

    // Display-side progress: the real progress only advances per tick (20fps steps read as
    // stutter at high frame rates), so the render loop eases toward it every frame instead.
    private double renderProgress;
    private double renderSecondaryProgress;
    private double lastRenderDt = 0.0;
    private long lastRenderNs = 0L;
    private BlockPos renderPrimaryPos;
    private BlockPos renderSecondaryPos;

    @Subscribe
    private void onRender(Render3DEvent event) {
        if (nullCheck() || !render.getValue()) {
            lastRenderNs = 0L;
            return;
        }

        long now = System.nanoTime();
        if (lastRenderNs != 0L) lastRenderDt = (now - lastRenderNs) / 1_000_000_000.0;
        lastRenderNs = now;

        boolean primaryActive = pos != null && started;
        double primaryTarget = primaryActive
                ? (finished ? 1 : Math.min(progress / threshold.getValue(), 1)) : 0;
        if (!primaryActive || renderPrimaryPos == null || !renderPrimaryPos.equals(pos)) {
            renderPrimaryPos = primaryActive ? pos.immutable() : null;
            renderProgress = primaryTarget;
        } else {
            renderProgress = easeProgress(renderProgress, primaryTarget, lastRenderDt);
        }

        boolean secondaryActive = secondaryPos != null;
        if (!secondaryActive || renderSecondaryPos == null || !renderSecondaryPos.equals(secondaryPos)) {
            renderSecondaryPos = secondaryActive ? secondaryPos.immutable() : null;
            renderSecondaryProgress = secondaryActive ? secondaryProgress : 0;
        } else {
            renderSecondaryProgress = easeProgress(renderSecondaryProgress, secondaryProgress, lastRenderDt);
        }

        if (primaryActive && renderProgress > 0.004) {
            drawBlock(event, pos, primaryColor.getValue(), renderProgress);
        }
        if (secondaryActive && renderSecondaryProgress > 0.004) {
            drawBlock(event, secondaryPos, sideColor.getValue(), renderSecondaryProgress);
        }
    }

    private static double easeProgress(double current, double target, double dtSeconds) {
        if (dtSeconds <= 0) return target;
        double f = 1.0 - Math.exp(-dtSeconds * 30.0);
        return current + (target - current) * f;
    }

    private void drawBlock(Render3DEvent event, BlockPos target, Color side, double progress) {
        if (mc.level.getBlockState(target).isAir()) return;

        float t = (float) Mth.clamp(progress, 0, 1);
        double cx = target.getX() + 0.5;
        double cy = target.getY() + 0.5;
        double cz = target.getZ() + 0.5;
        double half = 0.5 * t;
        AABB box = new AABB(cx - half, cy - half, cz - half, cx + half, cy + half, cz + half);
        RenderUtil.drawBoxFilled(event.getMatrix(), box, side);
        RenderUtil.drawBox(event.getMatrix(), box, lineColor.getValue(), lineWidth.getValue());
    }

    private boolean stopBreak(int slot) {
        return stopBreak(slot, true);
    }

    private boolean stopBreak(int slot, boolean cooldown) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return false;
        if (paused()) { ClientDiagnostics.count("mine.stop.paused", 1); return false; }
        if (!rotateFor(pos, endRotate.getValue())) {
            ClientDiagnostics.count("mine.stop.rotationRefused", 1); return false;
        }
        // A temporary tool switch must not steal a live secondary or another module's hold.
        if (GuizhouWare.swapManager.isLatched() && !GuizhouWare.swapManager.isLatched(primaryToolOwner)) {
            if (pauseOnEat.getValue() || GuizhouWare.swapManager.isLatched(secondaryToolOwner)) {
                ClientDiagnostics.count("mine.stop.toolBusy", 1); return false;
            }
        }

        ItemStack stack = mc.player.getInventory().getItem(slot);
        boolean[] sent = {false};
        Runnable action = () -> {
            sent[0] = sendAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, validFace(pos));
            if (sent[0] && cooldown) stopCooldown = breakDelay.getValue();
            if (sent[0] && swing.getValue()) {
                mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
            }
        };
        Result result = new Result(slot, stack, ResultType.HOTBAR);
        if (swapAction.getValue() == ToolSwitch.Manual) {
            if (slot != mc.player.getInventory().getSelectedSlot()) return false;
            action.run();
        } else if (swapAction.getValue() == ToolSwitch.Visible) {
            if (!GuizhouWare.swapManager.latch(primaryToolOwner, result)) return false;
            action.run();
        } else {
            boolean previous = temporaryToolSwap;
            temporaryToolSwap = true;
            try {
                if (!GuizhouWare.swapManager.withSwap(result, SwapMode.SILENT, SwapPriority.MINING, action)) return false;
            } finally {
                temporaryToolSwap = previous;
            }
        }
        return sent[0];
    }

    private void abortBreak() {
        sendAction(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, pos, Direction.DOWN);
    }

    private Direction validFace(BlockPos target) {
        AABB box = targetBox(target);

        Direction best = Direction.UP;
        double bestMargin = -Double.MAX_VALUE;
        for (Direction dir : Direction.values()) {
            double margin = faceMargin(dir, box);
            if (margin > bestMargin) {
                bestMargin = margin;
                best = dir;
            }
        }
        return best;
    }

    private double faceMargin(Direction dir, AABB box) {
        Vec3 now = mc.player.position();
        Vec3 prev = new Vec3(mc.player.xo, mc.player.yo, mc.player.zo);
        return Math.min(feetMargin(dir, box, now), feetMargin(dir, box, prev));
    }

    private double feetMargin(Direction dir, AABB box, Vec3 feet) {
        return switch (dir) {
            case UP -> feet.y + GRIM_MAX_EYE - box.maxY;
            case DOWN -> box.minY - (feet.y + GRIM_MIN_EYE);
            case EAST -> feet.x - box.maxX;
            case WEST -> box.minX - feet.x;
            case SOUTH -> feet.z - box.maxZ;
            case NORTH -> box.minZ - feet.z;
        };
    }

    private AABB targetBox(BlockPos target) {
        VoxelShape shape = mc.level.getBlockState(target).getShape(mc.level, target);
        return shape.isEmpty() ? new AABB(target) : shape.bounds().move(target);
    }

    private int bestSlot(BlockState state, BlockPos target) {
        int best = mc.player.getInventory().getSelectedSlot();
        if (swapAction.getValue() == ToolSwitch.Manual) return best;
        double bestDelta = InteractionUtil.getBreakDelta(mc.player.getInventory().getItem(best), state, target);
        for (int i = 0; i < 9; i++) {
            double delta = InteractionUtil.getBreakDelta(mc.player.getInventory().getItem(i), state, target);
            if (delta > bestDelta) {
                best = i;
                bestDelta = delta;
            }
        }
        return best;
    }

    private boolean sendAction(ServerboundPlayerActionPacket.Action action, BlockPos target, Direction face) {
        // An active break must still be abortable after revocation; new START/STOP actions must not continue.
        if (action != ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK
                && !dev.guizhou.manager.HeartbeatManager.authorized()) return false;
        boolean sent = PacketSendUtil.send(new ServerboundPlayerActionPacket(action, target, face));
        if (sent && action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) {
            lastStopMs = System.currentTimeMillis();
            ClientDiagnostics.count("mine.stop.submitted", 1);
            refreshDiagnosticWindow();
            if (primaryStopNanos == 0) primaryStopNanos = ClientDiagnostics.startTimer();
        } else if (!sent) {
            ClientDiagnostics.count("mine.action.refused", 1);
        }
        return sent;
    }

    private boolean paused() {
        return nullCheck() || (pauseOnEat.getValue() && mc.player.isUsingItem())
                || (onlyOnGround.getValue() && !mc.player.onGround())
                || (pauseOnFlying.getValue() && (mc.player.isFallFlying() || mc.player.getAbilities().flying))
                || (pauseInFluid.getValue() && (mc.player.isInWater() || mc.player.isInLava()));
    }

    private boolean rotateFor(BlockPos target, boolean rotate) {
        if (!rotate) return true;
        float[] angles = MathUtil.calcAngle(mc.player.getEyePosition(), Vec3.atCenterOf(target));
        return GuizhouWare.rotationManager.trySubmit(new RotationRequest("GuangxiMine", SwapPriority.MINING,
                angles[0], angles[1], RotationRequest.Mode.SILENT));
    }

    private void onBlockObserved(BlockPos target, boolean nowAir) {
        if (!isEnabled() || !mc.isSameThread() || mc.level != sessionLevel || mc.player != sessionPlayer) return;
        if (target.equals(pos) && started && nowAir && !observedAir) {
            recordObservedAir(false);
            observedAir = true;
            finished = true;
            retry.reset();
            GuizhouWare.swapManager.release(primaryToolOwner);
            primaryHeldTicks = 0;
            if (target.equals(oneShotPos)) clearMine();
            fireFinish(target);
        }
        if (target.equals(secondaryPos) && nowAir) {
            recordObservedAir(true);
            clearSecondary();
            fireFinish(target);
        }
    }

    private void ensureSession() {
        if (sessionLevel == mc.level && sessionPlayer == mc.player) return;
        clearSecondary();
        clearMine();
        rebreakHoldPos = null;
        rebreakHoldTicks = stopCooldown = 0;
        lastStopMs = 0;
        delayBalance = 0;
        sessionLevel = mc.level;
        sessionPlayer = mc.player;
    }

    private void clearMine() {
        oneShotOwner = null;
        oneShotPos = null;
        primaryStopNanos = 0;
        GuizhouWare.swapManager.release(primaryToolOwner);
        primaryHeldTicks = 0;
        pos = null;
        direction = null;
        ticks = 0;
        progress = 0;
        started = false;
        finished = false;
        observedAir = false;
        retry.reset();
    }

    private void stopUnauthorizedMining() {
        if (!nullCheck() && mc.level == sessionLevel && mc.player == sessionPlayer
                && pos != null && started && !finished) abortBreak();
        clearSecondary();
        clearMine();
        rebreakHoldPos = null;
        rebreakHoldTicks = stopCooldown = 0;
    }

    public enum ToolSwitch { Silent, Visible, Manual }

    private void refreshDiagnosticWindow() {
        long recordingId = ClientDiagnostics.recordingId();
        if (recordingId == stopRecordingId) return;
        stopRecordingId = recordingId;
        primaryStopNanos = secondaryStopNanos = 0;
    }

    private void recordObservedAir(boolean secondary) {
        refreshDiagnosticWindow();
        ClientDiagnostics.count(secondary ? "mine.airObserved.secondary" : "mine.airObserved.primary", 1);
        ClientDiagnostics.endTimer(secondary ? "mine.stopToAir.secondary" : "mine.stopToAir.primary",
                secondary ? secondaryStopNanos : primaryStopNanos);
        if (secondary) secondaryStopNanos = 0;
        else primaryStopNanos = 0;
    }
}
