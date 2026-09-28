package dev.guizhou.features.modules.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.entity.player.UpdateWalkingPlayerEvent;
import dev.guizhou.event.Stage;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.client.TargetsModule;
import dev.guizhou.features.modules.movement.GuangxiElytraModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.RotationRequest;
import dev.guizhou.util.MathUtil;
import dev.guizhou.util.elytra.AerialCombatPolicy;
import dev.guizhou.util.elytra.AerialIntercept;
import dev.guizhou.util.elytra.AerialDuelPredictor;
import dev.guizhou.util.elytra.AsyncFlightRoute;
import dev.guizhou.util.elytra.CombatHitWindow;
import dev.guizhou.util.elytra.EntityAim;
import dev.guizhou.util.elytra.ElytraWeaponController;
import dev.guizhou.util.elytra.ElytraPhysics;
import dev.guizhou.util.elytra.FlightVelocity;
import dev.guizhou.util.elytra.FlightRoutePlanner;
import dev.guizhou.util.elytra.FlightPlanningTask;
import dev.guizhou.util.elytra.MaceDiveController;
import dev.guizhou.util.elytra.MaceDiveState;
import dev.guizhou.util.elytra.MacePassTracker;
import dev.guizhou.util.elytra.MaceApproach;
import dev.guizhou.util.elytra.SpearAimSweep;
import dev.guizhou.util.elytra.SpearCombat;
import dev.guizhou.util.elytra.SpearApproach;
import dev.guizhou.util.elytra.TargetMotionTracker;
import dev.guizhou.util.diagnostics.ClientDiagnostics;
import dev.guizhou.util.player.EatUtil;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Elytra pursuit movement and mace attack timing.
 *
 * Four modes:
 * - FOLLOW: fly straight at the target (heading length = distance → natural
 *   far-fast / near-slow convergence)
 * - MACE: pull up above the target, dive through with mace timing
 *   (state machine None → PullUp → Follow → WaitAttack; hit feedback re-pulls)
 * - SPEAR: approach to spear distance, sidestep charged-spear raycasts
 *   (anti-spear), pull over and reset
 * - MXS: confirmed mace hit, then a bounded spear pass before returning to mace
 *
 * Speed seam: GuangxiElytra calls {@link #onElytra} every movement tick with
 * a FlightVelocity; the active behaviour may overwrite the vector. Its LENGTH
 * is the desired speed — a short vector means slow down.
 */
public class GuangxiElytraChaseModule extends Module {

    public enum Mode { FOLLOW, MACE, SPEAR, MXS }
    public enum ThreatMode { ALL_PLAYERS, ADAPTIVE }

    /** Observed motion for the status display, not a player classification. */
    public enum MotionClass { RUSHING, FLEEING, UNKNOWN, NORMAL, STILL }

    private static GuangxiElytraChaseModule INSTANCE;

    // ---- General ----
    private final Setting<Mode> mode = mode("Mode", Mode.FOLLOW).setPage("General");
    private final Setting<Integer> range = num("Range", 80, 8, 256).setPage("General");
    private final Setting<Boolean> playerOnly = bool("PlayerOnly", true).setPage("General");
    private final Setting<Boolean> dynamicTarget = bool("DynamicTarget", false).setPage("General");
    private final Setting<Boolean> autoControlElytra = bool("AutoControlElytra", true).setPage("General")
            .described("有目标时由空战接管航向；手动已开启的 GZBoost 在空战让出控制后继续工作。");
    private final Setting<Boolean> autoWeapon = bool("AutoWeapon", true).setPage("General");
    private final Setting<Boolean> autoSpearUse = bool("AutoSpearUse", true).setPage("General");
    private final Setting<Boolean> adaptiveCombat = bool("AdaptiveCombat", true).setPage("General");
    private final Setting<ThreatMode> threatMode = mode("ThreatMode", ThreatMode.ALL_PLAYERS).setPage("General");
    private final Setting<Boolean> avoidObstacles = bool("AvoidObstacles", true).setPage("General");
    private final Setting<Boolean> onlyWhenNoWasd = bool("OnlyWhenNoWasd", false).setPage("General")
            .described("按 WASD 时暂停追击与武器控制，交回手动飞行；适合与 GZBoost 配合赶路。");
    private final Setting<Double> combatRange = num("CombatRange", 10.0, 1.0, 64.0).setPage("General");
    private final Setting<Double> spearRange = num("SpearRange", 11.0, 1.0, 64.0).setPage("General");
    private final Setting<Double> followGroundHeightExtra = num("FollowGroundHeightExtra", 2.0, 0.0, 10.0).setPage("General");

    // ---- Advanced ----
    private final Setting<Boolean> maceUseExtraAttack = bool("MaceUseExtraAttack", true).setPage("Advanced")
            .described("手动下砸模式允许额外普攻；AutoMaceDive 开启时保留真正重击的攻击窗口。");
    private final Setting<Boolean> autoMaceDive = bool("AutoMaceDive", true).setPage("Mace");
    private final Setting<Boolean> macePreSwap = bool("MacePreSwap", false).setPage("Mace");
    private final Setting<Integer> macePreSwapDelay = num("MacePreSwapDelay", 2, 0, 20).setPage("Mace");
    private final Setting<Double> macePreSwapDistancePerTick = num("MacePreSwapDistancePerTick", 0.0, 0.0, 2.0).setPage("Mace");
    private final Setting<Integer> maceSwapMinDelay = num("MaceSwapMinDelay", 2, 0, 40).setPage("Mace");
    private final Setting<Boolean> maceAdaptive = bool("MaceAdaptive", true).setPage("Advanced");
    private final Setting<Boolean> maceTerminal = bool("MaceTerminal", true).setPage("Advanced");
    private final Setting<Double> maceHeight = num("MaceHeight", 10.0, 1.0, 64.0).setPage("Advanced");
    private final Setting<Double> maceMinFallDistance = num("MaceMinFallDistance", 1.5, 0.0, 20.0).setPage("Advanced");
    private final Setting<Double> maceCustomPullUpAngle = num("MaceCustomPullUpAngle", 0.0, 0.0, 89.0).setPage("Advanced");
    private final Setting<Double> spearAttackDistance = num("SpearAttackDistance", 8.0, 1.0, 64.0).setPage("Advanced");
    private final Setting<Double> spearPullOverDistance = num("SpearPullOverDistance", 8.0, 1.0, 64.0).setPage("Advanced");
    private final Setting<Double> spearNoEntryDistance = num("SpearNoEntryDistance", 0.0, 0.0, 20.0).setPage("Advanced");
    private final Setting<Boolean> spearAntiSpear = bool("SpearAntiSpear", true).setPage("Advanced");
    private final Setting<Double> spearAntiSpearExtraDistance = num("SpearAntiSpearExtraDistance", 0.0, 0.0, 10.0).setPage("Advanced");
    private final Setting<Double> flyAntiSpear = num("FlyAntiSpear", 1.0, 0.0, 5.0).setPage("Advanced");
    private final Setting<Integer> mxsMaceHitWaitTicks = num("MxsMaceHitWaitTicks", 1, 0, 40).setPage("Advanced");
    private final Setting<Double> mxsSpearSwitchMaceHeight = num("MxsSpearSwitchMaceHeight", 0.0, 0.0, 64.0).setPage("Advanced");
    private final Setting<Integer> mxsSpearRunTicks = num("MxsSpearRunTicks", 60, 10, 200).setPage("Advanced");

    // target state
    private volatile Entity target;
    private Entity lastTarget;
    private MotionClass targetMotion = MotionClass.NORMAL;
    private boolean targetGrounded;
    private final TargetMotionTracker targetHistory = new TargetMotionTracker();
    private ClientLevel trackedLevel;
    private LocalPlayer trackedPlayer;
    private volatile long session;
    private int latencyTicks = 1;
    private int confirmationTicks = 6;
    private int spearSweepPhase;
    private final SpearApproach.CommitWindow spearCommit = new SpearApproach.CommitWindow();
    private int lastAttackTick = Integer.MIN_VALUE / 2;
    private final CombatHitWindow maceFeedback = new CombatHitWindow();
    private final ElytraWeaponController weapons = new ElytraWeaponController();
    private final FlightRoutePlanner flightRoute = new FlightRoutePlanner();
    private final AsyncFlightRoute parallelRoute = new AsyncFlightRoute();
    private final FlightPlanningTask<DuelRequest, AerialDuelPredictor.Output> duelPlanner =
            new FlightPlanningTask<>(request -> AerialDuelPredictor.predict(request.input()));
    private AerialDuelPredictor.Output duelPrediction;
    private DuelRequest acceptedDuel;
    private FlightPlanningTask.Completed<DuelRequest, AerialDuelPredictor.Output> lastCountedPrediction;
    private long predictionPhase;
    private boolean planningEnabled;
    private RouteContext routeContext;
    private boolean routeAvoiding;
    private final MaceDiveController maceDive = new MaceDiveController();
    private volatile boolean sendingAttack;
    private PacketEvent.Send pendingAttack;
    private PacketEvent.Send pendingRotation;
    private float sentYaw;
    private float sentPitch;
    private boolean sentRotationKnown;
    private volatile long attackGeneration;
    private volatile long combatPhase;
    private boolean weaponControlActive;
    private boolean controllingFlight;
    private TargetsModule targetRules;
    private final AerialCombatPolicy tactics = new AerialCombatPolicy();
    private AerialCombatPolicy.Action combatAction = AerialCombatPolicy.Action.CHASE;
    private Vec3 defensiveHeading = Vec3.ZERO;
    private long targetSinceTick;
    private long lastTargetSearchTick = Long.MIN_VALUE / 2;
    private long evadeSideUntil;
    private double evadeSide = 1.0;

    private record DuelRequest(long session, long phase, long targetMotionRevision, int tick, int targetId, Pose pose,
                               boolean wings, boolean gliding, AerialDuelPredictor.Input input) {
    }

    private record RouteContext(long phase, Pose pose, boolean wings, boolean gliding, boolean parallel,
                                double width, double height, double depth) {
    }

    // behaviour machinery
    private Behavior behavior;
    private Mode behaviorMode;

    public GuangxiElytraChaseModule() {
        super("GuangxiElytraChase", "Elytra pursuit bot: FOLLOW / MACE / SPEAR / MXS (rennaikanksmp ElytraBot port).", Category.COMBAT);
        INSTANCE = this;
    }

    public static boolean isUsingSpear(Player player) {
        return player.isUsingItem() && SpearCombat.isSpear(player.getUseItem());
    }

    public static boolean shouldKeepUsingSpear() {
        GuangxiElytraChaseModule bot = INSTANCE;
        return bot != null && bot.isEnabled() && bot.autoSpearUse.getValue()
                && !bot.paused() && bot.isValid(bot.target) && bot.spearPhase()
                && !bot.maceDive.isActive()
                && bot.weapons.isHoldingUse();
    }

    public static boolean isMaceDiveActive() {
        return INSTANCE != null && INSTANCE.maceDive.isActive();
    }

    /** Sample only on the game thread; background plans retain the numeric token. */
    public static long planningPhaseToken() {
        GuangxiElytraChaseModule bot = INSTANCE;
        if (bot == null || !bot.isEnabled()) return 0L;
        Behavior active = bot.behavior instanceof MxsBehavior mxs ? mxs.active : bot.behavior;
        int state = active instanceof MaceBehavior mace ? 16 + mace.state
                : active instanceof SpearBehavior spear ? 32 + spear.state : 0;
        long token = bot.session * 31L + bot.combatPhase;
        token = token * 31L + (bot.behaviorMode == null ? -1 : bot.behaviorMode.ordinal());
        token = token * 31L + bot.maceDive.phase().ordinal();
        token = token * 31L + bot.targetHistory.revision();
        return token * 31L + state;
    }

    public static boolean preservesMaceDescent() {
        // FOLLOW starts while powered flight is still braking or approaching.
        // Only an actual unpowered fall owns the no-restart/no-rocket barrier.
        return hasMaceDescentIntent() && !mc.player.isFallFlying();
    }

    public static boolean hasMaceDescentIntent() {
        GuangxiElytraChaseModule bot = INSTANCE;
        if (bot == null || !bot.isEnabled() || bot.paused() || !bot.isValid(bot.target)
                || bot.combatAction != AerialCombatPolicy.Action.CHASE
                || !mc.player.getMainHandItem().is(Items.MACE)
                || mc.player.onGround() || mc.player.getY() <= bot.target.getY() + 0.5) return false;
        Behavior active = bot.behavior instanceof MxsBehavior mxs ? mxs.active : bot.behavior;
        return active instanceof MaceBehavior mace && mace.state == MaceBehavior.FOLLOW;
    }

    public static boolean isWeaponControlActive() {
        GuangxiElytraChaseModule bot = INSTANCE;
        return bot != null && bot.isEnabled() && !bot.paused() && bot.isValid(bot.target)
                && (bot.weaponControlActive || mc.player.tickCount == bot.lastAttackTick);
    }

    /** Auto-use yields before weapon acquisition, so an owned shield cannot starve pursuit. */
    public static boolean isWeaponControlRequested() {
        GuangxiElytraChaseModule bot = INSTANCE;
        return isWeaponControlActive() || bot != null && bot.isEnabled() && !bot.paused()
                && bot.isValid(bot.target) && bot.mode.getValue() != Mode.FOLLOW
                && bot.combatAction == AerialCombatPolicy.Action.CHASE;
    }

    public static boolean blocksCrystalCombat() {
        GuangxiElytraChaseModule bot = INSTANCE;
        return bot != null && bot.isEnabled() && !bot.paused() && bot.isValid(bot.target)
                && (bot.weapons.isHoldingUse() || isUsingSpear(mc.player)
                || bot.maceDive.isActive()
                || mc.player.tickCount == bot.lastAttackTick
                || bot.maceFeedback.pending(bot.target.getId(), mc.player.tickCount));
    }

    private boolean spearPhase() {
        return behavior instanceof SpearBehavior
                || behavior instanceof MxsBehavior mxs && !mxs.usingMace;
    }

    private boolean paused() {
        return mc.player == null || mc.player.isDeadOrDying() || mc.screen != null
                || manualOverride() || GuizhouWare.tpsCounterService.isRubberbandRecent();
    }

    /** HUD seam: current locked target for the air-combat HUD, or null. */
    public static Entity hudTarget() {
        GuangxiElytraChaseModule m = INSTANCE;
        return m != null && m.isEnabled() && m.behavior != null ? m.target : null;
    }

    /** HUD seam: read-only view of the behavior mode (FOLLOW/MACE/SPEAR/MXS). */
    public static String hudMode() {
        GuangxiElytraChaseModule m = INSTANCE;
        return m != null && m.isEnabled() ? m.mode.getValue().name() : "OFF";
    }

    /** Observed movement only: ARMOR AIR is not a claim about the target's flight implementation. */
    public static String hudTargetMotion() {
        GuangxiElytraChaseModule bot = INSTANCE;
        return bot != null && bot.isEnabled() && bot.target != null ? bot.targetHistory.mode().label() : "OFF";
    }

    public static TargetMotionTracker.Snapshot targetMotionSnapshot() {
        GuangxiElytraChaseModule bot = INSTANCE;
        return bot != null && bot.isEnabled() && bot.target != null
                ? bot.targetHistory.snapshot(bot.target.getDeltaMovement()) : null;
    }

    /** HUD seam: one-word intent label from the live behavior state machine. */
    public static String hudIntent() {
        GuangxiElytraChaseModule m = INSTANCE;
        if (m == null || !m.isEnabled() || m.behavior == null || m.target == null) return "OFF";
        if (m.combatAction != AerialCombatPolicy.Action.CHASE) return "EVADE";
        return m.behavior.hudLabel();
    }

    /** Flight seam: GuangxiElytra calls this every movement tick. */
    public static void onElytra(FlightVelocity fv) {
        GuangxiElytraChaseModule m = INSTANCE;
        if (m == null || !m.isEnabled() || m.behavior == null || !m.isValid(m.target)) return;
        if (m.paused()) {
            m.behavior.resetHeading();
            return;
        }
        if (m.combatAction != AerialCombatPolicy.Action.CHASE) {
            double speed = Math.min(m.defensiveHeading.length(), fv.maxVelocity());
            fv.set(m.defensiveHeading.normalize().scale(speed));
        } else {
            m.behavior.inject(fv);
            fv.set(m.flankApproachVector(fv.value()));
        }
        if (m.avoidObstacles.getValue()) {
            Vec3 requested = fv.value();
            Vec3 safe = m.safeFlightVector(requested);
            if (requested.lengthSqr() > 1.0E-6 && safe.lengthSqr() < 1.0E-9) fv.requestBraking();
            else if (m.routeAvoiding) fv.requestAvoidance(safe);
            else fv.set(safe);
        }
    }

    /** True when the bot currently owns a heading (flight keeps running for it). */
    public boolean hasHeading() {
        return isEnabled() && !paused() && isValid(target)
                && behavior != null && (behavior.heading.lengthSqr() > 1.0E-9
                || defensiveHeading.lengthSqr() > 1.0E-9);
    }

    /** A grounded combat mode can request takeoff before it has a flight heading. */
    public boolean wantsFlight() {
        return isEnabled() && autoControlElytra.getValue() && !paused() && isValid(target)
                && !maceDive.isActive() && !fallingMaceWindow();
    }

    private boolean fallingMaceWindow() {
        if (combatAction != AerialCombatPolicy.Action.CHASE) return false;
        MaceBehavior mace = behavior instanceof MaceBehavior m ? m
                : behavior instanceof MxsBehavior mxs && mxs.usingMace ? mxs.mace : null;
        return mace != null && mace.fallingWindow();
    }

    /** Catch a reach window opened by this tick's movement/fall accumulation. */
    @Subscribe(priority = 100)
    private void onPostMovement(UpdateWalkingPlayerEvent event) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) { dev.guizhou.manager.HeartbeatManager.notifyDeniedOnce(); return; }
        if (event.getStage() != Stage.POST || paused() || !isValid(target)
                || combatAction != AerialCombatPolicy.Action.CHASE) return;
        Behavior active = behavior instanceof MxsBehavior mxs ? mxs.active : behavior;
        if (!(active instanceof MaceBehavior mace) || mace.state != MaceBehavior.FOLLOW) return;
        if (maceDive.isActive()) mace.tickDive();
        else mace.tryAttack(true);
    }

    private boolean manualOverride() {
        return onlyWhenNoWasd.getValue() && hasWasd();
    }

    private static boolean hasWasd() {
        return mc.options.keyUp.isDown() || mc.options.keyDown.isDown()
                || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown();
    }

    @Override
    public void onEnable() {
        resetRuntime();
        syncFlightControl();
        if ((mode.getValue() == Mode.MACE || mode.getValue() == Mode.MXS) && !autoMaceDive.getValue()) {
            dev.guizhou.features.commands.Command.sendMessage(dev.guizhou.util.lang.ClientLanguage.text("重锤自动下砸未开启：滑翔中不会产生重击。可在 Mace 页开启 AutoMaceDive；背包需有胸甲或空槽用于收翼。旧配置保留了关闭状态。"));
        }
    }

    @Override
    public void onDisable() {
        maceDive.requestRecovery();
        var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        if (flight != null) flight.releaseByChase();
        controllingFlight = false;
        resetRuntime();
    }

    private void resetRuntime() {
        spearCommit.reset();
        clearDiveAim();
        flightRoute.reset();
        invalidatePlanning();
        routeAvoiding = false;
        maceDive.requestRecovery();
        weapons.release();
        clearSpearAim();
        weaponControlActive = false;
        maceFeedback.clear();
        attackGeneration++;
        session++;
        behavior = null;
        behaviorMode = null;
        target = null;
        lastTarget = null;
        targetGrounded = false;
        targetMotion = MotionClass.NORMAL;
        targetHistory.clear();
        trackedLevel = null;
        trackedPlayer = null;
        lastAttackTick = Integer.MIN_VALUE / 2;
        latencyTicks = 1;
        confirmationTicks = 6;
        pendingAttack = null;
        pendingRotation = null;
        sentRotationKnown = false;
        tactics.reset();
        combatAction = AerialCombatPolicy.Action.CHASE;
        defensiveHeading = Vec3.ZERO;
        targetSinceTick = 0;
        lastTargetSearchTick = Long.MIN_VALUE / 2;
        evadeSideUntil = 0;
    }

    private void syncFlightControl() {
        boolean requested = autoControlElytra.getValue();
        if (requested == controllingFlight) return;
        var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        if (flight == null) return;
        if (requested) flight.enableByChase();
        else flight.releaseByChase();
        controllingFlight = requested;
    }

    private boolean parallelPlanningEnabled() {
        var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        return flight != null && flight.parallelPlanningEnabled();
    }

    private void invalidatePlanning() {
        duelPlanner.invalidate();
        duelPrediction = null;
        acceptedDuel = null;
        lastCountedPrediction = null;
        parallelRoute.reset();
        routeContext = null;
    }

    private AerialDuelPredictor.Weapon targetWeapon() {
        if (!(target instanceof LivingEntity living)) return AerialDuelPredictor.Weapon.NONE;
        var stack = living.getMainHandItem();
        if (stack.is(Items.MACE)) return AerialDuelPredictor.Weapon.MACE;
        if (SpearCombat.isSpear(stack)) return AerialDuelPredictor.Weapon.SPEAR;
        return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES)
                ? AerialDuelPredictor.Weapon.MELEE : AerialDuelPredictor.Weapon.NONE;
    }

    private void updateDuelPlanning() {
        duelPrediction = null;
        acceptedDuel = null;
        boolean enabled = parallelPlanningEnabled();
        if (planningEnabled != enabled) {
            invalidatePlanning();
            planningEnabled = enabled;
        }
        if (!enabled) return;
        TargetMotionTracker.Snapshot motion = targetHistory.snapshot(target.getDeltaMovement());
        Vec3 velocity = motion.velocity();
        boolean consistent = motion.confidence() >= 0.6;
        AerialDuelPredictor.Weapon weapon = targetWeapon();
        boolean attackActive = target instanceof Player player && (weapon == AerialDuelPredictor.Weapon.SPEAR
                ? isUsingSpear(player) && SpearCombat.isReady(player) : weapon == AerialDuelPredictor.Weapon.MACE
                || player.swinging && player.swingTime <= 2);
        double reach = target instanceof Player player
                ? weapon == AerialDuelPredictor.Weapon.SPEAR
                ? SpearCombat.maxRange(player, player.getMainHandItem()) : player.entityInteractionRange() : 3.0;
        long phase = planningPhaseToken();
        boolean wings = mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
        var done = duelPlanner.latest();
        if (done != null) {
            DuelRequest request = done.input();
            int age = mc.player.tickCount - request.tick();
            var input = request.input();
            if (request.session() == session && request.phase() == phase && request.targetId() == target.getId()
                    && request.targetMotionRevision() == motion.revision()
                    && age >= 0 && age <= 2 && request.pose() == mc.player.getPose()
                    && request.wings() == wings && request.gliding() == mc.player.isFallFlying()
                    && input.targetWeapon() == weapon && input.targetOnGround() == target.onGround()
                    && input.targetGliding() == ((LivingEntity) target).isFallFlying()
                    && input.targetAttackActive() == attackActive
                    && input.selfPosition().add(input.selfVelocity().scale(age)).distanceToSqr(mc.player.position()) <= 2.25
                    && input.targetPosition().add(input.targetVelocity().scale(age)).distanceToSqr(target.position()) <= 2.25
                    && input.selfVelocity().distanceToSqr(mc.player.getDeltaMovement()) <= 0.36
                    && input.targetVelocity().distanceToSqr(velocity) <= 0.36
                    && input.targetLook().dot(target.getLookAngle()) >= 0.85) {
                duelPrediction = done.output();
                acceptedDuel = request;
                predictionPhase = phase;
                countPrediction(done, true);
            } else {
                countPrediction(done, false);
            }
        }
        var input = new AerialDuelPredictor.Input(mc.player.position(), mc.player.getDeltaMovement(),
                mc.player.getBbWidth(), mc.player.getBbHeight(), target.position(), velocity,
                target.getEyePosition(), target.getLookAngle(), reach, weapon, target.onGround(),
                ((LivingEntity) target).isFallFlying(), attackActive, latencyTicks, consistent, List.of(), null, motion,
                copiedTargetKind(), copiedMotionTag(), copiedStrafeOffset());
        duelPlanner.submit(new DuelRequest(session, phase, motion.revision(), mc.player.tickCount, target.getId(),
                mc.player.getPose(), wings, mc.player.isFallFlying(), input));
    }

    /** Latest results may be reused for multiple ticks; diagnose each completion's first observation once. */
    private void countPrediction(FlightPlanningTask.Completed<DuelRequest, AerialDuelPredictor.Output> done,
                                 boolean accepted) {
        if (done == lastCountedPrediction) return;
        lastCountedPrediction = done;
        ClientDiagnostics.count(accepted ? "air.plan.accept" : "air.plan.stale", 1);
    }

    @Subscribe(priority = 100)
    private void onPreTick(PreTickEvent event) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) { dev.guizhou.manager.HeartbeatManager.notifyDeniedOnce(); return; }
        clearDiveAim();
