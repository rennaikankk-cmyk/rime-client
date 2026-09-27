package dev.guizhou.features.modules.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.render.Render3DEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.client.TargetsModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.RotationRequest;
import dev.guizhou.mixin.client.ClientLevelAccessor;
import dev.guizhou.util.AnchorDamageUtil;
import dev.guizhou.util.DamageSyncTracker;
import dev.guizhou.util.MathUtil;
import dev.guizhou.util.PlaceUtil;
import dev.guizhou.util.PacketSendUtil;
import dev.guizhou.util.combat.AnchorSequence;
import dev.guizhou.util.combat.AnchorTiming;
import dev.guizhou.features.modules.world.GuangxiMineModule;
import dev.guizhou.util.combat.BlastStageDiagnostics;
import dev.guizhou.util.combat.AnchorPlanner;
import dev.guizhou.util.combat.CombatantSnapshot;
import dev.guizhou.util.combat.CombatSnapshotService;
import dev.guizhou.util.combat.CombatSnapshotValidation;
import dev.guizhou.util.combat.CombatPlanningTask;
import dev.guizhou.util.combat.CombatTerrainCapture;
import dev.guizhou.util.diagnostics.ClientDiagnostics;
import dev.guizhou.util.inventory.SwapMode;
import dev.guizhou.util.inventory.SwapPriority;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.inventory.Result;
import dev.guizhou.util.inventory.ResultType;
import dev.guizhou.util.render.RenderUtil;
import dev.guizhou.util.player.EatUtil;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

public class AutoAnchorModule extends Module {

    private final Setting<Double>  minDamage     = num("MinDamage", 6.0, 0.0, 36.0).setPage("General");
    private final Setting<Double>  maxSelfDamage = num("MaxSelfDamage", 4.0, 0.0, 36.0).setPage("General");
    private final Setting<Double> placeRange = num("PlaceRange", 5.0, 1.0, 6.0).setPage("General");
    private final Setting<Double> wallRange = num("WallRange", 5.0, 0.0, 6.0).setPage("General");
    private final Setting<Boolean> airPlace = bool("AirPlace", true).setPage("General");
    private final Setting<Boolean> rotate = bool("Rotate", true).setPage("General");
    private final Setting<Boolean> assumeBestArmor = bool("AssumeBestArmor", false).setPage("General");
    private enum AnchorSwap { SILENT, INVENTORY }
    private final Setting<AnchorSwap> autoSwap = mode("AutoSwap", AnchorSwap.SILENT).setPage("General");
    private enum StageSwap { INHERIT, SILENT, INVENTORY }
    private final Setting<StageSwap> placeSwitch = mode("PlaceSwitch", StageSwap.INHERIT).setPage("General");
    private final Setting<StageSwap> chargeSwitch = mode("ChargeSwitch", StageSwap.INHERIT).setPage("General");
    private final Setting<StageSwap> explodeSwitch = mode("ExplodeSwitch", StageSwap.INHERIT).setPage("General");
    private final Setting<Boolean> spamPlace = bool("SpamPlace", true).setPage("General");
    private final Setting<Boolean> detectMining = bool("DetectMining", true).setPage("General");
    private final Setting<Integer> placeDelay = num("PlaceDelay", 0, 0, 20).setPage("General");
    private final Setting<Integer> chargeDelay = num("ChargeDelay", 0, 0, 20).setPage("General");
    private final Setting<Integer> detonateDelay = num("DetonateDelay", 0, 0, 20).setPage("General");
    private final Setting<Boolean> extrapolate = bool("Extrapolate", true).setPage("General");
    private final Setting<Boolean> selfExtrapolate = bool("SelfExtrapolate", true).setPage("General");
    private final Setting<Integer> extrapolationTicks = num("ExtrapolationTicks", 6, 0, 20).setPage("General");
    private final Setting<Integer> confirmTicks = num("ConfirmTicks", 8, 2, 40).setPage("General");
    private final Setting<Integer> retries = num("Retries", 2, 1, 5).setPage("General");
    private final Setting<Integer> delay         = num("Delay", 2, 0, 20).setPage("General");
    private final Setting<Boolean> asyncCompute = bool("AsyncCompute", false).setPage("General");
    private final Setting<Integer> asyncMaxAge = num("AsyncMaxAge", 2, 1, 5).setPage("General");
    private final Setting<Double> snapshotBudget = num("SnapshotBudgetMs", 4.0, 1.0, 10.0).setPage("General");

    private final Setting<Boolean> damageSync    = bool("DamageSync", false).setPage("General");
    private final Setting<Boolean> render        = bool("Render", true).setPage("Render");
    private final Setting<Float>   fadeTime      = num("FadeTime", 1.0f, 0.05f, 2.0f).setPage("Render");
    private final Setting<Color>   fillColor     = color("FillColor", 224, 138, 108, 45).setPage("Render");
    private final Setting<Color>   outlineColor  = color("OutlineColor", 224, 138, 108, 255).setPage("Render");

