package dev.guizhou.features.modules.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.util.elytra.MaceCombat;
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
import dev.guizhou.util.elytra.AerialEngagement;
import dev.guizhou.util.elytra.EatingRetreat;
import dev.guizhou.util.elytra.AerialIntercept;
import dev.guizhou.util.elytra.AerialDuelPredictor;
import dev.guizhou.util.elytra.AsyncFlightRoute;
import dev.guizhou.util.elytra.CombatHitWindow;
import dev.guizhou.util.elytra.EntityAim;
import dev.guizhou.util.elytra.ElytraWeaponController;
import dev.guizhou.util.elytra.FlightVelocity;
import dev.guizhou.util.elytra.FlightTerrainSafety;
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
import java.util.ArrayList;
import java.util.Comparator;
import dev.guizhou.util.elytra.EvadeCommitment;
import dev.guizhou.util.elytra.PearlLandingThreat;
import dev.guizhou.util.combat.ProjectilePrediction;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;

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
    private final Setting<Boolean> autoArmorFly = bool("AutoArmorFly", true).setPage("General")
            .described("Enable armor switching while controlling combat flight; preserve an already enabled ArmorFly module.");
    private boolean armorEnabledByChase;
    private final Setting<Boolean> autoSpearUse = bool("AutoSpearUse", true).setPage("General");
    private final Setting<Boolean> adaptiveCombat = bool("AdaptiveCombat", true).setPage("General");
    private final Setting<Boolean> noacPressure = bool("NoACPressure", true).setPage("Advanced")
            .described("无反作弊：持续攀升僵持且无法下砸时，在正常距离和冷却内用普通重锤攻击施压；不打断已开始的下砸。");
    private final Setting<Boolean> whileEating = bool("WhileEating", true).setPage("Defense")
            .described("吃普通或附魔金苹果时暂停进攻，恢复鞘翅、拉升并远离敌人；松键、吃完或取消后恢复空战。手动接管、地形和回弹保护优先。");
    private final Setting<Double> eatingDistance = num("EatingDistance", 16.0, 6.0, 64.0).setPage("Defense")
            .described("进食期间希望保持的安全距离，单位是方块；敌人的武器距离和接近速度会扩大避让距离。");
    private final Setting<Double> eatingHeight = num("EatingHeight", 6.0, 1.0, 24.0).setPage("Defense")
            .described("进食时希望高出附近敌人的高度；达到后停止继续拉高，遇到顶障时优先横向脱离。");
    private final Setting<Boolean> evadeCommitment = bool("EvadeCommitment", true).setPage("Defense")
            .described("Briefly keep an escape heading; obstacles and new threats can interrupt it.");
    private final Setting<Integer> evadeHoldTicks = num("EvadeHoldTicks", 3, 1, 6).setPage("Defense");
    private final Setting<Boolean> evadeRearm = bool("EvadeRearm", true).setPage("Defense")
            .described("Debounce repeated heading commitments without ignoring incoming attacks.");
    private final Setting<Boolean> pearlDefense = bool("PearlDefense", true).setPage("Defense")
            .described("Avoid imminent hostile pearl arrivals, independent of trajectory rendering.");
    private final Setting<ThreatMode> threatMode = mode("ThreatMode", ThreatMode.ALL_PLAYERS).setPage("General");
    private final Setting<Boolean> avoidObstacles = bool("AvoidObstacles", true).setPage("General");
    private final Setting<Boolean> onlyWhenNoWasd = bool("OnlyWhenNoWasd", false).setPage("General")
            .described("按 WASD 时暂停追击与武器控制，交回手动飞行；适合与 GZBoost 配合赶路。");
    private final Setting<Double> combatRange = num("CombatRange", 10.0, 1.0, 64.0).setPage("General");
    private final Setting<Double> spearRange = num("SpearRange", 11.0, 1.0, 64.0).setPage("General");
    private final Setting<Double> followGroundHeightExtra = num("FollowGroundHeightExtra", 2.0, 0.0, 10.0).setPage("General");

    // ---- Advanced ----
    private final Setting<Boolean> maceUseExtraAttack = bool("MaceUseExtraAttack", true).setPage("Advanced")
            .described("手动下砸或低顶盖地面目标允许冷却完成后的普攻；开阔地自动下砸保留重击窗口。");
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
    /** Experimental: keep the crossing direction after flying past the target in close range. */
    private final Setting<Boolean> spearPassThrough = bool("SpearPassThrough", false).setPage("Advanced");
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
    private boolean lowGroundCover;
    private long groundCoverHoldUntil = Long.MIN_VALUE;
    private int groundCoverTick = Integer.MIN_VALUE;
    private Entity groundCoverTarget;
    private int groundAimTick = Integer.MIN_VALUE;
    private Entity groundAimTarget;
    private Vec3 groundAim = Vec3.ZERO;
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
    private boolean eatingRetreat;
    private long targetSinceTick;
    private long lastTargetSearchTick = Long.MIN_VALUE / 2;
    private long evadeSideUntil;
    private double evadeSide = 1.0;
    private final EvadeCommitment escapeCommitment = new EvadeCommitment();
    private boolean observedMaceThreat;
    private long maceThreatEpisode;
    private Vec3 lastDefenseDirection = Vec3.ZERO;

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
        eatingDistance.setVisibility(v -> whileEating.getValue());
        eatingHeight.setVisibility(v -> whileEating.getValue());
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
        if (!hasMaceDescentIntent() || mc.player.isFallFlying()) return false;
        Behavior active = INSTANCE.behavior instanceof MxsBehavior mxs ? mxs.active : INSTANCE.behavior;
        return active instanceof MaceBehavior mace && mace.retainNaturalFall();
    }

    /** Close mace pursuit needs controlled lateral banking rather than cruise lift. */
    public static boolean isMacePursuitActive() {
        GuangxiElytraChaseModule bot = INSTANCE;
        if (bot == null || !bot.isEnabled() || bot.paused() || !bot.isValid(bot.target)
                || bot.combatAction != AerialCombatPolicy.Action.CHASE || isEatingRetreatRequested()) return false;
        Behavior active = bot.behavior instanceof MxsBehavior mxs ? mxs.active : bot.behavior;
        return active instanceof MaceBehavior;
    }

    public static boolean hasMaceDescentIntent() {
        GuangxiElytraChaseModule bot = INSTANCE;
        if (bot == null || !bot.isEnabled() || bot.paused() || bot.terrainEscaping() || !bot.isValid(bot.target)
                || bot.combatAction != AerialCombatPolicy.Action.CHASE || isEatingRetreatRequested()
                || !MaceCombat.isMace(mc.player.getMainHandItem())
                || mc.player.onGround()) return false;
        if (bot.lowGroundCover && !bot.realMaceHitWindow()) return false;
        Behavior active = bot.behavior instanceof MxsBehavior mxs ? mxs.active : bot.behavior;
        return active instanceof MaceBehavior mace && (mace.state == MaceBehavior.FOLLOW
                && mc.player.getY() > bot.target.getY() + .5 || bot.realMaceHitWindow());
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
                && (isEatingRetreatRequested() || bot.weapons.isHoldingUse() || isUsingSpear(mc.player)
                || bot.maceDive.isActive()
                || mc.player.tickCount == bot.lastAttackTick
                || bot.maceFeedback.pending(bot.target.getId(), mc.player.tickCount));
    }

    private boolean spearPhase() {
        return behavior instanceof SpearBehavior
                || behavior instanceof MxsBehavior mxs && !mxs.usingMace;
    }

    /** Read the real meal before the earlier armor listener decides whether to return a chestplate. */
    public static boolean isEatingRetreatRequested() {
        GuangxiElytraChaseModule bot = INSTANCE;
        return dev.guizhou.manager.HeartbeatManager.authorized() && bot != null && bot.isEnabled()
                && bot.whileEating.getValue() && !bot.paused() && bot.isValid(bot.target)
                && !mc.player.onGround() && !mc.player.isInWater() && !mc.player.isInLava()
                && !mc.player.isPassenger() && !mc.player.isSpectator() && EatUtil.isEatingGoldenApple();
    }

    private boolean paused() {
        return mc.player == null || mc.player.isDeadOrDying() || mc.screen != null
                || dev.guizhou.util.compat.ClientCoexistence.blocks(dev.guizhou.util.compat.ForeignClientProbe.Domain.FLIGHT)
                || dev.guizhou.util.compat.ClientCoexistence.blocks(dev.guizhou.util.compat.ForeignClientProbe.Domain.ARMOR)
                || dev.guizhou.util.compat.ClientCoexistence.blocks(dev.guizhou.util.compat.ForeignClientProbe.Domain.COMBAT)
                || manualOverride() || GuizhouWare.tpsCounterService.isRubberbandRecent();
    }

    private boolean terrainEscaping() {
        var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        return flight != null && flight.isEscapingTerrain();
    }

    /** A roofed ground target has no smash lane; finish the approach with normal walking after landing. */
    public static Vec3 groundCoverDestination() {
        var bot = INSTANCE;
        if (!dev.guizhou.manager.HeartbeatManager.authorized() || bot == null || !bot.isEnabled() || bot.paused()
                || !bot.isValid(bot.target) || !bot.targetGrounded || !bot.lowGroundCover || !bot.maceAdaptive.getValue()
                || !bot.maceUseExtraAttack.getValue() || !isMacePursuitActive() || isEatingRetreatRequested()
                || bot.terrainEscaping() || !mc.player.onGround()) return null;
        var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        if (flight == null || !flight.allowsGroundPassage()
                || Math.abs(bot.observedTargetPosition().y - mc.player.getY()) > .6) return null;
        Vec3 destination = bot.observedTargetPosition();
        return mc.player.position().distanceToSqr(destination) <= 2.2 * 2.2 && mc.player.hasLineOfSight(bot.target)
                ? mc.player.position() : destination;
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
        if (m.terrainEscaping()) return "TERRAIN_RETREAT";
        if (m.eatingRetreat) return "EATING_RETREAT";
        if (m.combatAction != AerialCombatPolicy.Action.CHASE) return "EVADE";
        return m.behavior.hudLabel();
    }

    /** Flight seam: GuangxiElytra calls this every movement tick. */
    public static void onElytra(FlightVelocity fv) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
        GuangxiElytraChaseModule m = INSTANCE;
        if (m == null || !m.isEnabled() || m.behavior == null || !m.isValid(m.target)) return;
        if (m.paused() || m.terrainEscaping()) {
            m.behavior.resetHeading();
            return;
        }
        if (m.combatAction != AerialCombatPolicy.Action.CHASE) {
            double speed = Math.min(m.defensiveHeading.length(), fv.maxVelocity());
            if (speed < 1.0e-6) {
                fv.requestBraking();
                return;
            }
            fv.set(m.defensiveHeading.normalize().scale(speed));
            // A coupled-cruise preview must not overwrite the chosen escape direction.
            if (m.eatingRetreat || m.evadeCommitment.getValue() || m.pearlDefense.getValue()) fv.preserveDirection();
        } else {
            m.behavior.inject(fv);
            fv.set(m.flankApproachVector(fv.value()));
        }
        // Meal lanes already sweep the full escape and powered glide below. A generic
        // detour may turn back toward a weapon or oscillate against a low ceiling.
        if (m.avoidObstacles.getValue() && !m.eatingRetreat) {
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
        if (event.getStage() != Stage.POST || paused() || !isValid(target) || eatingRetreat) return;
        if (spearPhase()) {
            tryPostMovementSpearAim();
            return;
        }
        if (combatAction == AerialCombatPolicy.Action.EVADE) {
            tryDefensiveMaceAttack();
            return;
        }
        if (combatAction != AerialCombatPolicy.Action.CHASE) return;
        Behavior active = behavior instanceof MxsBehavior mxs ? mxs.active : behavior;
        if (!(active instanceof MaceBehavior mace) || mace.state != MaceBehavior.FOLLOW
                && (mace.state != MaceBehavior.PULL_UP || !willUseMaceAttack()
                || mc.player.getDeltaMovement().y >= -.05)) return;
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
        eatingRetreat = false;
        releaseArmorControl();
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
        lowGroundCover = false;
        groundCoverHoldUntil = Long.MIN_VALUE;
        groundCoverTarget = null;
        groundAimTarget = null;
        groundAimTick = Integer.MIN_VALUE;
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
        resetEscape();
        combatAction = AerialCombatPolicy.Action.CHASE;
        defensiveHeading = Vec3.ZERO;
        targetSinceTick = 0;
        lastTargetSearchTick = Long.MIN_VALUE / 2;
        evadeSideUntil = 0;
    }

    private void syncFlightControl() {
        boolean requested = autoControlElytra.getValue();
        var armor = GuizhouWare.moduleManager.getModuleByClass(dev.guizhou.features.modules.movement.ArmorFlyModule.class);
        boolean needsArmor = requested && autoArmorFly.getValue() && mode.getValue() != Mode.FOLLOW
                && target != null && isValid(target) && !paused();
        if (needsArmor && armor != null && !armor.isEnabled()
                && !dev.guizhou.features.modules.movement.SLMArmorFlyModule.selected()) {
            armor.enable();
            armorEnabledByChase = true;
        } else if (!needsArmor) releaseArmorControl();
        if (requested == controllingFlight) return;
        var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        if (flight == null) return;
        if (requested) flight.enableByChase();
        else flight.releaseByChase();
        controllingFlight = requested;
    }

    private void releaseArmorControl() {
        if (!armorEnabledByChase) return;
        armorEnabledByChase = false;
        var armor = GuizhouWare.moduleManager.getModuleByClass(dev.guizhou.features.modules.movement.ArmorFlyModule.class);
        if (armor != null && armor.isEnabled()) armor.disable();
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
        if (MaceCombat.isMace(stack)) return AerialDuelPredictor.Weapon.MACE;
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
        Vec3 selfVelocity = combatVelocity();
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
                    && input.targetPosition().add(input.targetVelocity().scale(age)).distanceToSqr(observedTargetPosition()) <= 2.25
                    && input.selfVelocity().distanceToSqr(selfVelocity) <= 0.36
                    && input.targetVelocity().distanceToSqr(velocity) <= 0.36
                    && input.targetLook().dot(observedTargetLook()) >= 0.85) {
                duelPrediction = done.output();
                acceptedDuel = request;
                predictionPhase = phase;
                countPrediction(done, true);
            } else {
                countPrediction(done, false);
            }
        }
        var input = new AerialDuelPredictor.Input(mc.player.position(), selfVelocity,
                mc.player.getBbWidth(), mc.player.getBbHeight(), observedTargetPosition(), velocity,
                target.getEyePosition().add(targetPacketOffset()), observedTargetLook(), reach, weapon, target.onGround(),
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
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) {
            endEatingRetreat();
            dev.guizhou.manager.HeartbeatManager.notifyDeniedOnce(); return;
        }
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
            resetEscape();
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
            resetEscape();
            tactics.reset();
            combatAction = AerialCombatPolicy.Action.CHASE;
            defensiveHeading = Vec3.ZERO;
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
            endEatingRetreat();
            invalidatePlanning();
            maceDive.requestRecovery();
            weapons.release();
            clearSpearAim();
            maceFeedback.clear();
            behavior.enter();
            defensiveHeading = Vec3.ZERO;
            combatAction = AerialCombatPolicy.Action.CHASE;
            tactics.reset();
            resetEscape();
            return;
        }
        if (terrainEscaping()) {
            maceDive.requestRecovery();
            weapons.pauseUse();
            clearSpearAim();
            behavior.enter();
            defensiveHeading = Vec3.ZERO;
            combatAction = AerialCombatPolicy.Action.CHASE;
            return;
        }
        if (updateEatingRetreat()) return;
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

    private boolean updateEatingRetreat() {
        if (!dev.guizhou.manager.HeartbeatManager.authorized() || !isEatingRetreatRequested()) {
            endEatingRetreat();
            return false;
        }
        if (!eatingRetreat) {
            eatingRetreat = true;
            invalidatePlanning();
            combatPhase++;
            attackGeneration++;
            maceFeedback.clear();
            tactics.reset();
            resetEscape();
            behavior.enter();
            ClientDiagnostics.count("air.eating.enter", 1);
        }
        // Keep the selected weapon throughout an offhand meal, so restoring an unrelated
        // slot cannot revoke the user's food request. Only owned spear use is relinquished.
        weapons.pauseUse();
        weaponControlActive = false;
        clearSpearAim();
        clearDiveAim();
        if (GuizhouWare.rotationManager != null) GuizhouWare.rotationManager.cancel("ElytraChase_attack");
        maceDive.requestRecovery();
        combatAction = AerialCombatPolicy.Action.RETREAT;
        List<Entity> enemies = new ArrayList<>();
        enemies.add(target);
        for (Player enemy : mc.level.players()) {
            if (enemy != target && isValid(enemy)) enemies.add(enemy);
        }
        enemies.sort(Comparator.comparingDouble(mc.player::distanceToSqr));
        List<EatingRetreat.Threat> threats = new ArrayList<>();
        for (int i = 0; i < Math.min(8, enemies.size()); i++) {
            Entity enemy = enemies.get(i);
            double reach = enemy instanceof Player player ? SpearCombat.isSpear(player.getMainHandItem())
                    ? SpearCombat.maxRange(player, player.getMainHandItem()) : player.entityInteractionRange() : 3;
            Vec3 position = enemy == target ? enemy.position().add(targetPacketOffset()) : enemy.position();
            Vec3 velocity = enemy == target ? targetHistory.predictionVelocity(enemy.getDeltaMovement()) : enemy.getDeltaMovement();
            threats.add(new EatingRetreat.Threat(position, velocity, reach));
        }
        Vec3 escape = EatingRetreat.direction(mc.player.position(), combatVelocity(), threats,
                mc.player.getLookAngle().scale(-1), eatingDistance.getValue(), eatingHeight.getValue(),
                latencyTicks, this::eatingPathClear);
        defensiveHeading = escape.scale(10);
        return true;
    }

    private boolean eatingPathClear(Vec3 step) {
        var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        if (flight == null || step.lengthSqr() < 1.0E-9) return false;
        Vec3 origin = mc.player.position();
        Vec3 direction = step.normalize();
        return flight.clearNavigationPath(origin, origin.add(direction.scale(10)))
                && FlightTerrainSafety.trajectoryClear(mc.player.getDeltaMovement(), direction,
                mc.player.getAttributeValue(Attributes.GRAVITY),
                12, flight.isBoostAttached(), (from, to) -> flight.clearNavigationPath(
                        origin.add(from), origin.add(to)));
    }

    /** Cleanup is allowed after revocation, and never releases somebody else's meal. */
    private void endEatingRetreat() {
        if (!eatingRetreat) return;
        eatingRetreat = false;
        invalidatePlanning();
        combatPhase++;
        attackGeneration++;
        defensiveHeading = Vec3.ZERO;
        combatAction = AerialCombatPolicy.Action.CHASE;
        tactics.reset();
        resetEscape();
        clearSpearAim();
        clearDiveAim();
        if (behavior != null) behavior.enter();
        ClientDiagnostics.count("air.eating.exit", 1);
    }

    private boolean updateDefense() {
        AerialCombatPolicy.Action next = AerialCombatPolicy.Action.CHASE;
        boolean maceThreat = false;
        boolean spearThreat = false;
        boolean urgent = false;
        EvadeCommitment.Key threatKey = null;
        List<PearlLandingThreat.Threat> pearls = List.of();
        PearlLandingThreat.Threat pearl = null;
        var prediction = duelPrediction;
        Vec3 observedTarget = target.position().add(targetPacketOffset());
        if (adaptiveCombat.getValue() && behaviorMode != Mode.FOLLOW) {
            Vec3 velocity = targetHistory.predictionVelocity(target.getDeltaMovement());
            maceThreat = fallingMaceThreat(velocity);
            spearThreat = target instanceof Player player
                    && SpearCombat.threatens(player, mc.player, spearAntiSpearExtraDistance.getValue(), 0.25);
            boolean predictedThreat = prediction != null && prediction.severity() >= 0.5
                    && mc.player.hasLineOfSight(target);
            pearls = pearlDefense.getValue() ? pearlThreats() : List.of();
            pearl = pearls.stream().min(Comparator.comparingInt(PearlLandingThreat.Threat::arrivalTicks)
                    .thenComparing(Comparator.comparingDouble(PearlLandingThreat.Threat::severity).reversed())).orElse(null);
            urgent = spearThreat || maceThreat
                    || predictedThreat && AerialDuelPredictor.shouldInterruptAttack(prediction, true,
                    mc.player.getHealth(), mc.player.getAbsorptionAmount(), mc.player.getMaxHealth());
            urgent |= pearl != null && pearl.urgent();
            if (maceThreat && !observedMaceThreat) maceThreatEpisode++;
            observedMaceThreat = maceThreat;
            if (spearThreat) {
                Player attacker = (Player) target;
                threatKey = new EvadeCommitment.Key(session, target.getId(), "spear",
                        attacker.tickCount - attacker.getTicksUsingItem());
            } else if (maceThreat) {
                threatKey = new EvadeCommitment.Key(session, target.getId(), "mace", maceThreatEpisode);
            } else if (predictedThreat) {
                threatKey = new EvadeCommitment.Key(session, target.getId(), "predicted:" + prediction.threatKind(), 0);
            }
            if (pearl != null && (threatKey == null || pearl.urgent() && !spearThreat && !maceThreat))
                threatKey = new EvadeCommitment.Key(session, pearl.pearlId(), "pearl", pearl.ownerId());
            else if (pearl != null && pearl.urgent())
                threatKey = new EvadeCommitment.Key(session, threatKey.entity(),
                        threatKey.kind() + ":pearl:" + pearl.pearlId(), threatKey.attack());
            boolean preserveStrike = (realMaceHitWindow() || maceDropWindow() || spearApproachWindow()) && !urgent;
            var threatInput = new AerialCombatPolicy.ThreatInput(mc.player.tickCount,
                    policyVector(observedTarget.subtract(mc.player.position())),
                    policyVector(combatVelocity()), policyVector(velocity),
                    spearThreat, maceThreat, mc.player.getHealth(), mc.player.getAbsorptionAmount(),
                    mc.player.getMaxHealth(), false, latencyTicks,
                    threatMode.getValue() == ThreatMode.ALL_PLAYERS && target instanceof Player);
            // Relative height does not decide a smash: a falling attacker can hit
            // upward. Use its standing-eye reach window, not every closing glide.
            boolean approachThreat = !(target instanceof Player p && MaceCombat.isMace(p.getMainHandItem()))
                    || maceThreat;
            next = tactics.evaluate(threatInput, predictedThreat || pearl != null, preserveStrike, approachThreat);
            // The policy's clear-delay is not a new approach event: preserve an existing
            // escape across a brief lost observation instead of snapping back toward the target.
            if (next != AerialCombatPolicy.Action.CHASE && threatKey == null
                    && approachThreat && AerialCombatPolicy.incomingHighSpeed(threatInput))
                threatKey = new EvadeCommitment.Key(session, target.getId(), "approach", 0);
        } else {
            tactics.reset();
            resetEscape();
        }
        if (next != combatAction) {
            invalidatePlanning();
            combatPhase++;
            attackGeneration++;
            maceFeedback.clear();
            resetDefenseBehavior();
            clearSpearAim();
            combatAction = next;
            // Send a genuinely ready counter before equipment recovery clears the
            // natural fall. The selected escape still owns this tick's movement.
            tryDefensiveMaceAttack();
            maceDive.requestRecovery();
        }
        defensiveHeading = Vec3.ZERO;
        if (next == AerialCombatPolicy.Action.CHASE) {
            escapeCommitment.reset();
            lastDefenseDirection = Vec3.ZERO;
            return false;
        }
        clearSpearAim();
        maintainDefensiveSpearUse();
        Vec3 threatOrigin = pearl != null && !spearThreat && !maceThreat ? pearl.landing() : observedTarget;
        Vec3 away = mc.player.position().subtract(threatOrigin).multiply(1.0, 0.0, 1.0);
        if (away.lengthSqr() < 1.0E-6) away = mc.player.getLookAngle().multiply(-1.0, 0.0, -1.0);
        if (away.lengthSqr() < 1.0E-6) away = new Vec3(1.0, 0.0, 0.0);
        away = away.normalize();
        if (next == AerialCombatPolicy.Action.RETREAT) {
            defensiveHeading = away.add(0.0, maceThreat ? 0.0 : 0.2, 0.0).normalize().scale(10.0);
        } else {
            Vec3 side = new Vec3(-away.z, 0.0, away.x);
            double probe = Math.max(1.0, combatVelocity().length());
            if (mc.player.tickCount >= evadeSideUntil) {
                if (!pathClear(side.scale(evadeSide * probe)) && pathClear(side.scale(-evadeSide * probe))) {
                    evadeSide = -evadeSide;
                }
                evadeSideUntil = (long) mc.player.tickCount + 6;
            }
            defensiveHeading = side.scale(evadeSide).add(away.scale(0.35)).normalize().scale(10.0);
            if (pearl == null && prediction != null && prediction.severity() >= 0.5) {
                Vec3 preferred = prediction.recommendedDirection();
                if (preferred.lengthSqr() > 1.0E-6 && pathClear(preferred.scale(probe))) {
                    defensiveHeading = preferred.normalize().scale(10.0);
                }
            }
        }
        // Score both sides against every observed arrival, not just the nearest pearl.
        double probe = Math.max(1.0, combatVelocity().length());
        Vec3 side = new Vec3(-away.z, 0.0, away.x);
        Vec3 candidate = defensiveHeading.normalize();
        if (evadeCommitment.getValue() || !pearls.isEmpty()) {
            Vec3 best = Vec3.ZERO;
            double bestScore = -Double.MAX_VALUE;
            for (Vec3 direction : new Vec3[]{candidate, side.add(away.scale(0.35)).normalize(),
                    side.scale(-1).add(away.scale(0.35)).normalize(), away}) {
                if (!pathClear(direction.scale(probe))) continue;
                double score = direction.dot(candidate) * 0.1;
                if (!pearls.isEmpty()) {
                    double worst = Double.POSITIVE_INFINITY;
                    for (var hazard : pearls) worst = Math.min(worst,
                            PearlLandingThreat.clearance(mc.player.position(), direction, probe, hazard));
                    // Also retain distance from an immediate weapon attacker.
                    if (spearThreat || maceThreat) worst = Math.min(worst,
                            mc.player.position().add(direction.scale(probe * 2)).distanceTo(observedTarget) / 4.0);
                    score += worst;
                }
                if (score > bestScore) { bestScore = score; best = direction; }
            }
            candidate = best;
        }
        // Kinetic damage requires positive forward speed. A pure sideways/backward
        // dodge can keep a charged spear aimed but never satisfy that condition.
        Vec3 counterPass = pearl == null && !maceThreat && next == AerialCombatPolicy.Action.EVADE
                ? spearCounterPass(side) : Vec3.ZERO;
        if (counterPass.lengthSqr() > 1.0E-9) {
            candidate = counterPass;
            if (threatKey != null) threatKey = new EvadeCommitment.Key(threatKey.session(),
                    threatKey.entity(), threatKey.kind() + ":spear-counter", threatKey.attack());
        }
        if (evadeCommitment.getValue()) {
            var choice = escapeCommitment.update(mc.player.tickCount, threatKey, urgent, candidate,
                    evadeHoldTicks.getValue(), evadeRearm.getValue() ? 2 : 0,
                    d -> d.lengthSqr() > 1e-9 && pathClear(d.scale(probe)));
            candidate = choice.direction();
            ClientDiagnostics.count("air.evade." + choice.reason().name().toLowerCase(java.util.Locale.ROOT), 1);
        } else escapeCommitment.reset();
        if (lastDefenseDirection.lengthSqr() > 0 && candidate.dot(lastDefenseDirection) < 0)
            ClientDiagnostics.count("air.evade.reversal", 1);
        lastDefenseDirection = candidate;
        defensiveHeading = candidate.scale(10.0);
        tryDefensiveMaceAttack();
        aimDefensiveSpear();
        return true;
    }

    /** A short charged crossing keeps lateral clearance and enough forward motion to damage.
     * Retreat, pearls, mace threats, eating and uncharged hands retain the escape. */
    private Vec3 spearCounterPass(Vec3 side) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()
                || !spearPhase() || paused() || !isValid(target) || !weapons.isHoldingUse()
                || !SpearCombat.isReady(mc.player)
                || !mc.player.hasLineOfSight(target)) return Vec3.ZERO;
        Vec3 eye = spearAttackEye();
        AABB box = observedTargetBox();
        double distance = Math.sqrt(box.distanceToSqr(eye));
        if (distance < SpearCombat.minRange(mc.player, mc.player.getUseItem()) + 0.35
                || !AerialEngagement.closingWindow(box.getCenter().subtract(eye), combatVelocity(),
                targetHistory.predictionVelocity(target.getDeltaMovement()), distance,
                SpearCombat.maxRange(mc.player, mc.player.getUseItem()) + 1.0, 2)) return Vec3.ZERO;
        Vec3 toward = box.getCenter().subtract(eye).normalize();
        Vec3 preferred = toward.scale(0.6).add(side.scale(evadeSide * 0.8)).normalize();
        double probe = Math.max(1.0, combatVelocity().length());
        if (pathClear(preferred.scale(probe))) {
            ClientDiagnostics.count("air.spear.counter.pass", 1);
            return preferred;
        }
        Vec3 alternate = toward.scale(0.6).subtract(side.scale(evadeSide * 0.8)).normalize();
        return pathClear(alternate.scale(probe)) ? alternate : Vec3.ZERO;
    }

    /** Keep a ready owned spear aimed during a sidestep; the escape still owns movement. */
    private void aimDefensiveSpear() {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()
                || combatAction != AerialCombatPolicy.Action.EVADE || !spearPhase()
                || paused() || !isValid(target) || !weapons.isHoldingUse()
                || !SpearCombat.isReady(mc.player)
                || !mc.player.hasLineOfSight(target)) return;
        Vec3 eye = spearAttackEye();
        AABB box = observedTargetBox();
        Vec3 toward = box.getCenter().subtract(eye).normalize();
        double movementExtension = Math.max(0.0, combatVelocity().dot(toward));
        double reach = SpearCombat.maxRange(mc.player, mc.player.getUseItem()) + movementExtension;
        double distance = Math.sqrt(box.distanceToSqr(eye));
        if (distance < SpearCombat.minRange(mc.player, mc.player.getUseItem()) || distance > reach) return;
        aimSpear();
        ClientDiagnostics.count("air.spear.counter.aim", 1);
    }

    /** A kinetic query uses the moved eye, not the eye from this tick's earlier planning.
     * Enabled for direct NoAC movement or protocol-verified dual view; listeners retain vetoes. */
    private void tryPostMovementSpearAim() {
        // isHoldingUse checks this controller's exact spear stack, inventory ownership,
        // and the meal latch. Generic main-hand-use deferral would reject every charge.
        if (!dev.guizhou.manager.HeartbeatManager.authorized()
                || !spearPhase() || paused() || !isValid(target) || !weapons.isHoldingUse()
                || !SpearCombat.isReady(mc.player)
                || GuizhouWare.rotationManager == null
                || !GuizhouWare.rotationManager.isActive("ElytraChase_spear")
                || !mc.player.hasLineOfSight(target)) return;
        var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        if (flight == null || !flight.directFlightConfigured()
                && (!flight.isCombatDualViewApplied() || dev.guizhou.util.elytra.DualViewSupport.status()
                != dev.guizhou.util.elytra.DualViewSupport.Status.SUPPORTED)) return;
        Vec3 eye = spearAttackEye();
        AABB box = observedTargetBox();
        Vec3 aim = aimKind() == EntityAim.Kind.PLAYER ? box.getCenter() : EntityAim.facingPoint(eye, box);
        Vec3 toward = aim.subtract(eye).normalize();
        double reach = SpearCombat.maxRange(mc.player, mc.player.getUseItem())
                + Math.max(0.0, combatVelocity().dot(toward));
        double distance = Math.sqrt(box.distanceToSqr(eye));
        if (distance < SpearCombat.minRange(mc.player, mc.player.getUseItem()) || distance > reach) return;
        float[] angles = MathUtil.calcAngle(eye, aim);
        if (aimKind() == EntityAim.Kind.PLAYER && !targetGrounded
                && aimChoice().pattern() == EntityAim.Pattern.USE_EXISTING) {
            // Refresh the moved eye without undoing this tick's uncertainty sweep.
            angles[0] = SpearAimSweep.applySweep(angles[0], Math.max(0, spearSweepPhase - 1));
        }
        if (dev.guizhou.util.PacketSendUtil.send(new ServerboundMovePlayerPacket.Rot(
                angles[0], angles[1], mc.player.onGround(), mc.player.horizontalCollision))) {
            ClientDiagnostics.count("air.spear.post.look.sent", 1);
        }
    }

    /** A ready smash may accompany an escape; it must not replace its heading or swap gear. */
    private void tryDefensiveMaceAttack() {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()
                || combatAction != AerialCombatPolicy.Action.EVADE || paused() || !isValid(target)
                || !willUseMaceAttack() || maceFeedback.pending(target.getId(), mc.player.tickCount)
                || maceDive.isActive() && !maceDive.canAttack()) return;
        Behavior active = behavior instanceof MxsBehavior mxs ? mxs.active : behavior;
        if (!(active instanceof MaceBehavior mace) || !attack(target)) return;
        mace.lastAttackSentTick = mc.player.tickCount;
        mace.state = MaceBehavior.WAIT_ATTACK;
        mace.terminal = false;
        mace.passHeight = Double.NaN;
        ClientDiagnostics.count("air.mace.counter.sent", 1);
    }

    private void resetEscape() {
        escapeCommitment.reset();
        observedMaceThreat = false;
        maceThreatEpisode = 0;
        lastDefenseDirection = Vec3.ZERO;
    }

    private List<PearlLandingThreat.Threat> pearlThreats() {
        long started = ClientDiagnostics.startTimer();
        try {
            var candidates = mc.level.getEntitiesOfClass(ThrownEnderpearl.class,
                    mc.player.getBoundingBox().inflate(48), p -> p.isAlive()
                            && p.getOwner() instanceof Player owner && owner != mc.player
                            && owner.isAlive() && !owner.isSpectator()
                            && !GuizhouWare.friendManager.isFriend(owner)
                            && (targetRules == null || targetRules.isValidTarget(owner)));
            candidates.sort(Comparator.comparingDouble(p -> {
                Vec3 offset = p.position().subtract(mc.player.position());
                Vec3 relative = p.getDeltaMovement().subtract(mc.player.getDeltaMovement());
                double t = relative.lengthSqr() < 1e-9 ? 0
                        : Math.clamp(-offset.dot(relative) / relative.lengthSqr(), 0, 12);
                return offset.add(relative.scale(t)).lengthSqr();
            }));
            List<PearlLandingThreat.Threat> result = new ArrayList<>();
            for (int i = 0; i < Math.min(4, candidates.size()); i++) {
                var p = candidates.get(i);
                Player owner = (Player) p.getOwner();
                var trajectory = ProjectilePrediction.simulate(p, 12);
                if (!trajectory.landed()) continue;
                double[] end = trajectory.end();
                double reach = SpearCombat.isSpear(owner.getMainHandItem()) ? 8.0 : 3.0;
                var hazard = PearlLandingThreat.assess(p.getId(), owner.getId(), new Vec3(end[0], end[1], end[2]),
                        trajectory.ticks(), mc.player.position(), mc.player.getDeltaMovement(), reach, true);
                if (hazard != null) result.add(hazard);
            }
            ClientDiagnostics.count("air.pearl.hazards", result.size());
            return result;
        } finally {
            ClientDiagnostics.endTimer("air.pearl.sample", started);
        }
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
        if (!(active instanceof MaceBehavior mace)
                || mace.state != MaceBehavior.FOLLOW && mace.state != MaceBehavior.PULL_UP
                || !willUseMaceAttack() || mc.player.isUsingItem() || EatUtil.shouldDefer()
                || mc.player.getDeltaMovement().y >= -0.05
                || maceDive.isActive() && !maceDive.canAttack()) return false;
        double reach = mc.player.entityInteractionRange();
        return observedTargetBox().distanceToSqr(mc.player.getEyePosition()) <= reach * reach
                && mc.player.hasLineOfSight(target);
    }

    private boolean maceDropWindow() {
        Behavior active = behavior instanceof MxsBehavior mxs ? mxs.active : behavior;
        if (!(active instanceof MaceBehavior mace) || mace.state != MaceBehavior.FOLLOW) return false;
        // A rising opponent entering our proven drop path is not by itself a reason
        // to discard the attack. Immediate weapon and projectile hazards still win.
        if (maceDive.isActive() && mace.dropPathClear() || mace.shouldStartDive()) return true;
        // The glide-exit gate intentionally requires several possible hit ticks.
        // Do not reuse that strict gate to cancel a descending approach before it
        // reaches the exit window: a rising enemy otherwise looks like a collision.
        if (!autoMaceDive.getValue() || !mc.player.isFallFlying()
                || mc.player.getDeltaMovement().y >= -.15
                || mc.player.getY() < observedTargetBox().minY + 3
                || !mc.player.hasLineOfSight(target) || !mace.dropPathClear()) return false;
        return AerialIntercept.dropHitTick(mc.player.position().add(0,
                mc.player.getEyeHeight(Pose.STANDING), 0), mc.player.getDeltaMovement(),
                observedTargetBox(), targetHistory.predictionVelocity(target.getDeltaMovement()),
                mc.player.entityInteractionRange(), maceMinFallDistance.getValue(), 10) > 0;
    }

    private Vec3 spearAttackEye() {
        // The armor cycle's ownership does not imply a standing server pose.
        // Kinetic queries during powered spear passes retain the real glide eye.
        return mc.player.getEyePosition();
    }

    /** Preserve only a charged, visible approach against a non-urgent prediction.
     * This radial opportunity is not a claim of a hit; actual weapon threats still interrupt. */
    private boolean spearApproachWindow() {
        Behavior active = behavior instanceof MxsBehavior mxs ? mxs.active : behavior;
        if (!(active instanceof SpearBehavior spear) || spear.state == SpearBehavior.PULL_OVER
                || spear.makingChargeRoom || !weapons.isHoldingUse() || !SpearCombat.isReady(mc.player)
                || !mc.player.hasLineOfSight(target)) {
            spearCommit.reset();
            return false;
        }
        double distance = Math.sqrt(observedTargetBox().distanceToSqr(spearAttackEye()));
        return spearCommit.admit(mc.player.tickCount, target,
                distance >= SpearCombat.minRange(mc.player, mc.player.getUseItem()) + 0.35
                && distance <= SpearCombat.maxRange(mc.player, mc.player.getUseItem()));
    }

    private boolean fallingMaceThreat(Vec3 velocity) {
        if (!(target instanceof Player player) || !MaceCombat.isMace(player.getMainHandItem())
                || player.isFallFlying() && player.getItemBySlot(EquipmentSlot.CHEST)
                .has(net.minecraft.core.component.DataComponents.GLIDER)
                || player.onGround() || !mc.player.hasLineOfSight(player)) return false;
        Vec3 eye = player.position().add(targetPacketOffset())
                .add(0, player.getEyeHeight(Pose.STANDING), 0);
        return AerialEngagement.maceThreatWindow(eye, velocity, mc.player.getBoundingBox(),
                combatVelocity(), player.entityInteractionRange(), latencyTicks + 1);
    }

    private static AerialCombatPolicy.Vector policyVector(Vec3 vector) {
        return new AerialCombatPolicy.Vector(vector.x, vector.y, vector.z);
    }

    private Vec3 combatVelocity() {
        var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        return flight != null ? flight.observedCombatVelocity() : mc.player.getDeltaMovement();
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
        escapeCommitment.reset();
        ClientDiagnostics.count("air.evade.terrain_override", 1);
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
        if (!targetGrounded) {
            lowGroundCover = false;
            groundCoverHoldUntil = Long.MIN_VALUE;
        }
        else if (groundCoverTarget != target || mc.player.tickCount < groundCoverTick
                || (long) mc.player.tickCount - groundCoverTick >= 4) {
            if (groundCoverTarget != target || mc.player.tickCount < groundCoverTick) groundCoverHoldUntil = Long.MIN_VALUE;
            groundCoverTarget = target;
            groundCoverTick = mc.player.tickCount;
            lowGroundCover = mc.level.getBlockCollisions(target, observedTargetBox().move(0, .05, 0)
                    .expandTowards(0, Math.clamp(maceMinFallDistance.getValue() + 1, 2, 4), 0)).iterator().hasNext();
            // Knockback can move the victim just outside a doorway while we are still under its roof.
            // Finish leaving that doorway before asking for another automatic climb.
            if (!lowGroundCover && Math.abs(mc.player.getY() - target.getY()) < 2
                    && mc.player.distanceToSqr(target) < 12 * 12) {
                Vec3 p = mc.player.position();
                lowGroundCover = mc.level.getBlockCollisions(mc.player, new AABB(
                        p.x - .3, p.y + .05, p.z - .3, p.x + .3, p.y + 2.8, p.z + .3)).iterator().hasNext();
            }
            if (lowGroundCover) groundCoverHoldUntil = (long) mc.player.tickCount + 40;
            else lowGroundCover = mc.player.tickCount <= groundCoverHoldUntil
                    && Math.abs(mc.player.getY() - target.getY()) < 2 && mc.player.distanceToSqr(target) < 12 * 12;
        }
        long previousRevision = targetHistory.revision();
        boolean gliding = target instanceof LivingEntity living && living.isFallFlying();
        var chest = ((LivingEntity) target).getItemBySlot(EquipmentSlot.CHEST);
        TargetMotionTracker.Equipment equipment = chest.is(Items.ELYTRA) ? TargetMotionTracker.Equipment.ELYTRA
                : chest.is(ItemTags.CHEST_ARMOR) ? TargetMotionTracker.Equipment.ARMOR : TargetMotionTracker.Equipment.OTHER;
        targetHistory.sampleServer(observedTargetPosition(), mc.player.tickCount, targetGrounded, gliding, equipment,
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
        int dropSetupTick = Integer.MIN_VALUE / 2;
        int chargeDeferredTick = Integer.MIN_VALUE / 2;
        int chargeDeferredTicks;
        int climbingTicks;
        boolean pressuring;
        int lastPressureAttackTick = Integer.MIN_VALUE / 2;
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
            dropSetupTick = Integer.MIN_VALUE / 2;
            chargeDeferredTick = Integer.MIN_VALUE / 2;
            chargeDeferredTicks = 0;
            climbingTicks = 0;
            pressuring = false;
            lastPressureAttackTick = Integer.MIN_VALUE / 2;
            pass.reset();
        }

        @Override
        void tick() {
            climbingTicks = state == PULL_UP && mc.player.getDeltaMovement().y > .2 && !maceDive.isActive()
                    ? Math.min(200, climbingTicks + 1) : 0;
            pressuring = false;
            if (wasDiving && !maceDive.isActive()) {
                naturalFallTicks = mc.player.fallDistance < maceMinFallDistance.getValue() ? 0 : 12;
                wasDiving = false;
                if (state != WAIT_ATTACK) state = PULL_UP;
            }
            if (!maceDive.isActive() && state == PULL_UP && willUseMaceAttack()
                    && mc.player.getDeltaMovement().y < -.05 && tryAttack(true)) return;
            if (!isValid(target) || !flyingOrCreative() && !fallingWindow() && !maceDive.isActive()
                    && groundCoverDestination() == null) {
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

            if (targetGrounded && lowGroundCover && maceAdaptive.getValue()) {
                // A roof cannot supply an unpowered smash lane. Approach under it
                // instead of repeatedly asking for an apex inside solid blocks.
                heading = targetHistory.predictionVelocity(target.getDeltaMovement()).add(
                        observedTargetPosition().add(0, .12, 0).subtract(mc.player.position()).scale(.5));
                terminal = true;
                catchingUp = false;
                state = FOLLOW;
                tryAttack(armed);
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
            return mc.player != null && (state == FOLLOW || state == WAIT_ATTACK
                    || state == PULL_UP && realMaceHitWindow())
                    && naturalFallTicks < fallCap && !mc.player.isFallFlying() && !mc.player.onGround()
                    && !mc.player.isInWater() && !mc.player.isInLava() && !mc.player.isPassenger()
                    && mc.player.getDeltaMovement().y < -0.05;
        }

        /** An armor-switch exit is not a committed dive. Retain it only while
         * current momentum can still bring the attack into reach. */
        boolean retainNaturalFall() {
            if (maceDive.isActive()) return true;
            Vec3 motion = mc.player.getDeltaMovement();
            if (motion.y >= -0.05) return false;
            // The powered setup already reserved this short glide-exit window.
            // Do not reopen wings between setup and impact just because the next
            // interpolated target sample changes its velocity estimate.
            long setupAge = (long) mc.player.tickCount - dropSetupTick;
            if (setupAge >= 0 && setupAge <= 2 && dropPathClear()) return true;
            AABB box = observedTargetBox();
            double reach = mc.player.entityInteractionRange();
            if (box.distanceToSqr(mc.player.getEyePosition()) <= reach * reach) return true;
            Vec3 eye = mc.player.position().add(0, mc.player.getEyeHeight(Pose.STANDING), 0);
            return AerialIntercept.dropHitTick(eye, motion, box,
                    targetHistory.predictionVelocity(target.getDeltaMovement()),
                    reach, maceMinFallDistance.getValue(), 10) > 0 && dropPathClear();
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
            // Adaptive height is a damage preference, not a reason to fly past
            // an already reachable unpowered strike on a moving opponent.
            if (maceAdaptive.getValue() && autoMaceDive.getValue() && mc.player.isFallFlying()
                    && mc.player.getY() >= observedTargetBox().minY + minimumDropSetupHeight()) {
                Vec3 setup = AerialIntercept.dropSetupVelocity(
                        mc.player.position().add(0, mc.player.getEyeHeight(Pose.STANDING), 0),
                        observedTargetBox(), targetHistory.predictionVelocity(target.getDeltaMovement()),
                        planningSpeed(), mc.player.entityInteractionRange(), maceMinFallDistance.getValue(), this::achievableSetup);
                if (setup != null) {
                    heading = setup;
                    terminal = true;
                    dropSetupTick = mc.player.tickCount;
                    pass.reset();
                    return FOLLOW;
                }
            }
            var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
            Vec3 rising = targetHistory.predictionVelocity(target.getDeltaMovement());
            if (noacPressure.getValue() && adaptiveCombat.getValue() && flight != null && flight.directFlightConfigured()
                    && dev.guizhou.util.elytra.DirectFlightControl.macePressure(climbingTicks,
                    mc.player.getDeltaMovement().y, rising.y, 1, maceDive.isActive())
                    && !maceFeedback.pending(target.getId(), mc.player.tickCount)) {
                // Hold a reachable lane instead of continually extending the high apex.
                // Armor flight changes glide flags every window; sustained ascent is
                // measured from actual vertical motion, independent of those flags.
                Vec3 offset = observedTargetPosition().subtract(mc.player.position());
                heading = dev.guizhou.util.elytra.DirectFlightControl.spearLane(
                        rising.add(offset.multiply(.5,0,.5)), offset.y, rising.y);
                terminal = true;
                catchingUp = false;
                pressuring = true;
                if (mc.player.getAttackStrengthScale(.5F) > .95F
                        && dev.guizhou.manager.HeartbeatManager.authorized() && attack(target)) {
                    lastPressureAttackTick = mc.player.tickCount;
                    ClientDiagnostics.count("air.noac.mace.pressure", 1);
                }
                return PULL_UP;
            }
            boolean headBlocked = blockedInDirection(0.0, 0.1, 0.0);
            MaceApproach.Plan plan = climbPlan();
            Vec3 intercept = plan.aim();
            double aboveY = intercept.y;
            catchingUp = !plan.reachable();
            // The led apex is a steering destination, not a moving completion bar.
            // A rising target must not keep extending this climb after we have the
            // chosen relative fall height; the drop gate separately validates impact.
            boolean reached = mc.player.getY() >= observedTargetBox().minY + plan.height() - 0.1;
            if (!catchingUp && (reached || headBlocked)) {
                pass.reset();
                state = FOLLOW;
                if (reached && !headBlocked && autoMaceDive.getValue() && mc.player.isFallFlying()
                        && mc.player.getY() >= observedTargetBox().minY + minimumDropSetupHeight()) return tickFollow(true);
                heading = strikePlan().aim().subtract(mc.player.getEyePosition());
                return FOLLOW;
            }
            heading = new Vec3(intercept.x, aboveY, intercept.z)
                    .subtract(mc.player.position());
            if (catchingUp && heading.length() < 5.0) {
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
            if (autoMaceDive.getValue() && mc.player.isFallFlying()
                    && mc.player.getY() >= observedBox.minY + minimumDropSetupHeight()) {
                Vec3 velocity = targetHistory.predictionVelocity(target.getDeltaMovement());
                Vec3 setup = AerialIntercept.dropSetupVelocity(
                        mc.player.position().add(0, mc.player.getEyeHeight(Pose.STANDING), 0),
                        observedBox, velocity, planningSpeed(), reach, maceMinFallDistance.getValue(), this::achievableSetup);
                if (setup != null) {
                    heading = setup;
                    terminal = true;
                    dropSetupTick = mc.player.tickCount;
                    return FOLLOW;
                }
                // Match horizontal motion before spending the height advantage.
                // A direct downward intercept here can leave the armor-exit fall
                // several blocks to the side with no powered steering left.
                heading = AerialEngagement.maceStagingStep(observedTargetPosition().subtract(mc.player.position()),
                        velocity, maceHeight.getValue(), planningSpeed());
                terminal = true;
                return FOLLOW;
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

        private double minimumDropSetupHeight() {
            var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
            // Direct flight can establish downward velocity without spending a glide
            // turn. The natural-fall solver still has to prove reach and smash charge.
            return flight != null && flight.directFlightConfigured() ? 1.0 : 3.0;
        }

        private double planningSpeed() {
            // Use available flight speed, not last tick's deliberate terminal braking.
            // Feeding the short request back into reachability strands a pass beside
            // a rising target and progressively removes the descent budget.
            var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
            double available = flight != null ? flight.combatPlanningSpeed() : 0.0;
            return Math.clamp(Math.max(mc.player.getDeltaMovement().length(), available), 0.5, 15.0);
        }

        private Vec3 achievableSetup(Vec3 request) {
            var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
            return flight == null ? request : flight.previewPrecisionVelocity(request);
        }

        private MaceApproach.Plan climbPlan() {
            if (aimKind() == EntityAim.Kind.SLIME || aimChoice().pattern() != EntityAim.Pattern.USE_EXISTING
                    && aimKind() != EntityAim.Kind.PLAYER) {
                Vec3 at = aimKind() == EntityAim.Kind.SLIME ? observedTargetBox().getCenter() : predictedTarget();
                double height = MaceApproach.chargeHeight(maceHeight.getValue(), maceMinFallDistance.getValue());
                return new MaceApproach.Plan(new Vec3(at.x, observedTargetBox().minY + height, at.z), height, 0.0, true);
            }
            if (aimKind() == EntityAim.Kind.PLAYER && aimChoice().pattern() != EntityAim.Pattern.USE_EXISTING) {
                Vec3 at = predictedTarget();
                double height = MaceApproach.chargeHeight(maceHeight.getValue(), maceMinFallDistance.getValue());
                return new MaceApproach.Plan(new Vec3(at.x, observedTargetBox().minY + height, at.z), height, 0.0, true);
            }
            if (aimKind() != EntityAim.Kind.PLAYER) {
                Vec3 center = observedTargetBox().getCenter();
                double height = MaceApproach.chargeHeight(maceHeight.getValue(), maceMinFallDistance.getValue());
                return new MaceApproach.Plan(new Vec3(center.x, observedTargetBox().minY + height, center.z), height, 0.0, true);
            }
            if (!maceAdaptive.getValue()) {
                Vec3 lead = predictedTarget();
                double height = MaceApproach.chargeHeight(maceHeight.getValue(), maceMinFallDistance.getValue());
                return new MaceApproach.Plan(new Vec3(lead.x, observedTargetBox().minY + height, lead.z),
                        height, 0.0, true);
            }
            Vec3 velocity = targetHistory.predictionVelocity(target.getDeltaMovement());
            double preferred = maceHeight.getValue();
            double setupHeight = autoMaceDive.getValue() ? AerialIntercept.minimumSetupHeight(observedTargetBox(),
                    velocity, planningSpeed(), mc.player.entityInteractionRange(), maceMinFallDistance.getValue(),
                    mc.player.getEyeHeight(Pose.STANDING), this::achievableSetup) : 0;
            // Commit a height once per pass. Feeding the last reduced height back as
            // the preferred height repeatedly shrinks it until the attack has no room.
            if (Double.isFinite(passHeight)) setupHeight = Math.max(setupHeight, Math.min(passHeight, preferred));
            MaceApproach.Plan plan = MaceApproach.climb(mc.player.position(), target.position().add(targetPacketOffset()), velocity,
                    planningSpeed(), preferred, maceMinFallDistance.getValue(), latencyTicks,
                    MaceApproach.stable(velocity, targetHistory.previousVelocity(), targetHistory.hasTwoVelocities()), setupHeight);
            // Only a feasible plan commits; a larger physical drop floor can raise it.
            // The next pass may adapt afresh after feedback or recovery resets passHeight.
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
                Vec3 aim = dev.guizhou.util.elytra.ObservedTargetPosition.forecastCenter(
                        observedTargetBox(), observedTargetPosition(), predictedTarget());
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
            // Under a low roof there is no automatic smash to reserve immunity for.
            boolean ready = smash || (!autoMaceDive.getValue() || targetGrounded && lowGroundCover)
                    && maceUseExtraAttack.getValue()
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
            chargeDeferredTicks = 0;
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
            chargeDeferredTicks = 0;
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
                    || mc.player.getDeltaMovement().y >= -0.15 || mc.player.getY() < observedTargetBox().minY + minimumDropSetupHeight()
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
            if (mc.player.getY() < observedTargetBox().minY + 0.5 && !realMaceHitWindow()
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
                chargeDeferredTicks = 0;
                return tickPullUp();
            }
            aimNextClimb();
            return WAIT_ATTACK;
        }

        @Override
        void onHit(int type) {
            if (type == 1) {
                state = PULL_UP;
                chargeDeferredTicks = 0;
            }
        }

        @Override
        void inject(FlightVelocity fv) {
            if (heading.lengthSqr() > 1.0E-9) {
                Vec3 v = heading;
                if (maceCustomPullUpAngle.getValue() > 0.1 && state == PULL_UP && !catchingUp) {
                    v = MaceApproach.pullUpIntent(v, maceCustomPullUpAngle.getValue());
                }
                var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
                boolean preciseClimb = false;
                boolean preciseCatchUp = catchingUp && (v.y < -.05
                        || targetHistory.predictionVelocity(target.getDeltaMovement()).y <= .15);
                // Brake at a nearby level/falling apex in the air as well as on the ground.
                // climbStep retains full thrust while buying height over a rising opponent.
                if ((state == PULL_UP || state == WAIT_ATTACK) && !catchingUp
                        && flight != null && flight.combatBoostConfigured()) {
                    var step = AerialEngagement.climbStep(v,
                            targetHistory.predictionVelocity(target.getDeltaMovement()),
                            Math.max(fv.maxVelocity(), planningSpeed()));
                    v = step.velocity();
                    preciseClimb = step.precision();
                }
                if (!preciseClimb && !preciseCatchUp && (state == PULL_UP || state == WAIT_ATTACK)
                        && flight != null && flight.respectsGlideMomentum()) {
                    v = AerialEngagement.pursuitBoost(v, combatVelocity());
                }
                double len = v.length();
                // Rescaling can make the measured flight faster than the base setting.
                // Do not cap target-matching terminal motion back to that base speed.
                double budget = terminal || preciseClimb ? Math.max(fv.maxVelocity(), planningSpeed()) : fv.maxVelocity();
                double max = Math.min(len, budget);
                fv.set(v.normalize().scale(max));
                if (state == FOLLOW && maceAdaptive.getValue()) fv.preserveDirection();
                // Preserve a deliberate descending/level pursuit, while a rising
                // opponent still permits the established powered climb acceleration.
                if (terminal || preciseClimb || preciseCatchUp) fv.requestPrecision();
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
        final SpearApproach.CommitWindow rocketCommit = new SpearApproach.CommitWindow();
        final dev.guizhou.util.elytra.SpearPassThroughHeading passThrough = new dev.guizhou.util.elytra.SpearPassThroughHeading();
        static final int PASS_THROUGH_HOLD_TICKS = 20;
        static final double PASS_THROUGH_DEADZONE = 0.2;

        @Override
        void enter() {
            super.enter();
            state = NONE;
            pulledAfterHit = false;
            pullOverTicks = 0;
            makingChargeRoom = false;
            preciseApproach = false;
            rocketCommit.reset();
            passThrough.reset();
            spearSweepPhase = 0;
        }

        Vec3 targetPos() {
            boolean straightPlayer = aimKind() == EntityAim.Kind.PLAYER
                    && aimChoice().pattern() == EntityAim.Pattern.USE_EXISTING;
            Vec3 base = predictedTarget();
            if (targetGrounded) return new Vec3(base.x, target.getEyeY() + targetPacketOffset().y, base.z);
            return straightPlayer ? dev.guizhou.util.elytra.ObservedTargetPosition.forecastCenter(
                    observedTargetBox(), observedTargetPosition(), base) : base;
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
            double chargeDistance = Math.max(spearRange.getValue(), AerialEngagement.chargeRange(
                    observedTargetBox().getCenter().subtract(spearAttackEye()), combatVelocity(),
                    targetHistory.predictionVelocity(target.getDeltaMovement()),
                    SpearCombat.maxRange(mc.player, mc.player.getMainHandItem()),
                    SpearCombat.chargeTicks(mc.player.getMainHandItem()), latencyTicks));
            boolean ownedCharge = weapons.isHoldingUse() || isUsingSpear(mc.player);
            // An airborne charge used to monopolize the hand until its damage window
            // expired, even after the rocket ran out. Reserve at most two imminent
            // attack ticks, then yield the owned hand to the shared rocket controller.
            boolean keepForPass = rocketCommit.admit(mc.player.tickCount, target,
                    boostRefresh && ownedCharge && SpearCombat.isReady(mc.player)
                            && mc.player.hasLineOfSight(target)
                            && nearPass(SpearCombat.maxRange(mc.player, mc.player.getUseItem())));
            boolean lowUnpowered = targetGrounded && flight != null && flight.respectsGlideMomentum() && !flight.isBoostAttached()
                    && mc.player.getY() < target.getBoundingBox().minY + 0.9
                    && mc.player.getDeltaMovement().y < 0;
            boolean charge = !lowUnpowered && (!boostRefresh || keepForPass)
                    && (ownedCharge || mc.player.distanceToSqr(target) <= chargeDistance * chargeDistance);
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
                if (nextState != NEAR_FOLLOW) passThrough.reset();
                if (nextState == PULL_OVER) heading = escapeHeading(targetPos());
            }
            state = nextState;
            if (state != PULL_OVER && !makingChargeRoom && SpearCombat.isReady(mc.player)
                    && (!targetGrounded || spearAttackEye().y <= targetPos().y + 2.5)
                    && spearAttackEye().distanceTo(target.getBoundingBox().getCenter())
                    <= SpearCombat.maxRange(mc.player, mc.player.getUseItem())
                    + Math.max(1.0, combatVelocity().length() * 2.0)) aimSpear();
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
            if (nearPass(spearAttackDistance.getValue())) {
                return tickNearFollow();
            }
            if (tooClose(tp)) return PULL_OVER;
            heading = toTargetAtLeast(tp, 6.0);
            if (!tryAntiSpear(tp, false)) limitChargedApproach();
            return FOLLOW;
        }

        boolean nearPass(double setupRange) {
            Vec3 eye = spearAttackEye();
            return AerialEngagement.closingWindow(observedTargetBox().getCenter().subtract(eye),
                    combatVelocity(), targetHistory.predictionVelocity(target.getDeltaMovement()),
                    Math.sqrt(observedTargetBox().distanceToSqr(eye)), setupRange, latencyTicks + 1);
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
            if (!nearPass(Math.max(spearRange.getValue(), spearAttackDistance.getValue()))) {
                return FOLLOW;
            }
            heading = toTargetAtLeast(tp, 6.0);
            if (spearPassThrough.getValue()) {
                // Ported candidate from SlimefunHelper SpearAura: with the target behind us, keep
                // the crossing direction instead of flipping back; horizontal only, dead-zoned,
                // bounded to one pass and one target. Vertical lanes stay with the request.
                heading = passThrough.apply(target.getId(), heading, combatVelocity(),
                        mc.player.tickCount, PASS_THROUGH_HOLD_TICKS, PASS_THROUGH_DEADZONE);
            }
            if (!tryAntiSpear(tp, false)) limitChargedApproach();
            return NEAR_FOLLOW;
        }

        void limitChargedApproach() {
            if (!SpearCombat.isReady(mc.player)) return;
            Vec3 eye = spearAttackEye();
            var step = AerialEngagement.spearStep(heading, observedTargetBox().getCenter().subtract(eye),
                    targetHistory.predictionVelocity(target.getDeltaMovement()), combatVelocity(),
                    Math.sqrt(observedTargetBox().distanceToSqr(eye)),
                    SpearCombat.minRange(mc.player, mc.player.getUseItem()),
                    SpearCombat.maxRange(mc.player, mc.player.getUseItem()));
            preciseApproach = step.precision();
            heading = step.velocity();
        }

        int tickPullOver() {
            Vec3 tp = targetPos();
            double distance = spearPullOverDistance.getValue();
            if (spearAttackEye().distanceToSqr(tp) >= distance * distance) {
                pulledAfterHit = false;
                return tickFollow();
            }
            if (++pullOverTicks > (targetGrounded ? 30 : pulledAfterHit ? 20 : 6)) {
                return tickFollow();
            }
            heading = escapeHeading(tp);
            if (!tryAntiSpear(tp, true)) {
                var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
                double speed = flight == null ? 6 : Math.clamp(flight.combatPlanningSpeed(), .5, 15);
                var step = AerialEngagement.retreatStep(heading, spearAttackEye().subtract(tp),
                        targetHistory.predictionVelocity(target.getDeltaMovement()), combatVelocity(), distance, speed);
                heading = step.velocity();
                preciseApproach = step.precision();
            }
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
            boolean passWindow = nearPass(Math.max(spearRange.getValue(), spearAttackDistance.getValue()));
            var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
            if (flight != null && flight.combatBoostConfigured()
                    && !targetGrounded && state == FOLLOW && !passWindow && !preciseApproach
                    && !makingChargeRoom && fv.value().lengthSqr() > 1.0E-9) {
                Vec3 current = fv.value();
                Vec3 diagonal = AerialEngagement.pursuitBoost(current, combatVelocity());
                if (diagonal.dot(current) > 0.0 && diagonal.lengthSqr() > 1.0E-9) {
                    fv.set(diagonal.normalize().scale(current.length()));
                }
            }
            if (preciseApproach) fv.requestPrecision();
            // A spear pass needs its intercept direction; filling independent envelope
            // axes can turn a shallow disengagement into a climb over the target.
            if (targetGrounded || state != FOLLOW || preciseApproach || makingChargeRoom
                    || passWindow || Math.abs(heading.y) <= heading.horizontalDistance() * .1) fv.preserveDirection();
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
            double feetY = observedTargetBox().minY + (lowGroundCover ? .12 : 1.4);
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
            var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
            boolean stalledClimb = usingMace && maceHitTick < 0 && adaptiveCombat.getValue()
                    && flight != null && flight.directFlightConfigured() && mace.state == MaceBehavior.PULL_UP
                    && dev.guizhou.util.elytra.DirectFlightControl.shouldChargeSpear(
                    (long) mc.player.tickCount - spearPhaseTick,
                    (long) mc.player.tickCount - mace.lastPressureAttackTick,
                    mc.player.getY() - observedTargetPosition().y, maceDive.isActive())
                    && observedTargetBox().distanceToSqr(mc.player.getEyePosition()) <= Math.pow(Math.max(16, spearRange.getValue() * 2), 2)
                    && (maceDive == null || !maceDive.isActive());
            if (stalledClimb || usingMace && maceHitTick >= 0
                    && (long) mc.player.tickCount - maceHitTick >= mxsMaceHitWaitTicks.getValue()) {
                switchTo(false);
            } else if (!usingMace) {
                long elapsed = (long) mc.player.tickCount - spearStartedTick;
                boolean escaped = spearHit && spear.state == SpearBehavior.FOLLOW;
                boolean targetAbove = spearArmed && elapsed >= 8 && observedTargetPosition().y - mc.player.getY()
                        > Math.max(flight != null && flight.directFlightConfigured() ? 6.0 : 1.5,
                        mxsSpearSwitchMaceHeight.getValue());
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
                var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
                if (!usingMace && flight != null && flight.directFlightConfigured()) {
                    // Charging below a climbing mace gives away a free drop. Match its
                    // vertical drift while making charge room or retreating. A ready
                    // attack must retain the spear planner's direction: replacing a
                    // downward pass with upward drift removes its kinetic damage.
                    Vec3 request = fv.value();
                    if ((spear.makingChargeRoom || spear.state == SpearBehavior.PULL_OVER)
                            && request.horizontalDistanceSqr() < .01) {
                        Vec3 away = mc.player.position().subtract(observedTargetPosition()).multiply(1,0,1);
                        if (away.horizontalDistanceSqr() < .01)
                            away = Vec3.directionFromRotation(0, mc.player.getYRot() + 90);
                        request = new Vec3(away.normalize().x * fv.maxVelocity(), request.y,
                                away.normalize().z * fv.maxVelocity());
                    }
                    Vec3 lane = dev.guizhou.util.elytra.DirectFlightControl.mxsSpearStep(request,
                            observedTargetPosition().y - mc.player.getY(),
                            targetHistory.predictionVelocity(target.getDeltaMovement()).y,
                            SpearCombat.isReady(mc.player),
                            spear.makingChargeRoom || spear.state == SpearBehavior.PULL_OVER);
                    fv.set(lane);
                    fv.requestPrecision();
                }
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
        return MaceCombat.isMace(mc.player.getMainHandItem())
                && MaceItem.canSmashAttack(mc.player)
                && mc.player.fallDistance >= maceMinFallDistance.getValue();
    }

    private boolean attack(Entity entity) {
        if (terrainEscaping()) return false;
        if (mc.gameMode == null || mc.getConnection() == null || !isValid(entity) || entity != target || paused()
                || EatUtil.shouldDefer() || mc.player.isUsingItem()
                || !MaceCombat.isMace(mc.player.getMainHandItem())
                || lastAttackTick == mc.player.tickCount) return false;
        double reach = mc.player.entityInteractionRange();
        Vec3 eye = mc.player.getEyePosition();
        AABB box = observedTargetBox();
        if (!Double.isFinite(reach) || reach <= 0.0
                || box.distanceToSqr(eye) > reach * reach
                || !mc.player.hasLineOfSight(entity)) return false;
        if (!willUseMaceAttack() && mc.player.getAttackStrengthScale(0.5F) <= 0.95F) return false;
        Behavior active = behavior instanceof MxsBehavior mxs ? mxs.active : behavior;
        if (combatAction == AerialCombatPolicy.Action.CHASE && willUseMaceAttack()
                && active instanceof MaceBehavior mace) {
            if (mace.chargeDeferredTick == mc.player.tickCount) return false;
            if (dev.guizhou.util.elytra.MaceStrikeTiming.mayReserve(mace.chargeDeferredTicks,
                    mc.player.tickCount, mace.chargeDeferredTick,
                    MaceApproach.stable(targetHistory.predictionVelocity(target.getDeltaMovement()),
                            targetHistory.previousVelocity(), targetHistory.hasTwoVelocities()))
                    && mc.player.getArmorValue() >= 16
                    && mc.player.getHealth() + mc.player.getAbsorptionAmount() >= 12
                    && dev.guizhou.util.elytra.MaceStrikeTiming.reserveOneTick(eye,
                    mc.player.getDeltaMovement(), box, targetHistory.predictionVelocity(target.getDeltaMovement()),
                    mc.player.fallDistance, reach)) {
                mace.chargeDeferredTicks++;
                mace.chargeDeferredTick = mc.player.tickCount;
                ClientDiagnostics.count("air.mace.charge.reserve", 1);
                return false;
            }
        }
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
        if (kind == EntityAim.Kind.PLAYER && targetGrounded && choice.pattern() == EntityAim.Pattern.GROUND_LEAD)
            return groundPursuitAim();
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
                if (remaining.dot(full) > 0.0) return observedTargetPosition().add(remaining);
            }
        }
        TargetMotionTracker.Snapshot motion = targetHistory.snapshot(target.getDeltaMovement());
        double lead = AerialIntercept.leadTicks(observedTargetPosition().subtract(mc.player.position()),
                motion.velocity(), combatVelocity().length(), latencyTicks, motion.confidence() >= 0.6);
        return observedTargetPosition().add(motion.displacement(lead));
    }

    private int confirmationTicks() {
        return confirmationTicks;
    }

    private Vec3 groundPursuitAim() {
        if (groundAimTarget == target && groundAimTick == mc.player.tickCount) return groundAim;
        groundAimTarget = target;
        groundAimTick = mc.player.tickCount;
        AABB box = observedTargetBox();
        var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        double speed = Math.clamp(flight == null ? combatVelocity().length() : flight.combatPlanningSpeed(), .5, 15);
        Behavior active = behavior instanceof MxsBehavior mxs ? mxs.active : behavior;
        double extra = active instanceof MaceBehavior
                ? Math.max(0, maceHeight.getValue() * 2 - (mc.player.getY() - box.minY)) : 0;
        Vec3 lead = AerialEngagement.groundLead(box.getCenter().subtract(mc.player.position()),
                targetHistory.predictionVelocity(target.getDeltaMovement()), targetHistory.previousVelocity(),
                targetHistory.hasTwoVelocities(), speed, latencyTicks, extra);
        // Bound prediction at cover. Cache these at most three collision checks for the tick.
        for (int attempt = 0; attempt < 3; attempt++) {
            if (lead.lengthSqr() < 1e-8 || mc.level.noCollision(target, box.expandTowards(lead))) {
                groundAim = box.getCenter().add(lead);
                return groundAim;
            }
            lead = lead.scale(.5);
        }
        groundAim = box.getCenter();
        return groundAim;
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
        Vec3 attackEye = spearAttackEye();
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

    private Vec3 observedTargetPosition() {
        return target.position().add(targetPacketOffset());
    }

    private Vec3 observedTargetLook() {
        var interpolation = target.getInterpolation();
        return interpolation != null && interpolation.hasActiveInterpolation()
                ? dev.guizhou.util.elytra.ObservedTargetPosition.look(interpolation.yRot(), interpolation.xRot(), target.getLookAngle())
                : target.getLookAngle();
    }

    private Vec3 targetPacketOffset() {
        var interpolation = target.getInterpolation();
        if (interpolation == null || !interpolation.hasActiveInterpolation()) return Vec3.ZERO;
        return dev.guizhou.util.elytra.ObservedTargetPosition.offset(target.position(), interpolation.position());
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
                resetEscape();
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
                resetEscape();
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
                    || combatAction != AerialCombatPolicy.Action.CHASE
                    && !(combatAction == AerialCombatPolicy.Action.EVADE
                    && maceFeedback.pending(expectedTarget.getId(), mc.player.tickCount))) return;
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
        String foreign = dev.guizhou.util.compat.ClientCoexistence.flightReason();
        if (foreign == null) foreign = dev.guizhou.util.compat.ClientCoexistence.reason(dev.guizhou.util.compat.ForeignClientProbe.Domain.COMBAT);
        if (foreign != null) return dev.guizhou.util.lang.ClientLanguage.text("让行：") + foreign;
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