if (nullCheck() || mc.player == null) {
            if (trackedLevel != null || trackedPlayer != null) resetRuntime();
            return;
        }
        if (trackedLevel != mc.level || trackedPlayer != mc.player) {
            resetRuntime();
            trackedLevel = mc.level;
            trackedPlayer = mc.player;
        }
        syncFlightControl();
        latencyTicks = readPingTicks();

        if (behavior == null || behaviorMode != mode.getValue()) {
            maceDive.requestRecovery();
            weapons.release();
            clearSpearAim();
            maceFeedback.clear();
            session++;
            behaviorMode = mode.getValue();
            behavior = switch (behaviorMode) {
                case FOLLOW -> new FollowBehavior();
                case MACE -> new MaceBehavior();
                case SPEAR -> new SpearBehavior();
                case MXS -> new MxsBehavior();
            };
            behavior.enter();
            tactics.reset();
            combatAction = AerialCombatPolicy.Action.CHASE;
            defensiveHeading = Vec3.ZERO;
        }

        if (!isValid(target)) {
            target = null;
        }
        long tick = mc.player.tickCount;
        if (target == null || dynamicTarget.getValue() && tick - lastTargetSearchTick >= 4) {
            Entity candidate = searchTarget();
            lastTargetSearchTick = tick;
            if (candidate != null && (target == null || AerialCombatPolicy.shouldSwitchTarget(true,
                    mc.player.distanceTo(target), mc.player.distanceTo(candidate), tick - targetSinceTick))) {
                target = candidate;
            }
        }
        if (target != lastTarget) {
            flightRoute.reset();
            invalidatePlanning();
            maceDive.requestRecovery();
            session++;
            targetSinceTick = tick;
            lastTarget = target;
            targetHistory.clear();
            weapons.release();
            clearSpearAim();
            maceFeedback.clear();
            behavior.enter();
        }

        classifyTarget();
        weaponControlActive = false;
        if (paused() || target == null) {
            invalidatePlanning();
            maceDive.requestRecovery();
            weapons.release();
            clearSpearAim();
            maceFeedback.clear();
            behavior.enter();
            defensiveHeading = Vec3.ZERO;
            combatAction = AerialCombatPolicy.Action.CHASE;
            tactics.reset();
            return;
        }
        updateDuelPlanning();
        if (updateDefense()) return;
        if (maceDive.isActive() && spearPhase()) {
            maceDive.requestRecovery();
            weaponControlActive = true;
            behavior.resetHeading();
            return;
        }
        behavior.tick();
    }

    private boolean updateDefense() {
        AerialCombatPolicy.Action next = AerialCombatPolicy.Action.CHASE;
        boolean maceThreat = false;
        var prediction = duelPrediction;
        if (adaptiveCombat.getValue() && behaviorMode != Mode.FOLLOW) {
            Vec3 velocity = targetHistory.predictionVelocity(target.getDeltaMovement());
            maceThreat = fallingMaceThreat(velocity);
            boolean spearThreat = target instanceof Player player
                    && SpearCombat.threatens(player, mc.player, spearAntiSpearExtraDistance.getValue(), 0.25);
            boolean predictedThreat = prediction != null && prediction.severity() >= 0.5
                    && mc.player.hasLineOfSight(target);
            boolean urgent = spearThreat || maceThreat && mc.player.distanceToSqr(target) <= 20.25
                    || predictedThreat && AerialDuelPredictor.shouldInterruptAttack(prediction, true,
                    mc.player.getHealth(), mc.player.getAbsorptionAmount(), mc.player.getMaxHealth());
            boolean preserveStrike = (realMaceHitWindow() || spearApproachWindow()) && !urgent;
            next = tactics.evaluate(new AerialCombatPolicy.ThreatInput(mc.player.tickCount,
                    policyVector(target.position().subtract(mc.player.position())),
                    policyVector(mc.player.getDeltaMovement()), policyVector(velocity),
                    spearThreat, maceThreat, mc.player.getHealth(), mc.player.getAbsorptionAmount(),
                    mc.player.getMaxHealth(), false, latencyTicks,
                    threatMode.getValue() == ThreatMode.ALL_PLAYERS && target instanceof Player),
                    predictedThreat, preserveStrike);
        } else {
            tactics.reset();
        }
        if (next != combatAction) {
            invalidatePlanning();
            maceDive.requestRecovery();
            combatPhase++;
            attackGeneration++;
            maceFeedback.clear();
            resetDefenseBehavior();
            clearSpearAim();
            combatAction = next;
        }
        defensiveHeading = Vec3.ZERO;
        if (next == AerialCombatPolicy.Action.CHASE) return false;
        clearSpearAim();
        maintainDefensiveSpearUse();
        Vec3 away = mc.player.position().subtract(target.position()).multiply(1.0, 0.0, 1.0);
        if (away.lengthSqr() < 1.0E-6) away = mc.player.getLookAngle().multiply(-1.0, 0.0, -1.0);
        if (away.lengthSqr() < 1.0E-6) away = new Vec3(1.0, 0.0, 0.0);
        away = away.normalize();
        if (next == AerialCombatPolicy.Action.RETREAT) {
            defensiveHeading = away.add(0.0, maceThreat ? 0.0 : 0.2, 0.0).normalize().scale(10.0);
        } else {
            Vec3 side = new Vec3(-away.z, 0.0, away.x);
            double probe = Math.max(1.0, mc.player.getDeltaMovement().length());
            if (mc.player.tickCount >= evadeSideUntil) {
                if (!pathClear(side.scale(evadeSide * probe)) && pathClear(side.scale(-evadeSide * probe))) {
                    evadeSide = -evadeSide;
                }
                evadeSideUntil = (long) mc.player.tickCount + 6;
            }
            defensiveHeading = side.scale(evadeSide).add(away.scale(0.35)).normalize().scale(10.0);
            if (prediction != null && prediction.severity() >= 0.5) {
                Vec3 preferred = prediction.recommendedDirection();
                if (preferred.lengthSqr() > 1.0E-6 && pathClear(preferred.scale(probe))) {
                    defensiveHeading = preferred.normalize().scale(10.0);
                }
            }
        }
        return true;
    }

    private void resetDefenseBehavior() {
        if (spearPhase()) {
            // A heading change does not invalidate the held spear. Keep the MXS weapon
            // phase too; restarting the entire MXS behavior would select its mace phase.
            behavior.resetHeading();
            if (behavior instanceof MxsBehavior mxs) mxs.spear.enter();
            else behavior.enter();
            return;
        }
        weapons.release();
        behavior.enter();
    }

    private void maintainDefensiveSpearUse() {
        if (!spearPhase() || !weapons.isHoldingUse()) return;
        var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        // Maintain only a charge we already own, including its normal expiry/manual-use
        // checks. Defense may release it for a rocket, but must not acquire a new weapon.
        prepareWeapon(true, flight == null || !flight.needsSpearBoostRefresh());
    }

    private boolean realMaceHitWindow() {
        Behavior active = behavior instanceof MxsBehavior mxs ? mxs.active : behavior;
        if (!(active instanceof MaceBehavior mace) || mace.state != MaceBehavior.FOLLOW
                || !willUseMaceAttack() || mc.player.isUsingItem() || EatUtil.shouldDefer()
                || mc.player.getDeltaMovement().y >= -0.05
                || maceDive.isActive() && !maceDive.canAttack()) return false;
        double reach = mc.player.entityInteractionRange();
        return observedTargetBox().distanceToSqr(mc.player.getEyePosition()) <= reach * reach
                && mc.player.hasLineOfSight(target);
    }

    private Vec3 spearAttackEye() {
        var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        return SpearCombat.attackEye(mc.player, flight != null && flight.isArmorSwapActive());
    }

    /** Preserve only a charged, visible approach against a non-urgent prediction.
     * This radial opportunity is not a claim of a hit; actual weapon threats still interrupt. */
    private boolean spearApproachWindow() {
        Behavior active = behavior instanceof MxsBehavior mxs ? mxs.active : behavior;
        if (!(active instanceof SpearBehavior spear) || spear.state == SpearBehavior.PULL_OVER
                || spear.makingChargeRoom || !weapons.isHoldingUse() || !SpearCombat.isReady(mc.player)
                || EatUtil.shouldDefer() || !mc.player.hasLineOfSight(target)) {
            spearCommit.reset();
            return false;
        }
        double distance = Math.sqrt(observedTargetBox().distanceToSqr(spearAttackEye()));
        return spearCommit.admit(mc.player.tickCount, target,
                distance >= SpearCombat.minRange(mc.player, mc.player.getUseItem()) + 0.35
                && distance <= SpearCombat.maxRange(mc.player, mc.player.getUseItem()));
    }

    private boolean fallingMaceThreat(Vec3 velocity) {
        if (!(target instanceof Player player) || !(player.getMainHandItem().getItem() instanceof MaceItem)
                || player.isFallFlying() || player.onGround() || velocity.y >= -0.15
                || player.getY() <= mc.player.getY() + 0.5 || !mc.player.hasLineOfSight(player)) return false;
        Vec3 offset = player.position().subtract(mc.player.position());
        Vec3 relative = velocity.subtract(mc.player.getDeltaMovement());
        if (relative.y >= -0.05) return false;
        double ticks = offset.y / -relative.y;
        return ticks > 0.0 && ticks <= 4.0 + latencyTicks
                && offset.add(relative.scale(ticks)).horizontalDistanceSqr() <= 6.25;
    }

    private static AerialCombatPolicy.Vector policyVector(Vec3 vector) {
        return new AerialCombatPolicy.Vector(vector.x, vector.y, vector.z);
    }

    private boolean pathClear(Vec3 step) {
        return mc.level.noCollision(mc.player, mc.player.getBoundingBox().deflate(0.01).expandTowards(step));
    }

    private Vec3 flankApproachVector(Vec3 requested) {
        if (!adaptiveCombat.getValue() || threatMode.getValue() != ThreatMode.ALL_PLAYERS
                || !(target instanceof Player) || mc.player.distanceToSqr(target) <= 144.0) return requested;
        Behavior active = behavior instanceof MxsBehavior mxs ? mxs.active : behavior;
        // An intercept already includes lateral target motion. An unconditional side
        // bias spends closing speed and makes that rendezvous miss; actual threats
        // still use the defense/anti-spear paths.
        if (maceAdaptive.getValue() && active instanceof MaceBehavior) return requested;
        boolean approaching = active instanceof MaceBehavior mace && mace.state == MaceBehavior.FOLLOW
                || active instanceof SpearBehavior spear && spear.state == SpearBehavior.FOLLOW;
        if (!approaching || requested.lengthSqr() < 1.0E-6) return requested;
        Vec3 toward = target.position().subtract(mc.player.position()).multiply(1.0, 0.0, 1.0).normalize();
        if (requested.dot(toward) <= 0.0) return requested;
        Vec3 side = new Vec3(-toward.z, 0.0, toward.x).scale(0.3 * evadeSide);
        return requested.normalize().add(side).normalize().scale(requested.length());
    }

    private Vec3 safeFlightVector(Vec3 requested) {
        routeAvoiding = false;
        double speed = requested.length();
        if (!Double.isFinite(speed) || speed < 1.0E-6) return Vec3.ZERO;
        var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        if (flight == null) return requested;
        double probe = flight.navigationLookahead();
        Vec3 intended = combatAction == AerialCombatPolicy.Action.CHASE ? behavior.heading : defensiveHeading;
        Vec3 goal = mc.player.position().add(requested.normalize().scale(Math.max(1.0, intended.length())));
        if (combatAction == AerialCombatPolicy.Action.CHASE
                && (behavior instanceof SpearBehavior || behavior instanceof MxsBehavior mxs && !mxs.usingMace)) {
            // The minimum-speed spear request is not an instruction to continue through
            // the floor beyond a nearby grounded target.
            double toTarget = mc.player.getEyePosition().distanceTo(target.getEyePosition());
            goal = mc.player.position().add(requested.normalize().scale(Math.min(intended.length(), toTarget)));
        }
        if (hasMaceDescentIntent()) {
            Behavior active = behavior instanceof MxsBehavior mxs ? mxs.active : behavior;
            Vec3 aim = active instanceof MaceBehavior mace ? mace.strikePlan().aim()
                    : target.getBoundingBox().getCenter();
            goal = mc.player.position().add(requested.normalize().scale(
                    Math.max(speed, mc.player.getEyePosition().distanceTo(aim))));
        }
        long phase = planningPhaseToken();
        AABB body = mc.player.getBoundingBox();
        RouteContext context = new RouteContext(phase, mc.player.getPose(),
                mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA), mc.player.isFallFlying(),
                flight.parallelPlanningEnabled(), body.getXsize(), body.getYsize(), body.getZsize());
        if (!context.equals(routeContext)) {
            parallelRoute.reset();
            flightRoute.reset();
            routeContext = context;
        }
        var route = flight.parallelPlanningEnabled()
                ? parallelRoute.routeTo(mc.player.position(), goal, probe, mc.player.tickCount,
                flight::planningSnapshot, flight::clearNavigationPath)
                : flightRoute.routeTo(mc.player.position(), goal, probe, mc.player.tickCount,
                flight::clearNavigationPath);
        routeAvoiding = route.avoiding() || route.blocked();
        if (!routeAvoiding) return requested;
        clearSpearAim();
        if (GuizhouWare.rotationManager != null) GuizhouWare.rotationManager.cancel("ElytraChase_attack");
        if (weapons.isHoldingUse()) {
            weapons.release();
            weaponControlActive = false;
        }
        return route.blocked() ? Vec3.ZERO : route.direction().scale(Math.min(speed, route.distance()));
    }

    private boolean prepareWeapon(boolean spear) {
        return prepareWeapon(spear, spear);
    }

    private boolean prepareWeapon(boolean spear, boolean charge) {
        if (!spear) clearSpearAim();
        boolean ready = weapons.update(spear, autoWeapon.getValue(), charge && autoSpearUse.getValue());
        weaponControlActive = ready && (autoWeapon.getValue() || weapons.isHoldingUse());
        return ready;
    }

    private boolean isValid(Entity e) {
        if (mc.player == null || mc.level == null || !(e instanceof LivingEntity living)
                || e == mc.player || e.level() != mc.level || e.isRemoved() || !e.isAlive()
                || living.isDeadOrDying() || e.isSpectator()) return false;
        if (!allowsNonPlayers() && !(e instanceof Player)) return false;
        double radius = range.getValue();
        double distance = mc.player.distanceToSqr(e);
        if (!Double.isFinite(distance) || distance > radius * radius) return false;
        if (targetRules == null) targetRules = GuizhouWare.moduleManager.getModuleByClass(TargetsModule.class);
        return targetRules != null && targetRules.isValidTarget(e);
    }

    private Entity searchTarget() {
        Entity best = null;
        double bestDist = range.getValue() * (double) range.getValue();
        double r = range.getValue();
        Iterable<? extends Entity> candidates = allowsNonPlayers()
                ? mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(r), this::isValid)
                : mc.level.players();
        for (Entity e : candidates) {
            if (!isValid(e)) continue;
            double d = mc.player.distanceToSqr(e);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    /** TargetsModule hostiles, neutrals, passives, and armor stands override a player-only chase filter. */
    private boolean allowsNonPlayers() {
        if (!playerOnly.getValue()) return true;
        if (targetRules == null) targetRules = GuizhouWare.moduleManager.getModuleByClass(TargetsModule.class);
        return targetRules != null && (targetRules.hostiles.getValue() || targetRules.neutrals.getValue()
                || targetRules.passives.getValue() || targetRules.armorStands.getValue());
    }

    private EntityAim.Kind aimKind() {
        if (target instanceof Player) return EntityAim.Kind.PLAYER;
        if (target instanceof Slime) return EntityAim.Kind.SLIME;
        return EntityAim.Kind.MOB;
    }

    private AerialDuelPredictor.TargetKind copiedTargetKind() {
        return switch (aimKind()) {
            case SLIME -> AerialDuelPredictor.TargetKind.SLIME;
            case MOB -> AerialDuelPredictor.TargetKind.MOB;
            case PLAYER -> AerialDuelPredictor.TargetKind.PLAYER;
        };
    }

    private EntityAim.Choice aimChoice() {
        EntityAim.Kind kind = aimKind();
        boolean ground = targetGrounded || target.onGround();
        boolean airborne = !ground && (target instanceof LivingEntity living && living.isFallFlying() || !target.onGround());
        return EntityAim.choose(kind, ground, airborne, target instanceof ArmorStand,
                mc.player.getDeltaMovement().length(), targetHistory.velocity(), targetHistory.previousVelocity(),
                targetHistory.hasTwoVelocities(), Vec3.ZERO);
    }

    private AerialDuelPredictor.MotionTag copiedMotionTag() {
        if (aimKind() == EntityAim.Kind.SLIME) return AerialDuelPredictor.MotionTag.STRAIGHT;
        return switch (aimChoice().motion()) {
            case AIR_STRAFE -> AerialDuelPredictor.MotionTag.AIR_STRAFE;
            case GROUND_STRAFE -> AerialDuelPredictor.MotionTag.GROUND_STRAFE;
            case STRAIGHT -> AerialDuelPredictor.MotionTag.STRAIGHT;
        };
    }

    private Vec3 copiedStrafeOffset() {
        EntityAim.Choice choice = aimChoice();
        return choice.motion() == EntityAim.Motion.STRAIGHT ? Vec3.ZERO : choice.offset();
    }

    /** Classify consecutive measured velocities, clearing stale landing/teleport samples. */
    private void classifyTarget() {
        if (target == null) {
            targetHistory.clear();
            targetGrounded = false;
            targetMotion = MotionClass.NORMAL;
            return;
        }
        // Stationary remote entities (including local practice dummies) may never
        // receive a movement packet carrying onGround. Verify support locally too.
        targetGrounded = target.onGround() || Math.abs(target.getDeltaMovement().y) < 0.1
                && !(target instanceof LivingEntity living && living.isFallFlying())
                && mc.level.getBlockCollisions(target,
                target.getBoundingBox().deflate(0.01).move(0.0, -0.08, 0.0)).iterator().hasNext();
        long previousRevision = targetHistory.revision();
        boolean gliding = target instanceof LivingEntity living && living.isFallFlying();
        var chest = ((LivingEntity) target).getItemBySlot(EquipmentSlot.CHEST);
        TargetMotionTracker.Equipment equipment = chest.is(Items.ELYTRA) ? TargetMotionTracker.Equipment.ELYTRA
                : chest.is(ItemTags.CHEST_ARMOR) ? TargetMotionTracker.Equipment.ARMOR : TargetMotionTracker.Equipment.OTHER;
        targetHistory.sample(target.position(), mc.player.tickCount, targetGrounded, gliding, equipment,
                BuiltInRegistries.ITEM.getId(chest.getItem()));
        if (previousRevision != targetHistory.revision()) {
            invalidatePlanning();
            flightRoute.reset();
            ClientDiagnostics.count("air.motion.change", 1);
        }
        if (targetGrounded) {
            targetMotion = MotionClass.NORMAL;
            return;
        }
        if (!targetHistory.hasTwoVelocities()) {
            targetMotion = MotionClass.UNKNOWN;
            return;
        }
        Vec3 previous = targetHistory.previousVelocity();
        Vec3 current = targetHistory.velocity();
        double previousSq = previous.lengthSqr();
        double currentSq = current.lengthSqr();
        if (previousSq < 1.0E-12 && currentSq < 1.0E-12) {
            targetMotion = MotionClass.STILL;
        } else if (previousSq < 0.75 * 0.75 && currentSq < 0.75 * 0.75) {
            targetMotion = MotionClass.NORMAL;
        } else {
            double dot = previous.dot(current);
            if (dot > 0.0 && dot * dot > 0.25 * previousSq * currentSq) {
                boolean towardMe = current.dot(mc.player.position().subtract(target.position())) > 0.0;
                targetMotion = towardMe ? MotionClass.RUSHING : MotionClass.FLEEING;
            } else {
                targetMotion = MotionClass.UNKNOWN;
            }
        }
    }

    // ---------------------------------------------------------------
    // behaviour base (rennaikanksmp CombatSubHelperSX)
    // ---------------------------------------------------------------

    private abstract class Behavior {
        Vec3 heading = Vec3.ZERO;

        void enter() {
            heading = Vec3.ZERO;
        }

        /** SX.onElytra semantics: vector length is the desired speed. */
        void inject(FlightVelocity fv) {
            if (heading.lengthSqr() > 1.0E-9) {
                double len = heading.length();
                double max = Math.min(len, fv.maxVelocity());
                fv.set(heading.normalize().scale(max));
            }
        }

        void resetHeading() {
            heading = Vec3.ZERO;
        }

        void onHit(int type) {}

        abstract void tick();

        /** One-word HUD label for the current state; defaults to the behavior name. */
        String hudLabel() { return "CHASE"; }

        /** SX.antiSpear: lateral drift perpendicular to the heading. */
        void antiSpear(double strength) {
            Vec3 flat = new Vec3(heading.x, 0.0, heading.z);
            if (flat.lengthSqr() < 0.01) {
                if (mc.player.position().subtract(target.position()).horizontalDistance() >= 3.0) {
                    return;
                }
                heading = new Vec3(5.0, heading.y, heading.z);
                flat = new Vec3(heading.x, 0.0, heading.z);
            }
            Vec3 side = new Vec3(0.0, 1.0, 0.0).cross(flat).normalize();
            heading = heading.normalize().add(side.scale(strength)).normalize().scale(10.0);
        }

        boolean willAntiSpear(double strength, Vec3 targetPos) {
            return strength > 1.0E-6
                    && target instanceof Player p
                    && SpearCombat.threatens(p, mc.player, spearAntiSpearExtraDistance.getValue(), 0.25);
        }

        boolean flyingOrCreative() {
            return mc.player.isFallFlying() || mc.player.getAbilities().flying;
        }
    }

    // ---------------------------------------------------------------
    // FOLLOW (CombatSubHelperO)
    // ---------------------------------------------------------------

    private class FollowBehavior extends Behavior {
        @Override
        void tick() {
            if (!isValid(target)) {
                heading = Vec3.ZERO;
                return;
            }
            heading = target.position().subtract(mc.player.position());
            if (targetGrounded && followGroundHeightExtra.getValue() > 0.1) {
                heading = heading.add(0.0, followGroundHeightExtra.getValue(), 0.0);
            }
        }
    }

    // ---------------------------------------------------------------
    // MACE (CombatSubHelperOX + FX state machine)
    // ---------------------------------------------------------------

    private class MaceBehavior extends Behavior {
        static final int NONE = 0, PULL_UP = 1, FOLLOW = 2, WAIT_ATTACK = 3;
        int state = NONE;
        int lastAttackSentTick = Integer.MIN_VALUE / 2;
        int naturalFallTicks;
        boolean wasDiving;
        boolean catchingUp;
        boolean terminal;
        double passHeight = Double.NaN;
        int glideSince = Integer.MIN_VALUE;
        int lastDiveWarningTick = Integer.MIN_VALUE / 2;
        final MacePassTracker pass = new MacePassTracker();

        @Override
        void enter() {
            super.enter();
            state = NONE;
            lastAttackSentTick = Integer.MIN_VALUE / 2;
            naturalFallTicks = 0;
            wasDiving = false;
            catchingUp = false;
            terminal = false;
            passHeight = Double.NaN;
            glideSince = Integer.MIN_VALUE;
            pass.reset();
        }

        @Override
        void tick() {
            if (wasDiving && !maceDive.isActive()) {
                naturalFallTicks = mc.player.fallDistance < maceMinFallDistance.getValue() ? 0 : 12;
                wasDiving = false;
                if (state != WAIT_ATTACK) state = PULL_UP;
            }
            if (!isValid(target) || !flyingOrCreative() && !fallingWindow() && !maceDive.isActive()) {
                weapons.release();
                heading = Vec3.ZERO;
                if (state != WAIT_ATTACK) state = PULL_UP;
                return;
            }
            if (maceDive.isActive()) {
                wasDiving = true;
                tickDive();
                return;
            }
            if (flyingOrCreative()) naturalFallTicks = 0;
            else naturalFallTicks++;
            boolean armed = prepareWeapon(false);
            if (!armed) {
                aimNextClimb();
                state = PULL_UP;
                return;
            }

            switch (state) {
                case NONE -> state = tickNone();
                case PULL_UP -> state = tickPullUp();
                case FOLLOW -> state = tickFollow(armed);
                case WAIT_ATTACK -> state = tickWaitAttack();
            }
        }

        boolean fallingWindow() {
            int fallCap = mc.player != null && mc.player.fallDistance < maceMinFallDistance.getValue() ? 40 : 12;
            return mc.player != null && (state == FOLLOW || state == WAIT_ATTACK)
                    && naturalFallTicks < fallCap && !mc.player.isFallFlying() && !mc.player.onGround()
                    && !mc.player.isInWater() && !mc.player.isInLava() && !mc.player.isPassenger()
                    && mc.player.getDeltaMovement().y < -0.05;
        }

        int tickNone() {
            if (target == null) {
                heading = Vec3.ZERO;
                return NONE;
            }
            return tickPullUp();
        }

        /** FX.onStatePullUp: climb to maceHeight above the target, then dive. */
        int tickPullUp() {
            if (!isValid(target)) return NONE;
            terminal = false;
            boolean headBlocked = blockedInDirection(0.0, 0.1, 0.0);
            MaceApproach.Plan plan = climbPlan();
            Vec3 intercept = plan.aim();
            double aboveY = intercept.y;
            catchingUp = !plan.reachable();
            // The led apex is a steering destination, not a moving completion bar.
            // A rising target must not keep extending this climb after we have the
            // chosen relative fall height; the drop gate separately validates impact.
            boolean reached = mc.player.getY() >= target.getY() + plan.height() - 0.1;
            if (!catchingUp && (reached || headBlocked)) {
                pass.reset();
                heading = strikePlan().aim().subtract(mc.player.getEyePosition());
                return FOLLOW;
            }
            heading = new Vec3(intercept.x, aboveY, intercept.z)
                    .subtract(mc.player.position());
            if (heading.length() < 5.0) {
                heading = heading.normalize().scale(5.0);
            }
            return PULL_UP;
        }

        /** Attack during the current reach window, including a brief natural fall after gliding. */
        @Override
        String hudLabel() {
            return switch (state) {
                case FOLLOW -> maceDive != null && maceDive.isActive() ? "DIVE" : "FOLLOW";
                case WAIT_ATTACK -> "WAIT";
                case PULL_UP -> "PULL_UP";
                default -> "FOLLOW";
            };
        }

        int tickFollow(boolean armed) {
            if (target == null) return NONE;
            tryMacePreSwap();
            if (armed && shouldStartDive()) {
                if (maceDive.begin()) {
                    wasDiving = true;
                    naturalFallTicks = 12;
                    clearSpearAim();
                    heading = Vec3.ZERO;
                    aimDiveExit();
                    return FOLLOW;
                }
                if (mc.player.tickCount - lastDiveWarningTick >= 200) {
                    lastDiveWarningTick = mc.player.tickCount;
                    dev.guizhou.features.commands.Command.sendMessage(dev.guizhou.util.lang.ClientLanguage.text("重锤暂时无法收翼：请保留胸甲或背包空槽，并关闭容器界面。"));
                }
            }
            AABB observedBox = observedTargetBox();
            double distanceSquared = observedBox.distanceToSqr(mc.player.getEyePosition());
            if (tryAttack(armed)) return WAIT_ATTACK;
            double reach = mc.player.entityInteractionRange();
            boolean smashWindow = willUseMaceAttack() && distanceSquared <= reach * reach
                    && mc.player.getDeltaMovement().y < -0.05;
            if (pass.shouldPullUp(mc.player.tickCount, Math.sqrt(distanceSquared), reach,
                    mc.player.getY(), observedBox.minY, smashWindow)) return restartPass();
            if (!smashWindow && !targetGrounded && observedBox.minY > mc.player.getY() + 1.5) {
                return restartPass();
            }
            MaceApproach.Plan strike = strikePlan();
            if (maceAdaptive.getValue() && !strike.reachable() && !smashWindow) return restartPass();
            heading = strike.aim().subtract(mc.player.getEyePosition());
            terminal = maceTerminal.getValue() && distanceSquared <= Math.pow(reach + planningSpeed() * 2.0, 2);
            if (terminal) {
                // Match target motion while closing the last error, rather than forcing a
                // five-block intent through a narrow hitbox at maximum envelope speed.
                Vec3 velocity = targetHistory.predictionVelocity(target.getDeltaMovement());
                heading = velocity.add(heading.scale(0.65));
            } else if (heading.length() < 5.0) {
                heading = heading.normalize().scale(5.0);
            }
            if (willAntiSpear(flyAntiSpear.getValue(), target.position())) {
                antiSpear(flyAntiSpear.getValue());
            }

            return FOLLOW;
        }

        private double planningSpeed() {
            // Reachability is judged against the speed the flight controller is about to
            // request; the envelope-clamped instantaneous speed (measured 1.4-2.4 b/t in
            // firework duels) sits below the reachable threshold (~2.5) and would keep
            // every pass in the flat-pursuit fallback.
            var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
            double requested = flight != null ? flight.lastRequestedBpt() : 0.0;
            return Math.clamp(Math.max(mc.player.getDeltaMovement().length(), requested), 0.5, 6.0);
        }

        private MaceApproach.Plan climbPlan() {
            if (aimKind() == EntityAim.Kind.SLIME || aimChoice().pattern() != EntityAim.Pattern.USE_EXISTING
                    && aimKind() != EntityAim.Kind.PLAYER) {
                Vec3 at = aimKind() == EntityAim.Kind.SLIME ? observedTargetBox().getCenter() : predictedTarget();
                double height = MaceApproach.chargeHeight(maceHeight.getValue(), maceMinFallDistance.getValue());
                return new MaceApproach.Plan(new Vec3(at.x, target.getY() + height, at.z), height, 0.0, true);
            }
            if (aimKind() == EntityAim.Kind.PLAYER && aimChoice().pattern() != EntityAim.Pattern.USE_EXISTING) {
                Vec3 at = predictedTarget();
                double height = MaceApproach.chargeHeight(maceHeight.getValue(), maceMinFallDistance.getValue());
                return new MaceApproach.Plan(new Vec3(at.x, target.getY() + height, at.z), height, 0.0, true);
            }
            if (aimKind() != EntityAim.Kind.PLAYER) {
                Vec3 center = observedTargetBox().getCenter();
                double height = MaceApproach.chargeHeight(maceHeight.getValue(), maceMinFallDistance.getValue());
                return new MaceApproach.Plan(new Vec3(center.x, target.getY() + height, center.z), height, 0.0, true);
            }
            if (!maceAdaptive.getValue()) {
                Vec3 lead = predictedTarget();
                double height = MaceApproach.chargeHeight(maceHeight.getValue(), maceMinFallDistance.getValue());
                return new MaceApproach.Plan(new Vec3(lead.x, target.getY() + height, lead.z),
                        height, 0.0, true);
            }
            Vec3 velocity = targetHistory.predictionVelocity(target.getDeltaMovement());
            double preferred = Double.isFinite(passHeight) ? Math.min(passHeight, maceHeight.getValue()) : maceHeight.getValue();
            MaceApproach.Plan plan = MaceApproach.climb(mc.player.position(), target.position(), velocity,
                    planningSpeed(), preferred, maceMinFallDistance.getValue(), latencyTicks,
                    MaceApproach.stable(velocity, targetHistory.previousVelocity(), targetHistory.hasTwoVelocities()));
            // A moving planning horizon must not buy height back halfway through a pass.
            // An unreachable estimate does not commit a new height until a pass becomes feasible.
            if (plan.reachable()) passHeight = plan.height();
            return plan;
        }

        private void aimNextClimb() {
            MaceApproach.Plan plan = climbPlan();
            Vec3 aim = plan.aim();
            heading = new Vec3(aim.x, Math.max(mc.player.getY(), aim.y), aim.z).subtract(mc.player.position());
            terminal = false;
            catchingUp = !plan.reachable();
        }

        private MaceApproach.Plan strikePlan() {
            if (aimKind() == EntityAim.Kind.SLIME
                    || aimKind() != EntityAim.Kind.PLAYER && aimChoice().pattern() == EntityAim.Pattern.USE_EXISTING) {
                Vec3 aim = EntityAim.facingPoint(mc.player.getEyePosition(), observedTargetBox());
                return new MaceApproach.Plan(aim, 0.0, 0.0, true);
            }
            if (aimChoice().pattern() != EntityAim.Pattern.USE_EXISTING) {
                return new MaceApproach.Plan(predictedTarget(), 0.0, 0.0, true);
            }
            if (!maceAdaptive.getValue()) {
                Vec3 aim = observedTargetBox().getCenter().add(predictedTarget().subtract(target.position()));
                return new MaceApproach.Plan(aim, 0.0, 0.0, true);
            }
            Vec3 velocity = targetHistory.predictionVelocity(target.getDeltaMovement());
            return MaceApproach.intercept(mc.player.getEyePosition(), observedTargetBox().getCenter(),
                    velocity, planningSpeed(), latencyTicks,
                    MaceApproach.stable(velocity, targetHistory.previousVelocity(), targetHistory.hasTwoVelocities()));
        }

        private boolean tryAttack(boolean armed) {
            if (!armed || !isValid(target)) return false;
            double configuredRange = target instanceof Player p && isUsingSpear(p)
                    ? spearRange.getValue() : combatRange.getValue();
            boolean smash = willUseMaceAttack();
            // A gliding tap grants the victim hurt immunity just before the real smash.
            // Extra attacks belong to manual-drop mode, not an automatic smash pass.
            boolean ready = smash || !autoMaceDive.getValue() && maceUseExtraAttack.getValue()
                    && mc.player.getAttackStrengthScale(0.5F) > 0.95F;
            if (!ready || observedTargetBox().distanceToSqr(mc.player.getEyePosition())
                    > configuredRange * configuredRange || !attack(target)) return false;
            if (!smash) return false; // An optional ordinary hit does not complete a smash pass.
            lastAttackSentTick = mc.player.tickCount;
            state = WAIT_ATTACK;
            terminal = false;
            passHeight = Double.NaN;
            resumeAfterPass();
            tickWaitAttack();
            return true;
        }

        private int restartPass() {
            state = PULL_UP;
            passHeight = Double.NaN;
            pass.reset();
            resumeAfterPass();
            return tickPullUp();
        }

        private void resumeAfterPass() {
            var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
            if (flight != null && !maceDive.isActive()) flight.finishDiveRecovery();
        }

        private void recoverMissedDive() {
            // Recovery can synchronously restart gliding, so release descent ownership first.
            state = PULL_UP;
            passHeight = Double.NaN;
            pass.reset();
            maceDive.requestRecovery();
            state = tickPullUp();
        }

        private void tryMacePreSwap() {
            if (!macePreSwap.getValue() || maceDive.isActive() || target == null || mc.player == null) return;
            var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
            if (flight != null && flight.isArmorSwapActive()) return;
            if (!mc.player.isFallFlying()) {
                glideSince = Integer.MIN_VALUE;
                return;
            }
            if (glideSince == Integer.MIN_VALUE) glideSince = mc.player.tickCount;
            if (mc.player.tickCount - glideSince < maceSwapMinDelay.getValue()) return;
            int delay = Math.max(0, macePreSwapDelay.getValue());
            AABB future = observedTargetBox().move(target.getDeltaMovement().scale(delay));
            double range = mc.player.entityInteractionRange()
                    + macePreSwapDistancePerTick.getValue() * delay;
            if (future.distanceToSqr(mc.player.getEyePosition()) > range * range) return;
            maceDive.beginPreSwap();
        }

        private boolean shouldStartDive() {
            var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
            Vec3 velocity = targetHistory.predictionVelocity(target.getDeltaMovement());
            double approachRange = AerialIntercept.dropApproachRange(mc.player.getDeltaMovement(),
                    velocity, mc.player.entityInteractionRange());
            if (!autoMaceDive.getValue() || !autoControlElytra.getValue()
                    || flight == null || !flight.canRecoverDiveFlight()
                    || !mc.player.isFallFlying() || mc.player.getAbilities().flying
                    || mc.player.getDeltaMovement().y >= -0.15 || mc.player.getY() < observedTargetBox().minY + 3.0
                    || observedTargetBox().distanceToSqr(mc.player.position()) > approachRange * approachRange
                    || mc.player.isUsingItem()
                    || mc.player.hasEffect(MobEffects.SLOW_FALLING) || mc.player.hasEffect(MobEffects.LEVITATION)
                    || Math.abs(mc.player.getAttributeValue(Attributes.GRAVITY) - 0.08) > 1.0E-5
                    || !dropPathClear()
                    || EatUtil.shouldDefer() || !mc.player.hasLineOfSight(target)) return false;
            Vec3 previous = targetHistory.previousVelocity();
            if (!targetHistory.hasTwoVelocities() || !(velocity.lengthSqr() < 0.01 && previous.lengthSqr() < 0.01)
                    && !AerialCombatPolicy.coherent(policyVector(velocity), policyVector(previous))) return false;
            Vec3 standingEye = mc.player.position().add(0.0, mc.player.getEyeHeight(Pose.STANDING), 0.0);
            return AerialIntercept.canStartDrop(standingEye, mc.player.getDeltaMovement(),
                    observedTargetBox(), velocity, mc.player.entityInteractionRange(), maceMinFallDistance.getValue());
        }

        private boolean dropPathClear() {
            Vec3 step = mc.player.getDeltaMovement().scale(2.0).add(0.0, -0.2, 0.0);
            // The drop only needs a clear lane down to the attack plane; the full
            // velocity lookahead treats the floor under the target as an obstacle
            // and aborts high-speed passes just above the smash window.
            double attackPlane = observedTargetBox().minY + 0.5;
            double lane = MaceApproach.dropStepClamp(step.y, mc.player.getY() - attackPlane);
            if (lane != step.y) step = new Vec3(step.x, lane, step.z);
            AABB standing = mc.player.getDimensions(Pose.STANDING).makeBoundingBox(mc.player.position());
            return mc.level.noCollision(mc.player, standing.deflate(0.01).expandTowards(step));
        }

        private void tickDive() {
            heading = Vec3.ZERO;
            weaponControlActive = true;
            aimDiveExit();
            if (!autoMaceDive.getValue() || EatUtil.shouldDefer() || mc.player.isUsingItem()) {
                recoverMissedDive();
                return;
            }
            if (maceDive.phase() == MaceDiveState.Phase.DIVING
                    && maceDive.canAttack() && willUseMaceAttack() && attack(target)) {
                lastAttackSentTick = mc.player.tickCount;
                state = WAIT_ATTACK;
                passHeight = Double.NaN;
                maceDive.requestRecovery();
                state = tickWaitAttack();
                return;
            }
            if (mc.player.getY() < observedTargetBox().minY + 0.5
                    || !dropPathClear()) {
                recoverMissedDive();
            }
        }

        private void aimDiveExit() {
            // Removing the wings is asynchronous. Until vanilla clears the glide
            // flag, keep steering at the drop: restoring the camera here redirects
            // the still-attached rocket sideways and invalidates the intercept.
            if (!mc.player.isFallFlying()) {
                GuizhouWare.rotationManager.cancel("ElytraChase_dive");
                return;
            }
            float[] angles = MathUtil.calcAngle(mc.player.getEyePosition(), strikePlan().aim());
            GuizhouWare.rotationManager.submit(new RotationRequest("ElytraChase_dive", 70,
                    angles[0], angles[1], RotationRequest.Mode.MOTION, true, true));
        }

        /** Climb while awaiting one server confirmation, with a bounded retry window. */
        int tickWaitAttack() {
            if (target == null) return NONE;
            long elapsed = (long) mc.player.tickCount - lastAttackSentTick;
            if (elapsed < 0 || elapsed > confirmationTicks()) {
                maceFeedback.clear();
                return tickPullUp();
            }
            aimNextClimb();
            return WAIT_ATTACK;
        }

        @Override
        void onHit(int type) {
            if (type == 1) state = PULL_UP;
        }

        @Override
        void inject(FlightVelocity fv) {
            if (heading.lengthSqr() > 1.0E-9) {
                Vec3 v = heading;
                if (maceCustomPullUpAngle.getValue() > 0.1 && state == PULL_UP && !catchingUp) {
                    v = MaceApproach.pullUpIntent(v, maceCustomPullUpAngle.getValue());
                }
                if (state == PULL_UP || state == WAIT_ATTACK) {
                    v = ElytraPhysics.diagonalGzBoost(v, mc.player.getDeltaMovement());
                }
                double len = v.length();
                // Rescaling can make the measured flight faster than the base setting.
                // Do not cap target-matching terminal motion back to that base speed.
                double budget = terminal ? Math.max(fv.maxVelocity(), planningSpeed()) : fv.maxVelocity();
                double max = Math.min(len, budget);
                fv.set(v.normalize().scale(max));
                if (maceAdaptive.getValue()) fv.preserveDirection();
                if (terminal) fv.requestPrecision();
            }
        }
    }

    // ---------------------------------------------------------------
    // SPEAR (CombatSubHelperIX state machine)
    // ---------------------------------------------------------------

    private class SpearBehavior extends Behavior {
        static final int NONE = 0, FOLLOW = 1, NEAR_FOLLOW = 2, PULL_OVER = 3;
        int state = NONE;
        boolean pulledAfterHit;
        int pullOverTicks;
        boolean makingChargeRoom;
        boolean preciseApproach;

        @Override
        void enter() {
            super.enter();
            state = NONE;
            pulledAfterHit = false;
            pullOverTicks = 0;
            makingChargeRoom = false;
            preciseApproach = false;
            spearSweepPhase = 0;
        }

        Vec3 targetPos() {
            boolean straightPlayer = aimKind() == EntityAim.Kind.PLAYER
                    && aimChoice().pattern() == EntityAim.Pattern.USE_EXISTING;
            Vec3 base = straightPlayer ? predictedTarget().add(targetPacketOffset()) : predictedTarget();
            return targetGrounded
                    ? new Vec3(base.x, target.getEyeY(), base.z)
                    : target.getBoundingBox().getCenter().add(base.subtract(target.position()));
        }

        @Override
        void tick() {
            if (!isValid(target) || !flyingOrCreative()) {
                weapons.release();
                clearSpearAim();
                enter();
                return;
            }
            var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
            boolean boostRefresh = flight != null && flight.needsSpearBoostRefresh();
            double chargeDistance = Math.max(spearRange.getValue(),
                    SpearCombat.maxRange(mc.player, mc.player.getMainHandItem())
                            + mc.player.getDeltaMovement().length()
                            * (SpearCombat.chargeTicks(mc.player.getMainHandItem()) + latencyTicks + 2));
            boolean ownedCharge = weapons.isHoldingUse() || isUsingSpear(mc.player);
            boolean lowUnpowered = targetGrounded && flight != null && !flight.isBoostAttached()
                    && mc.player.getY() < target.getBoundingBox().minY + 0.9
                    && mc.player.getDeltaMovement().y < 0;
            boolean charge = !lowUnpowered && (ownedCharge && (!targetGrounded || !boostRefresh)
                    || !boostRefresh && mc.player.distanceToSqr(target) <= chargeDistance * chargeDistance);
            boolean armed = prepareWeapon(true, charge);
            makingChargeRoom = false;
            preciseApproach = false;
            if (!armed || state != PULL_OVER && !isUsingSpear(mc.player)) {
                clearSpearAim();
                state = FOLLOW;
                Vec3 toward = approachHeading(targetPos());
                double distance = spearAttackEye().distanceTo(targetPos());
                double standOff = Math.max(spearAttackDistance.getValue(), 6.0);
                heading = distance < standOff ? escapeHeading(targetPos())
                        : toward.normalize().scale(Math.max(0.0, distance - standOff));
                return;
            }
            int nextState = switch (state) {
                case NONE -> tickNone();
                case FOLLOW -> tickFollow();
                case NEAR_FOLLOW -> tickNearFollow();
                case PULL_OVER -> tickPullOver();
                default -> NONE;
            };
            if (nextState != state) {
                pullOverTicks = 0;
                pulledAfterHit = false;
                if (nextState == PULL_OVER) heading = escapeHeading(targetPos());
            }
            state = nextState;
            if (state != PULL_OVER && !makingChargeRoom && SpearCombat.isReady(mc.player)
                    && (!targetGrounded || spearAttackEye().y <= targetPos().y + 2.5)
                    && spearAttackEye().distanceTo(target.getBoundingBox().getCenter())
                    <= SpearCombat.maxRange(mc.player, mc.player.getUseItem())
                    + Math.max(1.0, mc.player.getDeltaMovement().length() * 2.0)) aimSpear();
            else clearSpearAim();
        }

        int tickNone() {
            if (target == null) {
                heading = Vec3.ZERO;
                return NONE;
            }
            return tickFollow();
        }

        int tickFollow() {
            Vec3 tp = targetPos();
            if (spearAttackEye().distanceToSqr(tp)
                    < spearAttackDistance.getValue() * spearAttackDistance.getValue()) {
                return tickNearFollow();
            }
            if (tooClose(tp)) return PULL_OVER;
            heading = toTargetAtLeast(tp, 6.0);
            tryAntiSpear(tp, false);
            return FOLLOW;
        }

        @Override
        String hudLabel() {
            return switch (state) {
                case FOLLOW -> "FOLLOW";
                case NEAR_FOLLOW -> "ENGAGE";
                case PULL_OVER -> "PULL_OVER";
                default -> "FOLLOW";
            };
        }

        int tickNearFollow() {
            Vec3 tp = targetPos();
            if (isUsingSpear(mc.player) && !SpearCombat.isReady(mc.player)
                    && spearAttackEye().distanceToSqr(tp)
                    < Math.pow(SpearCombat.maxRange(mc.player, mc.player.getUseItem()) + 1.0, 2)) {
                makingChargeRoom = true;
                heading = escapeHeading(tp);
                tryAntiSpear(tp, true);
                return NEAR_FOLLOW;
            }
            if (tooClose(tp)) return PULL_OVER;
            if (spearAttackEye().distanceToSqr(tp)
                    > spearRange.getValue() * spearRange.getValue()) {
                return FOLLOW;
            }
            heading = toTargetAtLeast(tp, 6.0);
            if (!tryAntiSpear(tp, false)) limitChargedApproach();
            return NEAR_FOLLOW;
        }

        void limitChargedApproach() {
            if (!SpearCombat.isReady(mc.player)) return;
            Vec3 eye = spearAttackEye();
            Vec3 limited = SpearApproach.limitStep(heading, observedTargetBox().getCenter().subtract(eye),
                    targetHistory.predictionVelocity(target.getDeltaMovement()),
                    Math.sqrt(observedTargetBox().distanceToSqr(eye)),
                    SpearCombat.minRange(mc.player, mc.player.getUseItem()),
                    SpearCombat.maxRange(mc.player, mc.player.getUseItem()));
            preciseApproach = limited.lengthSqr() + 1.0E-6 < heading.lengthSqr();
            heading = limited;
        }

        int tickPullOver() {
            Vec3 tp = targetPos();
            if (pulledAfterHit || targetGrounded) {
                double distance = spearPullOverDistance.getValue();
                if (spearAttackEye().distanceToSqr(tp) >= distance * distance) {
                    pulledAfterHit = false;
                    return FOLLOW;
                }
            }
            if (++pullOverTicks > (targetGrounded ? 30 : pulledAfterHit ? 20 : 6)) {
                return FOLLOW;
            }
            heading = escapeHeading(tp);
            tryAntiSpear(tp, true);
            return PULL_OVER;
        }

        Vec3 escapeHeading(Vec3 targetPosition) {
            Vec3 away = spearAttackEye().subtract(targetPosition);
            if (targetGrounded) away = new Vec3(away.x, 0.0, away.z);
            if (away.lengthSqr() < 1.0E-9) {
                away = mc.player.getLookAngle().scale(-1.0);
                if (targetGrounded) away = new Vec3(away.x, 0.0, away.z);
                if (away.lengthSqr() < 1.0E-9) away = new Vec3(0.0, 0.0, 1.0);
            }
            Vec3 result = away.lengthSqr() < 36.0 ? away.normalize().scale(6.0) : away;
            return groundLane(result);
        }

        @Override
        void inject(FlightVelocity fv) {
            super.inject(fv);
            // GZ's diagonal reshaping turns a level ground lane into a steep climb/drop,
            // making terrain recovery cancel the charge on every approach.
            if (!targetGrounded && !preciseApproach && fv.value().lengthSqr() > 1.0E-9) {
                Vec3 current = fv.value();
                Vec3 diagonal = ElytraPhysics.diagonalGzBoost(current, mc.player.getDeltaMovement());
                if (diagonal.dot(current) > 0.0 && diagonal.lengthSqr() > 1.0E-9) {
                    fv.set(diagonal.normalize().scale(current.length()));
                }
            }
            if (preciseApproach) fv.requestPrecision();
            // A spear pass needs its intercept direction; filling independent envelope
            // axes can turn a shallow disengagement into a climb over the target.
            fv.preserveDirection();
        }

        boolean tooClose(Vec3 tp) {
            double noEntry = spearNoEntryDistance.getValue();
            if (isUsingSpear(mc.player)) {
                noEntry = Math.max(noEntry, SpearCombat.minRange(mc.player, mc.player.getUseItem()) + 0.35);
            }
            AABB interceptBox = target.getBoundingBox().move(targetPacketOffset());
            if (targetGrounded) {
                // Vertical separation must not hide the inner dead zone: hovering
                // directly above a target turns spear aim into a dive into the floor.
                noEntry = Math.max(noEntry, 3.0);
                Vec3 eye = spearAttackEye();
                Vec3 projected = new Vec3(eye.x, interceptBox.getCenter().y, eye.z);
                if (interceptBox.distanceToSqr(projected) < noEntry * noEntry) return true;
            }
            return noEntry > 0.1 && interceptBox.distanceToSqr(spearAttackEye())
                    < noEntry * noEntry;
        }

        Vec3 toTargetAtLeast(Vec3 tp, double minLen) {
            Vec3 v = approachHeading(tp);
            if (v.length() < minLen) {
                v = v.normalize().scale(minLen);
            }
            return v;
        }

        Vec3 approachHeading(Vec3 tp) {
            Vec3 delta = tp.subtract(spearAttackEye());
            if (!targetGrounded || delta.y >= -2.5) return groundLane(delta);
            // Establish a shallow approach lane before aiming the weapon. A vertical
            // chase over a grounded target otherwise alternates dive and terrain pull-up.
            Vec3 flat = new Vec3(delta.x, 0.0, delta.z);
            double distance = flat.length();
            if (distance < Math.max(8.0, Math.min(-delta.y * 3.0, range.getValue() * 0.4))) {
                flat = distance > 0.01 ? flat.scale(-1.0 / distance)
                        : new Vec3(mc.player.getDeltaMovement().x, 0.0, mc.player.getDeltaMovement().z).normalize();
                if (flat.lengthSqr() < 1.0E-6) flat = new Vec3(0.0, 0.0, 1.0);
            } else flat = flat.normalize();
            return flat.add(0.0, -0.18, 0.0).normalize().scale(6.0);
        }

        private Vec3 groundLane(Vec3 intent) {
            if (!targetGrounded) return intent;
            // Aim the weapon at the hitbox separately. Navigating the body to that
            // same eye point scrapes the floor when armor changes the player's pose.
            double feetY = target.getBoundingBox().minY + 1.4;
            double error = feetY - mc.player.getY();
            double horizontal = intent.horizontalDistance();
            double vertical = Math.clamp(error * 0.5, -0.08, 0.18) * Math.max(1.0, horizontal);
            return new Vec3(intent.x, vertical, intent.z);
        }

        /** IX.adjustMovementForSpear: sidestep when their charged-spear ray hits us. */
        boolean tryAntiSpear(Vec3 tp, boolean expand) {
            return spearAntiSpear.getValue() && target instanceof Player p
                    && SpearCombat.threatens(p, mc.player, spearAntiSpearExtraDistance.getValue(), expand ? 0.85 : 0.1)
                    && moveAdjust(heading);
        }

        /** IX.moveAdjust: sidestep perpendicular to the target, collision-checked. */
        boolean moveAdjust(Vec3 toward) {
            Vec3 flat = new Vec3(toward.x, 0.0, toward.z);
            if (flat.lengthSqr() < 1.0E-6) return false;
            Vec3 side = new Vec3(0.0, 1.0, 0.0).cross(flat).normalize();
            if (side.lengthSqr() < 1.0E-6) return false;
            double movement = Math.max(0.5, Math.min(6.0, mc.player.getDeltaMovement().length()));
            Vec3 drift = toward.normalize().add(side.scale(0.8)).normalize();
            if (canMove(drift.scale(movement))) {
                heading = drift.scale(Math.max(6.0, toward.length()));
                return true;
            }
            drift = toward.normalize().subtract(side.scale(0.8)).normalize();
            if (canMove(drift.scale(movement))) {
                heading = drift.scale(Math.max(6.0, toward.length()));
                return true;
            }
            return false;
        }

        boolean canMove(Vec3 step) {
            AABB next = mc.player.getBoundingBox().expandTowards(step);
            return mc.level.noCollision(mc.player, next);
        }

        @Override
        void onHit(int type) {
            if (type == 2 || type == 3) {
                pulledAfterHit = true;
                pullOverTicks = 0;
                state = PULL_OVER;
                heading = escapeHeading(targetPos());
                clearSpearAim();
            }
        }
    }

    // ---------------------------------------------------------------
    // MXS (CombatSubHelperU): mace until hit, then spear movement
    // ---------------------------------------------------------------

    private class MxsBehavior extends Behavior {
        final MaceBehavior mace = new MaceBehavior();
        final SpearBehavior spear = new SpearBehavior();
        Behavior active;
        boolean usingMace = true;
        int maceHitTick = -1;
        int maceHitTargetId = -1;
        int spearStartedTick;
        int spearPhaseTick;
        boolean spearArmed;
        boolean spearHit;

        @Override
        String hudLabel() {
            return active != null ? active.hudLabel() : super.hudLabel();
        }

        @Override
        void enter() {
            super.enter();
            mace.enter();
            spear.enter();
            active = null;
            switchTo(true);
        }

        void switchTo(boolean useMace) {
            combatPhase++;
            Behavior next = useMace ? mace : spear;
            if (active != next || usingMace != useMace) {
                next.enter();
            }
            active = next;
            usingMace = useMace;
            heading = active.heading;
            maceHitTick = -1;
            maceHitTargetId = -1;
            spearPhaseTick = mc.player != null ? mc.player.tickCount : 0;
            spearStartedTick = spearPhaseTick;
            spearArmed = false;
            spearHit = false;
            maceFeedback.clear();
        }

        @Override
        void tick() {
            if (!isValid(target)) {
                enter();
                return;
            }
            if (active == null) {
                switchTo(true);
            }
            if (target == null || target.getId() != maceHitTargetId) {
                maceHitTick = -1;
            }
            if (usingMace && maceHitTick >= 0
                    && (long) mc.player.tickCount - maceHitTick >= mxsMaceHitWaitTicks.getValue()) {
                switchTo(false);
            } else if (!usingMace) {
                long elapsed = (long) mc.player.tickCount - spearStartedTick;
                boolean escaped = spearHit && spear.state == SpearBehavior.FOLLOW;
                boolean targetAbove = spearArmed && elapsed >= 8 && target.getY() - mc.player.getY()
                        > Math.max(1.5, mxsSpearSwitchMaceHeight.getValue());
                boolean timedOut = spearArmed ? elapsed >= mxsSpearRunTicks.getValue()
                        : (long) mc.player.tickCount - spearPhaseTick >= 40;
                if (elapsed < 0 || timedOut || escaped || targetAbove) {
                    switchTo(true);
                }
            }
            active.tick();
            if (!usingMace && !spearArmed && isUsingSpear(mc.player)) {
                spearArmed = true;
                spearStartedTick = mc.player.tickCount;
            }
            heading = active.heading;
        }

        @Override
        void onHit(int type) {
            if (active != null) active.onHit(type);
            if (usingMace && type == 1 && target != null) {
                maceHitTick = mc.player.tickCount;
                maceHitTargetId = target.getId();
            }
            if (!usingMace && type == 3) spearHit = true;
        }

        @Override
        void inject(FlightVelocity fv) {
            if (active != null) {
                active.inject(fv);
                heading = active.heading;
            }
        }

        @Override
        void resetHeading() {
            super.resetHeading();
            mace.resetHeading();
            spear.resetHeading();
        }
    }

    // ---------------------------------------------------------------
    // attack + hit feedback
    // ---------------------------------------------------------------

    private boolean willUseMaceAttack() {
        return mc.player.getMainHandItem().is(Items.MACE)
                && MaceItem.canSmashAttack(mc.player)
                && mc.player.fallDistance >= maceMinFallDistance.getValue();
    }

    private boolean attack(Entity entity) {
        if (mc.gameMode == null || mc.getConnection() == null || !isValid(entity) || entity != target || paused()
                || EatUtil.shouldDefer() || mc.player.isUsingItem()
                || !mc.player.getMainHandItem().is(Items.MACE)
                || lastAttackTick == mc.player.tickCount) return false;
        double reach = mc.player.entityInteractionRange();
        Vec3 eye = mc.player.getEyePosition();
        AABB box = observedTargetBox();
        if (!Double.isFinite(reach) || reach <= 0.0
                || box.distanceToSqr(eye) > reach * reach
                || !mc.player.hasLineOfSight(entity)) return false;
        if (!willUseMaceAttack() && mc.player.getAttackStrengthScale(0.5F) <= 0.95F) return false;
        Vec3 aim = EntityAim.facingPoint(eye, box);
        float[] angles = MathUtil.calcAngle(eye, aim);
        var rotation = GuizhouWare.rotationManager;
        rotation.submit(new RotationRequest("ElytraChase_attack", 70, angles[0], angles[1],
                RotationRequest.Mode.SILENT));
        acceptPendingRotation();
        if (!sentRotationKnown) return false;
        Vec3 actualLook = Vec3.directionFromRotation(sentPitch, sentYaw);
        if (!box.contains(eye) && box.clip(eye, eye.add(actualLook.scale(reach))).isEmpty()) return false;

        boolean smash = willUseMaceAttack();
        attackGeneration++;
        maceFeedback.clear();
        pendingAttack = null;
        sendingAttack = true;
        PacketEvent.Send attempt;
        try {
            mc.gameMode.attack(mc.player, entity);
            attempt = pendingAttack;
        } finally {
            sendingAttack = false;
            pendingAttack = null;
        }
        if (attempt == null || attempt.isCancelled()) return false;
        lastAttackTick = mc.player.tickCount;
        if (smash) maceFeedback.arm(entity.getId(), lastAttackTick, confirmationTicks());
        mc.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private Vec3 predictedTarget() {
        EntityAim.Kind kind = aimKind();
        if (kind == EntityAim.Kind.SLIME) {
            Vec3 box = observedTargetBox().getCenter();
            boolean stable = MaceApproach.stable(targetHistory.velocity(), targetHistory.previousVelocity(),
                    targetHistory.hasTwoVelocities());
            return box.add(EntityAim.lead(kind, targetHistory.velocity(), stable));
        }
        EntityAim.Choice choice = aimChoice();
        if (choice.pattern() == EntityAim.Pattern.BOX) return observedTargetBox().getCenter();
        if (choice.pattern() == EntityAim.Pattern.AIR_STRAFE
                || choice.pattern() == EntityAim.Pattern.GROUND_STRAFE
                || choice.pattern() == EntityAim.Pattern.GROUND_LEAD) {
            return observedTargetBox().getCenter().add(choice.offset());
        }
        if (kind != EntityAim.Kind.PLAYER) {
            Vec3 box = observedTargetBox().getCenter();
            boolean stable = MaceApproach.stable(targetHistory.velocity(), targetHistory.previousVelocity(),
                    targetHistory.hasTwoVelocities());
            return box.add(EntityAim.lead(kind, targetHistory.velocity(), stable));
        }
        if (duelPrediction != null && acceptedDuel != null && predictionPhase == planningPhaseToken()
                && acceptedDuel.session() == session && acceptedDuel.targetId() == target.getId()) {
            int age = mc.player.tickCount - acceptedDuel.tick();
            Vec3 full = duelPrediction.interceptOffset();
            if (age >= 0 && age <= 2 && full != null && Double.isFinite(full.lengthSqr())) {
                Vec3 elapsed = acceptedDuel.input().targetVelocity().scale(age);
                Vec3 remaining = full.subtract(elapsed);
                if (remaining.dot(full) > 0.0) return target.position().add(remaining);
            }
        }
        TargetMotionTracker.Snapshot motion = targetHistory.snapshot(target.getDeltaMovement());
        double lead = AerialIntercept.leadTicks(target.position().subtract(mc.player.position()),
                motion.velocity(), mc.player.getDeltaMovement().length(), latencyTicks, motion.confidence() >= 0.6);
        return target.position().add(motion.displacement(lead));
    }

    private int confirmationTicks() {
        return confirmationTicks;
    }

    private void clearDiveAim() {
        if (GuizhouWare.rotationManager != null) GuizhouWare.rotationManager.cancel("ElytraChase_dive");
    }

    private void aimSpear() {
        // The packet offset alone is the best calibrated predictor of the server-side
        // query position (median 2.37 blocks); adding the velocity lead measured worse.
        Vec3 aim = aimKind() == EntityAim.Kind.PLAYER
                ? target.getBoundingBox().getCenter().add(targetPacketOffset())
                : EntityAim.facingPoint(spearAttackEye(), observedTargetBox());
        var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        Vec3 attackEye = SpearCombat.attackEye(mc.player, flight != null && flight.isArmorSwapActive());
        float[] angles = MathUtil.calcAngle(attackEye, aim);
        boolean straightAir = aimKind() == EntityAim.Kind.PLAYER && !targetGrounded
                && aimChoice().pattern() == EntityAim.Pattern.USE_EXISTING;
        if (straightAir && SpearCombat.isReady(mc.player)) {
            // Kinetic damage is a per-tick server ray query along the sent look; the
            // residual prediction error exceeds the target box at spear range, so sweep
            // the yaw across the uncertainty cone instead of parking on a ghost point.
            angles[0] = SpearAimSweep.applySweep(angles[0], spearSweepPhase++);
        }
        GuizhouWare.rotationManager.submit(new RotationRequest("ElytraChase_spear", 15,
                angles[0], angles[1], RotationRequest.Mode.MOTION, true, true));
    }

    private AABB observedTargetBox() {
        return target.getBoundingBox().move(targetPacketOffset());
    }

    private Vec3 targetPacketOffset() {
        var interpolation = target.getInterpolation();
        if (interpolation == null || !interpolation.hasActiveInterpolation()) return Vec3.ZERO;
        Vec3 offset = interpolation.position().subtract(target.position());
        // A teleport/discontinuity is not a reliable short-range attack estimate.
        return Double.isFinite(offset.lengthSqr()) && offset.lengthSqr() <= 144.0 ? offset : Vec3.ZERO;
    }

    private void clearSpearAim() {
        spearSweepPhase = 0;
        if (GuizhouWare.rotationManager != null) GuizhouWare.rotationManager.cancel("ElytraChase_spear");
    }

    private void acceptPendingRotation() {
        PacketEvent.Send event = pendingRotation;
        pendingRotation = null;
        if (event != null && !event.isCancelled() && event.getPacket() instanceof ServerboundMovePlayerPacket move) {
            sentYaw = move.getYRot(0.0F);
            sentPitch = move.getXRot(0.0F);
            sentRotationKnown = Float.isFinite(sentYaw) && Float.isFinite(sentPitch);
        }
    }

    @Subscribe(priority = Integer.MIN_VALUE)
    private void onPacketSend(PacketEvent.Send event) {
        if (sendingAttack && mc.isSameThread() && event.getPacket() instanceof ServerboundInteractPacket) {
            pendingAttack = event;
            return;
        }
        long expectedSession = session;
        if (event.getPacket() instanceof ServerboundMovePlayerPacket move && move.hasRotation()) {
            mc.execute(() -> {
                if (session != expectedSession || !isEnabled()) return;
                acceptPendingRotation();
                pendingRotation = event;
            });
            return;
        }
        if (!(event.getPacket() instanceof ServerboundInteractPacket)) return;
        long expectedAttack = attackGeneration;
        mc.execute(() -> {
            if (!event.isCancelled() && session == expectedSession && attackGeneration == expectedAttack) maceFeedback.clear();
        });
    }

    @Subscribe
    private void onPacketReceive(PacketEvent.Receive event) {
        var packet = event.getPacket();
        if (packet instanceof ClientboundTeleportEntityPacket teleport) {
            Entity expectedTarget = target;
            ClientLevel expectedLevel = mc.level;
            LocalPlayer expectedPlayer = mc.player;
            long expectedSession = session;
            if (expectedTarget == null || teleport.id() != expectedTarget.getId()) return;
            mc.execute(() -> {
                if (!isEnabled() || session != expectedSession || target != expectedTarget
                        || mc.level != expectedLevel || mc.player != expectedPlayer) return;
                targetHistory.clear();
                invalidatePlanning();
                flightRoute.reset();
                spearCommit.reset();
                clearSpearAim();
                maceDive.requestRecovery();
                if (behavior != null) behavior.enter();
                ClientDiagnostics.count("air.motion.teleport", 1);
            });
            return;
        }
        if (packet instanceof ClientboundPlayerPositionPacket) {
            long expectedSession = session;
            mc.execute(() -> {
                if (session != expectedSession || !isEnabled()) return;
                pendingRotation = null;
                sentRotationKnown = false;
                maceFeedback.clear();
                maceDive.requestRecovery();
                invalidatePlanning();
                attackGeneration++;
            });
            return;
        }
        if (!(packet instanceof ClientboundDamageEventPacket)
                && !(packet instanceof ClientboundEntityEventPacket)) return;
        long expectedSession = session;
        long expectedPhase = combatPhase;
        long expectedAttack = attackGeneration;
        Entity expectedTarget = target;
        ClientLevel expectedLevel = mc.level;
        LocalPlayer expectedPlayer = mc.player;
        if (expectedTarget == null || expectedLevel == null || expectedPlayer == null) return;
        mc.execute(() -> {
            if (expectedSession != session || expectedPhase != combatPhase || !isEnabled() || behavior == null
                    || mc.level != expectedLevel || mc.player != expectedPlayer
                    || target != expectedTarget || !isValid(expectedTarget) || paused()
                    || combatAction != AerialCombatPolicy.Action.CHASE) return;
            if (packet instanceof ClientboundDamageEventPacket damage) {
                if (damage.entityId() == mc.player.getId()) {
                    behavior.onHit(2);
                } else if (damage.entityId() == expectedTarget.getId()
                        && damage.sourceDirectId() == mc.player.getId()) {
                    if ((damage.sourceType().is(DamageTypes.MACE_SMASH)
                            || damage.sourceType().is(DamageTypes.PLAYER_ATTACK))
                            && expectedAttack == attackGeneration
                            && maceFeedback.consume(expectedTarget.getId(), mc.player.tickCount)) {
                        behavior.onHit(1);
                    } else if (damage.sourceType().is(DamageTypes.SPEAR) && spearPhase()) {
                        behavior.onHit(3);
                    }
                }
            } else if (packet instanceof ClientboundEntityEventPacket status
                    && status.getEventId() == 2 && status.getEntity(mc.level) == mc.player) {
                behavior.onHit(2);
            }
        });
    }

    /** ping in ticks (1-3) for the high-speed attack range compensation */
    private int readPingTicks() {
        if (mc.getConnection() != null && mc.player != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            if (info != null) {
                int roundTrip = Math.clamp((info.getLatency() + 49L) / 50L, 1, 20);
                confirmationTicks = Math.clamp(roundTrip + (int) Math.ceil(
                        3 * GuizhouWare.tpsCounterService.delayScale()), 4, 24);
                return Math.min(3, roundTrip);
            }
        }
        confirmationTicks = 6;
        return 1;
    }

    private boolean blockedInDirection(double dx, double dy, double dz) {
        AABB next = mc.player.getBoundingBox().move(dx, dy, dz);
        return !mc.level.noCollision(mc.player, next);
    }

    @Override
    public String getDisplayInfo() {
        if (mc.player == null) return dev.guizhou.util.lang.ClientLanguage.text("等待进入世界");
        if (manualOverride()) return dev.guizhou.util.lang.ClientLanguage.text("手动飞行优先");
        if (!isValid(target)) return dev.guizhou.util.lang.ClientLanguage.text("等待目标");
        if (paused()) return dev.guizhou.util.lang.ClientLanguage.text("暂停 / 回拉恢复");
        if (maceDive.isActive()) return "Dive/" + maceDive.phase();
        if (combatAction != AerialCombatPolicy.Action.CHASE) return combatAction.name();
        Behavior b = behavior;
        if (b instanceof MxsBehavior mxs && mxs.active != null) {
            return (mxs.usingMace ? "M" : "S") + "/" + targetMotion;
        }
        return targetMotion.name();
    }
}