    private final CombatPlanningTask<AnchorPlanner.Input, AnchorPlanner.Result> planner = new CombatPlanningTask<>(input -> {
        long timer = ClientDiagnostics.startTimer();
        try { return AnchorPlanner.plan(input); }
        finally { ClientDiagnostics.endTimer("combat.AutoAnchor.async.compute", timer); }
    });
    private record PlanningTicket(AnchorPlanner.Input input, CombatTerrainCapture.Captured captured, int settings) {}
    private PlanningTicket planning;
    private long planningGeneration;
    private int syncUntilTick;
    private int capturedTick = Integer.MIN_VALUE;
    private int lastBurstTick = -1;
    private final AnchorSequence sequence = new AnchorSequence();
    private final BlastStageDiagnostics stageDiagnostics = new BlastStageDiagnostics("AutoAnchor");
    private BlockPos activePos;
    private BlockPos repeatPos;
    private int repeatUntil;
    private final AnchorTiming timing = new AnchorTiming();
    private final java.util.Map<Integer, List<AnchorDamageUtil.Target>> targetPredictions = new java.util.HashMap<>();
    private final java.util.Map<Integer, List<Vec3>> selfPaths = new java.util.HashMap<>();
    private final java.util.Map<Integer, AnchorDamageUtil.Target> selfPredictions = new java.util.HashMap<>();
    private long lifecycleGeneration;
    private boolean reactiveScheduled;
    private int budgetTick = Integer.MIN_VALUE;
    private int actionsThisTick;
    private int reactiveRunsThisTick;
    private int lastActionTick = Integer.MIN_VALUE;
    private final dev.guizhou.manager.PlacementManager.PlacementListener placementListener = this::onBlockUpdate;
    private ClientLevel sessionLevel;
    private LocalPlayer sessionPlayer;
    private float lastDamage = 0f;
    private BlockPos renderPos = null;
    private long renderStart = 0L;

    public AutoAnchorModule() {
        super("AutoAnchor", "Places, charges and detonates anchors after observing each block state.", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        resetSession();
        GuizhouWare.placementManager.addListener(placementListener);
    }

    @Override
    public void onDisable() {
        GuizhouWare.placementManager.removeListener(placementListener);
        resetSession();
    }

    private void resetSession() {
        lifecycleGeneration++;
        reactiveScheduled = false;
        repeatPos = null;
        repeatUntil = 0;
        timing.reset();
        targetPredictions.clear();
        selfPredictions.clear();
        selfPaths.clear();
        budgetTick = Integer.MIN_VALUE;
        lastActionTick = Integer.MIN_VALUE;
        stageDiagnostics.reset();
        lastBurstTick = -1;
        planningGeneration++;
        planner.invalidate();
        planning = null;
        syncUntilTick = 0;
        capturedTick = Integer.MIN_VALUE;
        lastDamage = 0f;
        renderPos = null;
        activePos = null;
        sequence.reset();
        sessionLevel = mc.level;
        sessionPlayer = mc.player;
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) {
            dev.guizhou.manager.HeartbeatManager.notifyDeniedOnce();
            return;
        }
        runCycle();
    }

    private void onBlockUpdate(BlockPos pos, boolean nowAir) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized() || !isEnabled() || nullCheck()
                || sessionLevel != mc.level || sessionPlayer != mc.player || !pos.equals(activePos)) return;
        if (reactiveScheduled) { ClientDiagnostics.count("combat.AutoAnchor.reactive.coalesced", 1); return; }
        reactiveScheduled = true;
        long generation = lifecycleGeneration;
        var level = mc.level;
        var player = mc.player;
        mc.schedule(() -> {
            if (generation != lifecycleGeneration || !isEnabled() || mc.level != level || mc.player != player) return;
            reactiveScheduled = false;
            if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
            resetBudget(player.tickCount);
            if (reactiveRunsThisTick++ >= 2) return;
            ClientDiagnostics.count("combat.AutoAnchor.reactive.drain", 1);
            runCycle();
        });
    }

    private void resetBudget(int tick) {
        if (budgetTick == tick) return;
        budgetTick = tick;
        actionsThisTick = reactiveRunsThisTick = 0;
    }

    private void runCycle() {
        if (!dev.guizhou.manager.HeartbeatManager.authorized() || !mc.isSameThread()) return;
        if (sessionLevel != mc.level || sessionPlayer != mc.player) resetSession();
        if (nullCheck() || mc.player.isDeadOrDying()) return;
        if (mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.inventoryMenu.getCarried().isEmpty()
                || mc.player.isShiftKeyDown() || EatUtil.shouldDefer()) return;
        int tick = mc.player.tickCount;
        resetBudget(tick);
        if (actionsThisTick >= 3) return;
        targetPredictions.clear();
        selfPredictions.clear();
        selfPaths.clear();
        // One observed completion may immediately select its old position, never an unbounded burst.
        for (int advance = 0; advance < 2; advance++) {
            Candidate freshCandidate = null;
            List<AnchorDamageUtil.Target> targets = null;
            AnchorDamageUtil.Target self = null;
            if (activePos == null) {
                if (repeatPos != null && (!spamPlace.getValue() || tick > repeatUntil)) repeatPos = null;
                boolean delayed = lastBurstTick >= 0 && tick - lastBurstTick < delay.getValue();
                if (repeatPos == null && delayed) return;
                targets = collectTargets();
                self = AnchorDamageUtil.Target.of(mc.player);
                long scanTimer = ClientDiagnostics.startTimer();
                Candidate best = null;
                try {
                    if (repeatPos != null) {
                        best = repeatCandidate(repeatPos, targets, self);
                        ClientDiagnostics.count("combat.AutoAnchor.repeat." + (best != null ? "applied" : "rejected"), 1);
                        repeatPos = null;
                    }
                    if (best == null && !delayed) best = selectCandidate(targets, self);
                } finally { ClientDiagnostics.endTimer("combat.AutoAnchor.scan", scanTimer); }
                if (best == null) { lastDamage = 0f; return; }
                freshCandidate = best;
                activePos = best.pos;
                sequence.reset();
            }
            BlockState state = mc.level.getBlockState(activePos);
            boolean anchor = state.is(Blocks.RESPAWN_ANCHOR);
            AnchorSequence.Action awaited = sequence.awaiting();
            int sentTick = sequence.sentTick();
            AnchorSequence.Action action = sequence.next(anchor, anchor ? state.getValue(RespawnAnchorBlock.CHARGE) : 0,
                    state.canBeReplaced(), tick, confirmTicks.getValue(), retries.getValue());
            stageDiagnostics.decision(action.name(), tick, confirmTicks.getValue());
            if (awaited != null && action != AnchorSequence.Action.WAIT && action != AnchorSequence.Action.ABORT
                    && action != awaited) timing.observed(awaited, tick - sentTick);
            if (action == AnchorSequence.Action.WAIT) return;
            if (action == AnchorSequence.Action.COMPLETE || action == AnchorSequence.Action.ABORT) {
                if (action == AnchorSequence.Action.COMPLETE && spamPlace.getValue()
                        && state.canBeReplaced() && !miningBlocked(activePos)) {
                    repeatPos = activePos.immutable();
                    repeatUntil = tick + Math.max(confirmTicks.getValue(), delay.getValue() + 1);
                }
                activePos = null;
                sequence.reset();
                lastBurstTick = tick;
                if (repeatPos != null) continue;
                return;
            }
            if (!AnchorTiming.ready(tick, lastActionTick, actionDelay(action))) {
                stageDiagnostics.blocked("stageDelay"); return;
            }
            if (miningBlocked(activePos)) {
                stageDiagnostics.blocked("mining"); abandonActive(); return;
            }
            if (targets == null) targets = collectTargets();
            if (self == null) self = AnchorDamageUtil.Target.of(mc.player);
            Vec3 center = Vec3.atCenterOf(activePos);
            float damage = freshCandidate == null ? score(targets, center, activePos) : freshCandidate.damage;
            float local = selfDamage(self, center, activePos);
            if (targets.isEmpty() || !safeDamage(damage, local)
                    || mc.player.getEyePosition().distanceToSqr(center) > placeRange.getValue() * placeRange.getValue()) {
                stageDiagnostics.blocked("safety"); abandonActive(); return;
            }
            if (mc.level.environmentAttributes().getValue(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS, activePos)
                    || !canSupply(action, anchor ? state.getValue(RespawnAnchorBlock.CHARGE) : 0)) {
                stageDiagnostics.blocked("materialOrDimension"); abandonActive(); return;
            }
            if (perform(action, activePos)) {
                sequence.sent(action, tick);
                stageDiagnostics.submitted(action.name(), tick);
                lastActionTick = tick;
                actionsThisTick++;
                lastDamage = damage;
                if (render.getValue()) { renderPos = activePos; renderStart = System.currentTimeMillis(); }
            } else stageDiagnostics.blocked("actionDeferred");
            return;
        }
    }

    private void abandonActive() {
        stageDiagnostics.reset();
        activePos = null;
        repeatPos = null;
        sequence.reset();
        lastDamage = 0f;
    }

    private int actionDelay(AnchorSequence.Action action) {
        return switch (action) {
            case PLACE -> placeDelay.getValue(); case CHARGE -> chargeDelay.getValue();
            case DETONATE -> detonateDelay.getValue(); default -> 0;
        };
    }

    private boolean miningBlocked(BlockPos pos) {
        if (!detectMining.getValue()) return false;
        GuangxiMineModule mine = GuizhouWare.moduleManager.getModuleByClass(GuangxiMineModule.class);
        AutoMineModule auto = GuizhouWare.moduleManager.getModuleByClass(AutoMineModule.class);
        return mine != null && mine.isEnabled() && mine.isMining(pos) || auto != null && auto.reservesPlacement(pos);
    }

    private boolean safeDamage(float damage, float self) {
        return Float.isFinite(damage) && Float.isFinite(self) && damage > 0 && damage >= minDamage.getValue()
                && self <= maxSelfDamage.getValue()
                && (self <= 0 || self + 1.5f < mc.player.getHealth() + mc.player.getAbsorptionAmount());
    }

    private Candidate repeatCandidate(BlockPos pos, List<AnchorDamageUtil.Target> targets, AnchorDamageUtil.Target self) {
        if (!usableCandidate(pos)) return null;
        Vec3 center = Vec3.atCenterOf(pos);
        float damage = score(targets, center, pos), local = selfDamage(self, center, pos);
        return safeDamage(damage, local) ? new Candidate(pos, damage, local) : null;
    }

    private SwapMode swapMode(AnchorSequence.Action action) {
        StageSwap stage = switch (action) {
            case PLACE -> placeSwitch.getValue(); case CHARGE -> chargeSwitch.getValue();
            default -> explodeSwitch.getValue();
        };
        return (stage == StageSwap.INVENTORY || stage == StageSwap.INHERIT && autoSwap.getValue() == AnchorSwap.INVENTORY)
                ? SwapMode.ALTSILENT : SwapMode.SILENT;
    }

    private Result itemFor(AnchorSequence.Action action) {
        return InventoryUtil.find(stack -> switch (action) {
            case PLACE -> stack.is(Items.RESPAWN_ANCHOR);
            case CHARGE -> stack.is(Items.GLOWSTONE);

            default -> false;
        }, swapMode(action).scope());
    }

    private Result detonationSlot(int excluded) {
        int size = swapMode(AnchorSequence.Action.DETONATE) == SwapMode.ALTSILENT ? 36 : 9;
        for (int slot = 0; slot < size; slot++) {
            if (slot == excluded) continue;
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.is(Items.GLOWSTONE) || stack.isEmpty() && slot >= 9) continue;
            return new Result(slot, stack, slot < 9 ? ResultType.HOTBAR : ResultType.INVENTORY);
        }
        return null;
    }

    private int offhandClearSlot() {
        int size = swapMode(AnchorSequence.Action.DETONATE) == SwapMode.ALTSILENT ? 36 : 9;
        for (int slot = 0; slot < size; slot++) {
            if (!mc.player.getInventory().getItem(slot).is(Items.GLOWSTONE) && detonationSlot(slot) != null) return slot;
        }
        return -1;
    }

    private boolean canSupply(AnchorSequence.Action action, int charges) {
        if (action != AnchorSequence.Action.DETONATE) return itemFor(action).found();
        if (charges >= 4) return true;
        return mc.player.getOffhandItem().is(Items.GLOWSTONE) ? offhandClearSlot() >= 0 : detonationSlot(-1) != null;
    }

    private boolean detonate(BlockHitResult hit, int charges) {
        // In 1.21.11 only MAIN_HAND reaches useWithoutItem. A glowstone offhand defers
        // that main-hand interaction while the anchor can still accept a charge.
        if (charges >= 4) return sendUseItemOn(hit, InteractionHand.MAIN_HAND);
        int cleared = mc.player.getOffhandItem().is(Items.GLOWSTONE) ? offhandClearSlot() : -1;
        if (mc.player.getOffhandItem().is(Items.GLOWSTONE) && cleared < 0) return false;
        if (cleared >= 0) InventoryUtil.swapToOffhand(cleared);
        try {
            if (mc.player.getOffhandItem().is(Items.GLOWSTONE)) return false;
            Result item = detonationSlot(cleared);
            if (item == null) return false;
            boolean[] sent = {false};
            Runnable action = () -> sent[0] = sendUseItemOn(hit, InteractionHand.MAIN_HAND);
            boolean swapped = item.stack().isEmpty()
                    ? GuizhouWare.swapManager.withEmptySlot(item.slot(), SwapPriority.CRYSTAL, action)
                    : GuizhouWare.swapManager.withSwap(item, swapMode(AnchorSequence.Action.DETONATE), SwapPriority.CRYSTAL, action);
            return swapped && sent[0];
        } finally {
            if (cleared >= 0) InventoryUtil.swapToOffhand(cleared);
        }
    }

    private boolean perform(AnchorSequence.Action action, BlockPos pos) {
        ClientPacketListener conn = mc.getConnection();
        if (conn == null || miningBlocked(pos)) return false;
        BlockHitResult hit = action == AnchorSequence.Action.PLACE
                ? GuizhouWare.placementManager.preparePlaceHit(pos, null, airPlace.getValue(), placeRange.getValue(), wallRange.getValue())
                : GuizhouWare.placementManager.prepareInteractionHit(pos, null, mc.player.getEyePosition(),
                        placeRange.getValue(), wallRange.getValue(), false);
        if (hit == null || action == AnchorSequence.Action.PLACE
                && (!PlaceUtil.canPlace(pos, placeRange.getValue()) || miningBlocked(hit.getBlockPos()))) return false;
        if (mc.player.getEyePosition().distanceToSqr(hit.getLocation()) > placeRange.getValue() * placeRange.getValue()) return false;
        if (rotate.getValue()) {
            float[] angles = MathUtil.calcAngle(mc.player.getEyePosition(), hit.getLocation());
            if (!GuizhouWare.rotationManager.trySubmit(new RotationRequest(
                    "AutoAnchor", 62, angles[0], angles[1], RotationRequest.Mode.SILENT))) return false;
        }
        if (action == AnchorSequence.Action.PLACE) AutoTotemModule.holdForCombat(Items.RESPAWN_ANCHOR);
        if (action == AnchorSequence.Action.DETONATE)
            return detonate(hit, mc.level.getBlockState(pos).getValue(RespawnAnchorBlock.CHARGE));
        Result item = itemFor(action);
        if (!item.found()) return false;
        boolean[] sent = {false};
        boolean swapped = GuizhouWare.swapManager.withSwap(item, swapMode(action), SwapPriority.CRYSTAL,
                () -> sent[0] = sendUseItemOn(hit, item.hand()));
        if (swapped && sent[0] && action == AnchorSequence.Action.PLACE)
            GuizhouWare.placementManager.notePlacement(pos);
        return swapped && sent[0];
    }

    private boolean sendUseItemOn(BlockHitResult hit, InteractionHand hand) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return false;
        boolean sent = GuizhouWare.placementManager.sendUseItemOnDirect(hit, hand,
                mc.player.getEyePosition(), placeRange.getValue(), wallRange.getValue());
        if (sent) mc.getConnection().send(new ServerboundSwingPacket(hand));
        return sent;
    }

    private Candidate selectCandidate(List<AnchorDamageUtil.Target> targets, AnchorDamageUtil.Target self) {
        int tick = mc.player.tickCount;
        if (!asyncCompute.getValue() || targets.isEmpty()) {
            if (planning != null) { planner.invalidate(); planning = null; planningGeneration++; }
            return targets.isEmpty() ? null : findBest(targets, self);
        }
        if (planning != null) {
            PlanningTicket ticket = planning;
            var completed = planner.poll();
            if (completed != null && completed.input() == ticket.input()) {
                planning = null;
                boolean valid = !completed.failed() && completed.output() != null
                        && ticket.input().generation() == planningGeneration
                        && tick >= ticket.input().tick() && tick - ticket.input().tick() <= asyncMaxAge.getValue()
                        && ticket.settings() == planningSettings()
                        && ticket.input().difficulty() == mc.level.getDifficulty().getId()
                        && CombatSnapshotValidation.compatible(ticket.input().self(), CombatSnapshotService.get(mc.player), .75)
                        && CombatSnapshotValidation.compatibleTargets(ticket.input().targets(), plannerTargets(targets), .75);
                long validateTimer = ClientDiagnostics.startTimer();
                try { valid &= valid && ticket.captured().stillValid(snapshotBudgetNanos()); }
                finally { ClientDiagnostics.endTimer("combat.AutoAnchor.async.validate", validateTimer); }
                if (valid) {
                    AnchorPlanner.Result result = completed.output();
                    ClientDiagnostics.count("combat.AutoAnchor.async.rays", result.rays());
                    ClientDiagnostics.count("combat.AutoAnchor.async.evaluated", result.evaluated());
                    if (result.candidate() == null) return null;
                    var candidate = result.candidate();
                    BlockPos pos = new BlockPos(candidate.x(), candidate.y(), candidate.z());
                    // Worker estimates never authorize an action: score the winner again against live terrain.
                    if (usableCandidate(pos)) {
                        Vec3 center = Vec3.atCenterOf(pos);
                        float damage = score(targets, center, pos);
                        float local = selfDamage(self, center, pos);
                        if (damage > 0 && damage >= minDamage.getValue() && local <= maxSelfDamage.getValue()
                                && (local <= 0 || local + 1.5f < mc.player.getHealth() + mc.player.getAbsorptionAmount())) {
                            ClientDiagnostics.count("combat.AutoAnchor.async.applied", 1);
                            return new Candidate(pos, damage, local);
                        }
                    }
                }
                discardPlanning(tick);
                ClientDiagnostics.count("combat.AutoAnchor.async.discard", 1);
                return findBest(targets, self);
            }
            if (tick >= ticket.input().tick() && tick - ticket.input().tick() <= asyncMaxAge.getValue()) {
                ClientDiagnostics.count("combat.AutoAnchor.async.wait", 1);
                return null;
            }
            discardPlanning(tick);
            ClientDiagnostics.count("combat.AutoAnchor.async.expired", 1);
            return findBest(targets, self);
        }
        if (tick < syncUntilTick || capturedTick == tick) return findBest(targets, self);
        capturedTick = tick;
        long timer = ClientDiagnostics.startTimer();
        long captureStart = System.nanoTime();
        try {
            List<AnchorPlanner.Candidate> candidates = plannerCandidates(captureStart + snapshotBudgetNanos());
            if (candidates != null && candidates.isEmpty()) return null;
            List<CombatantSnapshot> snapshots = plannerTargets(targets);
            if (snapshots.isEmpty()) return null;
            CombatantSnapshot player = CombatSnapshotService.get(mc.player);
            java.util.Map<Integer, List<CombatantSnapshot>> predictions = new java.util.HashMap<>();
            java.util.Map<Integer, CombatantSnapshot> selfSnapshots = new java.util.HashMap<>();
            List<CombatantSnapshot> captureBounds = new ArrayList<>(snapshots);
            if (candidates != null) for (var candidate : candidates) {
                int ticks = candidate.predictionTicks();
                if (ticks <= 0 || predictions.containsKey(ticks)) continue;
                var predicted = plannerTargets(predictedTargets(targets, ticks));
                predictions.put(ticks, predicted);
                captureBounds.addAll(predicted);
                if (selfExtrapolate.getValue()) {
                    var projected = selfPredictions.computeIfAbsent(ticks, ignored -> predict(self, ticks));
                    Vec3 position = projected.pos();
                    var snapshot = player.at(position.x, position.y, position.z);
                    selfSnapshots.put(ticks, snapshot);
                    captureBounds.add(snapshot);
                }
            }
            long remaining = snapshotBudgetNanos() - (System.nanoTime() - captureStart);
            var captured = candidates == null || snapshots.size() > 32 || remaining <= 0 ? null
                    : CombatTerrainCapture.capture(candidates, captureBounds, player, remaining);
            if (captured != null) {
                AnchorPlanner.Input input = new AnchorPlanner.Input(planningGeneration, tick, mc.level.getDifficulty().getId(),
                        assumeBestArmor.getValue(), minDamage.getValue(), maxSelfDamage.getValue(), player, snapshots, candidates,
                        captured.terrain(), predictions, selfSnapshots);
                planning = new PlanningTicket(input, captured, planningSettings());
                planner.submit(input);
                ClientDiagnostics.count("combat.AutoAnchor.async.submitted", 1);
                ClientDiagnostics.count("combat.AutoAnchor.async.captureCells", captured.cells());
                return null;
            }
            ClientDiagnostics.count("combat.AutoAnchor.async.captureFallback", 1);
            syncUntilTick = tick + 20;
        } finally { ClientDiagnostics.endTimer("combat.AutoAnchor.async.capture", timer); }
        return findBest(targets, self);
    }

    private void discardPlanning(int tick) {
        planning = null;
        planningGeneration++;
        planner.invalidate();
        // Continually moving targets or expensive scenes should not pay capture cost every tick.
        syncUntilTick = tick + 20;
    }

    private long snapshotBudgetNanos() { return (long)(snapshotBudget.getValue() * 1_000_000); }
    private int planningSettings() {
        return java.util.Objects.hash(placeRange.getValue(), wallRange.getValue(), airPlace.getValue(), minDamage.getValue(), maxSelfDamage.getValue(),
                assumeBestArmor.getValue(), damageSync.getValue(), autoSwap.getValue(), detectMining.getValue(),
                placeSwitch.getValue(), chargeSwitch.getValue(), explodeSwitch.getValue(),
                extrapolate.getValue(), selfExtrapolate.getValue(), extrapolationTicks.getValue(),
                placeDelay.getValue(), chargeDelay.getValue(), detonateDelay.getValue());
    }

    private List<CombatantSnapshot> plannerTargets(List<AnchorDamageUtil.Target> targets) {
        List<CombatantSnapshot> out = new ArrayList<>();
        for (var target : targets) {
            if (damageSync.getValue() && DamageSyncTracker.shouldHold(target.entity())) continue;
            Vec3 position = target.pos();
            out.add(CombatSnapshotService.get(target.entity()).at(position.x, position.y, position.z));
        }
        return List.copyOf(out);
    }

    private List<AnchorPlanner.Candidate> plannerCandidates(long deadline) {
        List<AnchorPlanner.Candidate> out = new ArrayList<>();
        BlockPos origin = mc.player.blockPosition();
        int range = (int)Math.ceil(placeRange.getValue());
        Vec3 eye = mc.player.getEyePosition();
        double rangeSquared = placeRange.getValue() * placeRange.getValue();
        // Inventory is stable during this main-thread pass; do not rescan every slot per candidate.
        boolean place = canSupply(AnchorSequence.Action.PLACE, 0);
        boolean charge = canSupply(AnchorSequence.Action.CHARGE, 0);
        boolean detonate = canSupply(AnchorSequence.Action.DETONATE, 1);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int visited = 0;
        for (int x = -range; x <= range; x++) for (int y = -range; y <= range; y++) for (int z = -range; z <= range; z++) {
            if ((++visited & 31) == 0 && System.nanoTime() > deadline) return null;
            if (x * x + y * y + z * z > range * range) continue;
            cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
            if (eye.distanceToSqr(Vec3.atCenterOf(cursor)) > rangeSquared) continue;
            if (miningBlocked(cursor)) continue;
            BlockState state = mc.level.getBlockState(cursor);
            if (state.is(Blocks.RESPAWN_ANCHOR)) {
                int charges = state.getValue(RespawnAnchorBlock.CHARGE);
                if (charges == 0 && !charge || charges > 0 && charges < 4 && !detonate) continue;
            } else if (!place || !charge || !detonate || !state.canBeReplaced() || !PlaceUtil.canPlace(cursor, placeRange.getValue())) continue;
            if (mc.level.environmentAttributes().getValue(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS, cursor)) continue;
            out.add(new AnchorPlanner.Candidate(cursor.getX(), cursor.getY(), cursor.getZ(), horizon(cursor)));
        }
        ClientDiagnostics.count("combat.AutoAnchor.async.candidates", out.size());
        return out;
    }

    private boolean usableCandidate(BlockPos pos) {
        if (miningBlocked(pos)) return false;
        if (mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > placeRange.getValue() * placeRange.getValue()
                || mc.level.environmentAttributes().getValue(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS, pos)) return false;
        BlockState state = mc.level.getBlockState(pos);
        if (!hasInteraction(pos, state.is(Blocks.RESPAWN_ANCHOR))) return false;
        if (state.is(Blocks.RESPAWN_ANCHOR)) {
            int charges = state.getValue(RespawnAnchorBlock.CHARGE);
            return canSupply(charges > 0 ? AnchorSequence.Action.DETONATE : AnchorSequence.Action.CHARGE, charges);
        }
        return state.canBeReplaced() && canSupply(AnchorSequence.Action.PLACE, 0)
                && canSupply(AnchorSequence.Action.CHARGE, 0) && canSupply(AnchorSequence.Action.DETONATE, 1)
                && PlaceUtil.canPlace(pos, placeRange.getValue());
    }

    private boolean hasInteraction(BlockPos pos, boolean existing) {
        BlockHitResult hit = existing ? GuizhouWare.placementManager.prepareInteractionHit(pos, null, mc.player.getEyePosition(),
                placeRange.getValue(), wallRange.getValue(), false)
                : GuizhouWare.placementManager.preparePlaceHit(pos, null, airPlace.getValue(), placeRange.getValue(), wallRange.getValue());
        return hit != null && (existing || !miningBlocked(hit.getBlockPos()));
    }

    private Candidate findBest(List<AnchorDamageUtil.Target> targets, AnchorDamageUtil.Target selfTarget) {
        if (targets.isEmpty()) return null;
        Vec3 eye = mc.player.getEyePosition(1.0f);
        BlockPos playerPos = mc.player.blockPosition();
        double maxSelf = maxSelfDamage.getValue();
        double min = minDamage.getValue();
        double rangeSq = placeRange.getValue() * placeRange.getValue();
        float playerHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        int r = (int) Math.ceil(placeRange.getValue());
        int rr = r * r;

        boolean canPlaceAnchor = canSupply(AnchorSequence.Action.PLACE, 0);
        boolean canChargeAnchor = canSupply(AnchorSequence.Action.CHARGE, 0);
        boolean canDetonateAnchor = canSupply(AnchorSequence.Action.DETONATE, 1);
        Candidate best = null;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = -r; x <= r; x++) {
            int xx = x * x;
            for (int y = -r; y <= r; y++) {
                int xxyy = xx + y * y;
                for (int z = -r; z <= r; z++) {
                    if (xxyy + z * z > rr) continue;
                    cursor.set(playerPos.getX() + x, playerPos.getY() + y, playerPos.getZ() + z);
                    double cx = cursor.getX() + 0.5, cy = cursor.getY() + 0.5, cz = cursor.getZ() + 0.5;
                    double ex = eye.x - cx, ey = eye.y - cy, ez = eye.z - cz;
                    if (ex * ex + ey * ey + ez * ez > rangeSq) continue;
                    if (!nearAnyTarget(targets, cx, cy, cz)) continue;
                    Vec3 center = new Vec3(cx, cy, cz);

                    if (miningBlocked(cursor)) continue;
                    BlockState state = mc.level.getBlockState(cursor);
                    boolean existing = state.is(Blocks.RESPAWN_ANCHOR);
                    if (!existing && (!state.canBeReplaced() || !canPlaceAnchor || !canChargeAnchor || !canDetonateAnchor)) continue;
                    if (existing) {
                        int charges = state.getValue(RespawnAnchorBlock.CHARGE);
                        if (charges == 0 && !canChargeAnchor || charges > 0 && charges < 4 && !canDetonateAnchor) continue;
                    }
                    if (mc.level.environmentAttributes().getValue(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS, cursor)) continue;
                    BlockPos pos = null;
                    // Sum upper bounds using the same group-damage scoring as the exact pass.
                    float upper = 0f;
                    for (AnchorDamageUtil.Target target : predictedTargets(targets, horizon(cursor))) {
                        if (damageSync.getValue() && DamageSyncTracker.shouldHold(target.entity())) continue;
                        upper += AnchorDamageUtil.maxDamage(target, center);
                    }
                    if (upper < min || best != null && upper <= best.damage) continue;

                    if (existing) {
                        pos = cursor.immutable();
                    } else {
                        if (!state.canBeReplaced()) continue;
                        if (!PlaceUtil.canPlace(cursor, placeRange.getValue())) continue;
                        pos = cursor.immutable();
                    }

                    float total = score(targets, center, pos);
                    if (total <= 0f || total < min || best != null && total <= best.damage) continue;
                    float self = selfDamage(selfTarget, center, pos);
                    if (self > maxSelf) continue;

                    if (self > 0f && self + 1.5f >= playerHealth) continue;

                    if (hasInteraction(pos, existing)) best = new Candidate(pos, total, self);
                }
            }
        }
        return best;
    }

    private List<AnchorDamageUtil.Target> collectTargets() {
        TargetsModule targets = GuizhouWare.moduleManager.getModuleByClass(TargetsModule.class);
        List<AnchorDamageUtil.Target> out = new ArrayList<>();
        AABB area = mc.player.getBoundingBox().inflate(placeRange.getValue() + AnchorDamageUtil.DIAMETER);
        for (Entity e : mc.level.getEntities(mc.player, area)) {
            if (!(e instanceof LivingEntity living)) continue;
            if (living.isDeadOrDying()) continue;
            if (targets != null && !targets.isValidPlayerTarget(e)) continue;
            out.add(AnchorDamageUtil.Target.of(living, assumeBestArmor.getValue()));
        }
        return out;
    }

    private static boolean nearAnyTarget(List<AnchorDamageUtil.Target> targets, double x, double y, double z) {
        for (int i = 0, n = targets.size(); i < n; i++) {
            if (targets.get(i).pos().distanceToSqr(x, y, z) <= AnchorDamageUtil.DIAMETER * AnchorDamageUtil.DIAMETER) return true;
        }
        return false;
    }

    private float score(List<AnchorDamageUtil.Target> targets, Vec3 center, BlockPos pos) {
        float total = 0f;
        for (AnchorDamageUtil.Target target : predictedTargets(targets, horizon(pos))) {
            if (damageSync.getValue() && DamageSyncTracker.shouldHold(target.entity())) continue;
            total += AnchorDamageUtil.damage(target, center, pos);
        }
        return total;
    }

    private int horizon(BlockPos pos) {
        if (!extrapolate.getValue() && !selfExtrapolate.getValue()) return 0;
        BlockState state = mc.level.getBlockState(pos);
        boolean anchor = state.is(Blocks.RESPAWN_ANCHOR);
        return timing.horizon(anchor, anchor ? state.getValue(RespawnAnchorBlock.CHARGE) : 0,
                extrapolationTicks.getValue(), placeDelay.getValue(), chargeDelay.getValue(), detonateDelay.getValue());
    }

    private List<Vec3> predictionPath(AnchorDamageUtil.Target target, int ticks) {
        var entity = target.entity();
        Vec3 motion = entity == mc.player ? entity.getDeltaMovement()
                : entity.position().subtract(entity.xo, entity.yo, entity.zo);
        if (entity == mc.player && entity.isFallFlying()) {
            var flight = GuizhouWare.moduleManager.getModuleByClass(dev.guizhou.features.modules.movement.GuangxiElytraModule.class);
            Vec3 forecast = flight == null ? null : flight.forecastCombatVelocity();
            if (forecast != null) motion = forecast;
        }
        return dev.guizhou.util.combat.CrystalMotionPrediction.trace(target.pos(), target.box(), motion, ticks,
                !entity.onGround() && !entity.isFallFlying() && !entity.isNoGravity(),
                box -> mc.level.noCollision(entity, box));
    }

    private AnchorDamageUtil.Target predict(AnchorDamageUtil.Target target, int ticks) {
        if (ticks <= 0) return target;
        return target.at(predictionPath(target, ticks).getLast());
    }

    private List<AnchorDamageUtil.Target> predictedTargets(List<AnchorDamageUtil.Target> targets, int ticks) {
        if (!extrapolate.getValue() || ticks <= 0) return targets;
        return targetPredictions.computeIfAbsent(ticks,
                ignored -> targets.stream().map(target -> predict(target, ticks)).toList());
    }

    private float selfDamage(AnchorDamageUtil.Target self, Vec3 center, BlockPos pos) {
        float current = AnchorDamageUtil.damage(self, center, pos);
        int ticks = horizon(pos);
        if (!selfExtrapolate.getValue() || ticks <= 0) return current;
        for (Vec3 position : selfPaths.computeIfAbsent(ticks, ignored -> predictionPath(self, ticks))) {
            current = Math.max(current, AnchorDamageUtil.damage(self.at(position), center, pos));
            // Rejected candidates need no further exposure work.
            if (current > maxSelfDamage.getValue() || current > 0 && current + 1.5f >= mc.player.getHealth() + mc.player.getAbsorptionAmount()) break;
        }
        return current;
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (!render.getValue() || renderPos == null) return;
        long age = System.currentTimeMillis() - renderStart;
        double fadeMs = fadeTime.getValue() * 1000.0;
        if (age > fadeMs) {
            renderPos = null;
            return;
        }
        double t = age / fadeMs;
        Color fc = fillColor.getValue();
        Color oc = outlineColor.getValue();
        RenderUtil.drawBoxFilled(event.getMatrix(), renderPos,
                withAlpha(fc, (int) (fc.getAlpha() * (1 - t))));
        RenderUtil.drawBox(event.getMatrix(), renderPos,
                withAlpha(oc, (int) (oc.getAlpha() * (1 - t))), 1.0f);
    }

    private static Color withAlpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Mth.clamp(a, 0, 255));
    }

    @Override
    public String getDisplayInfo() {
        return lastDamage > 0 ? String.format("%.1f", lastDamage) : null;
    }

    private record Candidate(BlockPos pos, float damage, float selfDamage) {}
}
