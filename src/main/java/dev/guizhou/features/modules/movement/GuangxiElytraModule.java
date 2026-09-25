package dev.guizhou.features.modules.movement;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.impl.network.DisconnectEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.RotationManager;
import dev.guizhou.manager.RotationRequest;
import dev.guizhou.mixin.entity.FireworkRocketEntityAccessor;
import dev.guizhou.util.elytra.ElytraPhysics;
import dev.guizhou.util.elytra.CoupledCruise;
import dev.guizhou.util.elytra.DualViewSupport;
import dev.guizhou.util.elytra.ArmorSwapController;
import dev.guizhou.util.elytra.FlightTerrainSafety;
import dev.guizhou.util.elytra.FlightRoutePlanner;
import dev.guizhou.util.elytra.AsyncFlightRoute;
import dev.guizhou.util.elytra.FlightCollisionSnapshot;
import dev.guizhou.util.elytra.FlightPlanningTask;
import dev.guizhou.util.elytra.FlightRecoveryPlan;
import dev.guizhou.util.elytra.FlightVelocity;
import dev.guizhou.util.elytra.FlightViewAnchor;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.inventory.Result;
import dev.guizhou.util.inventory.ResultType;
import dev.guizhou.util.inventory.SwapMode;
import dev.guizhou.util.inventory.SwapPriority;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Optional;

import static dev.guizhou.util.inventory.InventoryUtil.FULL_SCOPE;

/**
 * Elytra steering with optional firework envelope rescaling.
 *
 * Flight modes (flight-mode):
 * - CONTROL: WASD(+jump/sneak) → world-space direction via vanilla input math;
 *   optional angle overrides reshape the Y component (tan(angle)·horizontal)
 * - ROTATION: fly where you look; jump/sneak still pitch the Y axis
 *
 * Motion modes (motion-mode):
 * - VOID: raw setDeltaMovement — for servers without movement AC
 * - FIREWORKS: applies a client-side envelope after vanilla glide calculation.
 *   This heuristic is not a guarantee of server acceptance.
 *
 * GuangxiElytraChase may replace the requested vector. FIREWORKS can rescale
 * that request to the available local envelope; VOID applies it directly.
 */
public class GuangxiElytraModule extends Module {

    public enum FlightMode { CONTROL, ROTATION }
    public enum MotionMode { VOID, FIREWORKS }
    public enum SetbackMode { SIMULATION, CRASH_PACKETS }

    // ---- General ----
    private final Setting<FlightMode> flightMode = mode("FlightMode", FlightMode.CONTROL).setPage("General");
    private final Setting<Boolean> freeLook = bool("FreeLook", true).setPage("General");
    private final Setting<MotionMode> motionMode = mode("MotionMode", MotionMode.FIREWORKS).setPage("General");
    private final Setting<Double> speed = num("Speed", 1.0, 0.05, 6.0).setPage("General");
    private final Setting<Boolean> autoFly = bool("AutoFly", false).setPage("General");
    private final Setting<Boolean> horizontalNoGravity = bool("HorizontalNoGravity", true).setPage("General");
    private final Setting<Double> motionLerp = num("MotionLerp", 1.0, 0.0, 1.0).setPage("General");
    private final Setting<Double> pullupAngle = num("PullupAngle", 0.0, 0.0, 89.0).setPage("General");
    private final Setting<Double> downwardAngle = num("DownwardAngle", 0.0, 0.0, 89.0).setPage("General");
    private final Setting<Boolean> takeoff = bool("Takeoff", true).setPage("General");
    private final Setting<Integer> takeoffTicks = num("TakeoffTicks", 10, 1, 40).setPage("General");
    private final Setting<Boolean> landAutoClose = bool("LandAutoClose", false).setPage("General");
    private final Setting<Boolean> safeLanding = bool("SafeLanding", true).setPage("General");
    private final Setting<Boolean> terrainGuard = bool("TerrainGuard", true).setPage("General");
    private final Setting<Integer> terrainLookahead = num("TerrainLookahead", 12, 8, 32).setPage("General");
    private final Setting<Boolean> parallelPlanning = bool("ParallelPlanning", true).setPage("General");

    // ---- Rescale (yclimb auto-rescale V2 + corner climb) ----
    private final Setting<Boolean> autoRescale = bool("AutoRescale", true).setPage("Rescale");
    private final Setting<Double> rescaleAmount = num("RescaleAmount", 1.0, 0.05, 5.0).setPage("Rescale");
    private final Setting<Boolean> cornerClimb = bool("CornerClimb", true).setPage("Rescale");
    private final Setting<Double> yCap = num("YCap", 8.0, 0.0, 20.0).setPage("Rescale");
    private final Setting<Double> zeroPointThree = num("ZeroPointThree", 0.3, 0.0, 1.0).setPage("Rescale");
    private final Setting<Boolean> legacyVerticalBoost = bool("LegacyVerticalBoost", false).setPage("Rescale");

    // Single-look cruise uses its own envelope; combat keeps its existing controller.
    private final Setting<Boolean> coupledCruise = bool("CoupledCruise", false).setPage("GZBoost");
    private final Setting<Double> cruisePitch = num("CruisePitch", 15.0, -89.5, 89.5).setPage("GZBoost");
    private final Setting<Double> cruiseClimbPitch = num("CruiseClimbPitch", -45.0, -89.5, 0.0).setPage("GZBoost");
    private final Setting<Double> cruiseDescentPitch = num("CruiseDescentPitch", 30.0, 0.0, 89.5).setPage("GZBoost");
    private final Setting<Boolean> cruiseSmartClimb = bool("CruiseSmartClimb", true).setPage("GZBoost");
    private final Setting<Double> cruiseDescentFloor = num("CruiseDescentFloor", -1.7, -20.0, 0.0).setPage("GZBoost");
    private final Setting<Boolean> cruisePorpoise = bool("CruisePorpoise", false).setPage("GZBoost");
    private final Setting<Double> cruiseHeightWindow = num("CruiseHeightWindow", 60.0, 1.0, 256.0).setPage("GZBoost");
    private final Setting<Double> cruiseSpeedCap = num("CruiseSpeedCap", 15.0, 0.05, 15.0).setPage("GZBoost");
    private final Setting<Double> cruiseBoxCap = num("CruiseBoxCap", 1.7, 0.01, 5.0).setPage("GZBoost");
    private final Setting<Double> cruiseInset = num("CruiseInset", 0.002, 0.0, 0.1).setPage("GZBoost");
    private final Setting<Boolean> cruiseDualLook = bool("CruiseDualLook", true).setPage("GZBoost");
    private final Setting<Double> cruiseDualClimbAngle = num("CruiseDualClimbAngle", 0.0, 0.0, 89.5).setPage("GZBoost");
    private final Setting<Double> cruiseDualDiveAngle = num("CruiseDualDiveAngle", 0.0, 0.0, 30.0).setPage("GZBoost");
    private final Setting<Boolean> cruiseSpeedLog = bool("CruiseSpeedLog", false).setPage("GZBoost");

    // ---- Fireworks ----
    private final Setting<Integer> minBoostTicks = num("MinBoostTicks", 3, 0, 20).setPage("Fireworks");
    private final Setting<Boolean> velFilter = bool("VelFilter", true).setPage("Fireworks");
    private final Setting<Boolean> grimBoost = bool("GrimBoost", false).setPage("Fireworks");
    private final Setting<Double> accMin = num("AccMin", 3.6, 0.5, 20.0).setPage("Fireworks");
    private final Setting<Double> accMax = num("AccMax", 6.0, 1.0, 30.0).setPage("Fireworks");
    private final Setting<SetbackMode> setbackMode = mode("Setback", SetbackMode.SIMULATION).setPage("Fireworks");
    private final Setting<Integer> boostTimeoutTicks = num("BoostTimeoutTicks", 20, 5, 100).setPage("Fireworks");
    private final Setting<Integer> correctionPauseTicks = num("CorrectionPauseTicks", 10, 1, 60).setPage("Fireworks");

    // ---- Tricks ----
    private final Setting<Boolean> flipHover = bool("FlipHover", true).setPage("Tricks");
    private final Setting<Boolean> autoEquip = bool("AutoEquip", true).setPage("Tricks");
    private final Setting<Boolean> groundHop = bool("GroundHop", false).setPage("Tricks");
    private final Setting<Double> hopHeight = num("HopHeight", 3.0, 1.0, 8.0).setPage("Tricks");
    private final Setting<Double> hopPitch = num("HopPitch", 80.0, 40.0, 89.0).setPage("Tricks");

    private static final int CHEST_MENU_SLOT = 6;
    private static final int OFFHAND_MENU_SLOT = 45;
    private static final String ROTATION_ID = "GuangxiElytra_flight";
    private static final String SAFETY_ROTATION_ID = "GuangxiElytra_safety";

    // flight state
    private Vec3 lastApplied = null;   // previous tick's applied velocity (lerp base)
    private Vec3 lastLook = null;
    private final FlightViewAnchor viewAnchor = new FlightViewAnchor();
    private Vec3 pendingMotion;
    private final CoupledCruise cruiseController = new CoupledCruise();
    private CoupledCruise.Selection pendingCruise;
    private CoupledCruise.Config pendingCruiseConfig;
    private boolean cruiseTickActive;
    // Dual view: the rotation of the last duplicate-rotation packet (NaN = none
    // pending) and the once-per-session capability warning flag.
    private float dualSnapYaw = Float.NaN;
    private float dualSnapPitch = Float.NaN;
    private boolean dualViewWarned;
    private boolean noGravitySet;
    private boolean wasFlying;
    private int holdJumpTicks;
    private int flipTick;
    private int yawJitter;

    // GrimBoost state
    private boolean accelerating;
    private boolean boostTickActive;
    private boolean freezing;
    private Vec3 freezeAnchor;
    private long lastTriggerTick = Long.MIN_VALUE / 2;
    private long freezeStartTick;
    private long boostRetryTick;
    private ServerboundMovePlayerPacket sendingTrigger;
    private final AtomicBoolean correctionPending = new AtomicBoolean();
    private LocalPlayer statePlayer;
    private ClientLevel stateLevel;

    // fireworks state
    private int ticksSinceRocket;
    private int refireTicks = 10;
    private int boostScanTick = Integer.MIN_VALUE;
    private int boostRemaining;
    private boolean attachedBoost;

    // passive diagnostics, written on the game thread and read by TelemetryManager; never affect flight
    private volatile double lastRequestedBpt;
    private boolean rocketSentThisTick;
    private volatile double lastAppliedBpt;
    private volatile int lastAppliedTick = Integer.MIN_VALUE;
    private volatile int lastRocketBlock = dev.guizhou.util.telemetry.FlightSummary.BLOCK_NONE;

    // auto-equip state
    private boolean weEquipped;
    private int stowedChestSlot = -1;
    private ItemStack stowedChest = ItemStack.EMPTY;
    private final ArmorSwapController armorSwap = new ArmorSwapController(this);
    private final FlightRoutePlanner terrainRoute = new FlightRoutePlanner();
    private final AsyncFlightRoute asyncTerrainRoute = new AsyncFlightRoute();
    private final FlightPlanningTask<FlightRecoveryPlan, Optional<Vec3>> recoveryTask =
            new FlightPlanningTask<>(FlightRecoveryPlan::calculate);
    private long planningGeneration;
    private LocalPlayer planningPlayer;
    private ClientLevel planningLevel;
    private PlanningContext planningContext;
    private ItemStack planningChest = ItemStack.EMPTY;
    private FlightCollisionSnapshot planningSnapshot;
    private int planningSnapshotTick = Integer.MIN_VALUE;
    private int recoverySubmitTick = Integer.MIN_VALUE;
    private final LandingRecovery landingRecovery = new LandingRecovery();
    private boolean awaitingLanding;
    private boolean safetySteering;
    private boolean pendingSafetySteering;
    private boolean pendingPrecision;
    private boolean pendingDirectional;
    private boolean safetyRocketReady;
    private int safetyHoldTicks;
    private boolean landingDanger;
    private int safetyProbeTick = Integer.MIN_VALUE;
    private int landingHoldTicks;

    // chase coupling
    private boolean enabledByChase;

    private record PlanningContext(boolean enabled, boolean parallel, boolean gliding, boolean grounded,
            boolean dive, boolean armorSwap, boolean landing, boolean terrainGuard, boolean safeLanding,
            boolean coolingDown, boolean rocket, int pose, long combatPhase, MotionMode motion,
            float width, float height, float depth, double gravity) {}

    public GuangxiElytraModule() {
        super("GuangxiElytra", "Elytra flight: CONTROL/ROTATION steering, vanilla-physics firework rescale (yclimb V2 + corner climb).", Category.MOVEMENT);
        accMin.setVisibility(v -> grimBoost.getValue());
        accMax.setVisibility(v -> grimBoost.getValue());
        setbackMode.setVisibility(v -> grimBoost.getValue());
        boostTimeoutTicks.setVisibility(v -> grimBoost.getValue());
        pullupAngle.setVisibility(v -> flightMode.getValue() == FlightMode.CONTROL);
        downwardAngle.setVisibility(v -> flightMode.getValue() == FlightMode.CONTROL);
        zeroPointThree.setVisibility(v -> legacyVerticalBoost.getValue());
        horizontalNoGravity.setVisibility(v -> motionMode.getValue() == MotionMode.VOID);
        for (Setting<?> setting : new Setting<?>[]{cruisePitch, cruiseClimbPitch, cruiseDescentPitch,
                cruiseSmartClimb, cruiseDescentFloor, cruisePorpoise, cruiseHeightWindow,
                cruiseSpeedCap, cruiseBoxCap, cruiseInset, cruiseDualLook, cruiseSpeedLog}) {
            setting.setVisibility(v -> coupledCruise.getValue());
        }
        for (Setting<?> setting : new Setting<?>[]{cruiseDualClimbAngle, cruiseDualDiveAngle}) {
            setting.setVisibility(v -> coupledCruise.getValue() && cruiseDualLook.getValue());
        }
    }

    public void enableByChase() {
        if (!isEnabled()) {
            enable();
            enabledByChase = true;
        }
    }

    public void releaseByChase() {
        if (enabledByChase && isEnabled()) {
            disable();
        }
        enabledByChase = false;
    }

    public boolean canRecoverDiveFlight() {
        return isEnabled() && takeoff.getValue();
    }

    public boolean parallelPlanningEnabled() {
        return parallelPlanning.getValue();
    }

    public boolean wantsArmorSwap() {
        return armorFlyRequested() && mc.player != null && !correctionPending.get()
                && mc.player.tickCount >= boostRetryTick
                && !dev.guizhou.features.modules.combat.GuangxiElytraChaseModule.preservesMaceDescent()
                && !needsLandingRecovery();
    }

    public boolean armorFlyRequested() {
        return ArmorFlyModule.requested();
    }

    public void monitorArmorFly() { armorSwap.monitor(); }

    public void releaseArmorFly() {
        if (!armorFlyRequested()) {
            armorSwap.stop();
            if (!isEnabled()) swapBackChest();
        }
    }

    public void finishArmorSwapRecovery() {
        if (!isEnabled() && ArmorFlyModule.requested()) resumeGliding();
        else finishDiveRecovery();
    }

    public void independentArmorCorrection() { correctionPending.set(true); }

    /** Reuse equipment and rocket safety without owning motion, rotation or packet filtering. */
    public void serviceIndependentArmorFly(boolean rockets) {
        if (isEnabled() || !ArmorFlyModule.requested()) return;
        lastRocketBlock = dev.guizhou.util.telemetry.FlightSummary.BLOCK_NONE;
        checkFlightSession();
        if (nullCheck() || mc.player == null || mc.gameMode == null) return;
        if (correctionPending.getAndSet(false)) {
            resetFlightState();
            boostRetryTick = (long) mc.player.tickCount + correctionPauseTicks.getValue();
        }
        scanAttachedBoosts();
        if (mc.player.tickCount < boostRetryTick) return;
        if (ticksSinceRocket < Integer.MAX_VALUE) ticksSinceRocket++;
        if (!mc.player.isFallFlying()) maybeTakeoff();
        else if (rockets) tryRocket(Double.MAX_VALUE);
    }

    public boolean respectsGlideMomentum() {
        return motionMode.getValue() == MotionMode.FIREWORKS && autoRescale.getValue();
    }

    /** True when the chase bot currently owns a heading — flight keeps running for it. */
    public boolean chaseActive() {
        var chase = GuizhouWare.moduleManager.getModuleByClass(
                dev.guizhou.features.modules.combat.GuangxiElytraChaseModule.class);
        return chase != null && chase.hasHeading();
    }

    private boolean chaseWantsFlight() {
        var chase = GuizhouWare.moduleManager.getModuleByClass(
                dev.guizhou.features.modules.combat.GuangxiElytraChaseModule.class);
        return chase != null && chase.wantsFlight();
    }

    private boolean useStandaloneGrimBoost() {
        return motionMode.getValue() == MotionMode.FIREWORKS && grimBoost.getValue()
                && !terrainGuard.getValue() && !landingDanger
                && !armorSwap.active()
                && !dev.guizhou.features.modules.combat.GuangxiElytraChaseModule.isMaceDiveActive()
                && !chaseActive() && !chaseWantsFlight();
    }

    @Override
    public void onEnable() {
        cancelLandingRecovery();
        viewAnchor.reset();
        correctionPending.set(false);
        resetFlightState();
        checkFlightSession();
        armorSwap.monitor();
        if (mc.player == null) return;
        updateViewAnchor();
        equipElytraIfNeeded();
    }

    @Override
    public void onDisable() {
        restoreNoGravity();
        if (!ArmorFlyModule.requested()) armorSwap.stop();
        if (!ArmorFlyModule.requested() && mc.player != null && mc.player == statePlayer) {
            swapBackChest();
        }
        resetFlightState();
        enabledByChase = false;
        correctionPending.set(false);
    }

    private void resetFlightState() {
        resetCruise();
        lastRocketBlock = dev.guizhou.util.telemetry.FlightSummary.BLOCK_NONE;
        lastAppliedTick = Integer.MIN_VALUE;
        clearFlightRotation();
        restoreNoGravity();
        lastApplied = null;
        lastLook = null;
        pendingMotion = null;
        pendingSafetySteering = false;
        safetySteering = false;
        safetyRocketReady = false;
        safetyHoldTicks = 0;
        safetyProbeTick = Integer.MIN_VALUE;
        landingHoldTicks = 0;
        landingDanger = false;
        terrainRoute.reset();
        invalidatePlanning();
        wasFlying = false;
        holdJumpTicks = 0;
        flipTick = 0;
        yawJitter = 0;
        accelerating = false;
        boostTickActive = false;
        lastTriggerTick = Long.MIN_VALUE / 2;
        boostRetryTick = 0;
        ticksSinceRocket = refireTicks;
        boostScanTick = Integer.MIN_VALUE;
        attachedBoost = false;
        endFreeze();
    }

    private void checkFlightSession() {
        if (statePlayer == mc.player && stateLevel == mc.level) return;
        cancelLandingRecovery();
        resetFlightState();
        viewAnchor.reset();
        statePlayer = mc.player;
        stateLevel = mc.level;
        weEquipped = false;
        stowedChestSlot = -1;
        stowedChest = ItemStack.EMPTY;
        dualSnapYaw = Float.NaN;
        dualSnapPitch = Float.NaN;
        dualViewWarned = false;
    }

    @Subscribe
    private void onDisconnect(DisconnectEvent event) {
        LocalPlayer departed = statePlayer;
        ClientLevel departedLevel = stateLevel;
        mc.execute(() -> {
            if (statePlayer == departed && stateLevel == departedLevel) invalidatePlanning();
        });
    }

    @Subscribe(priority = -100)
    private void onPreTick(PreTickEvent event) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) { dev.guizhou.manager.HeartbeatManager.notifyDeniedOnce(); return; }
        // Observation lifetime: a rocket-gate result is only valid for the tick that produced it.
        lastRocketBlock = dev.guizhou.util.telemetry.FlightSummary.BLOCK_NONE;
        rocketSentThisTick = false;
        resyncDualViewRotation();
        clearFlightRotation();
        checkFlightSession();
        pendingMotion = null;
        pendingCruise = null;
        pendingCruiseConfig = null;
        cruiseTickActive = false;
        pendingSafetySteering = false;
        safetySteering = false;
        safetyRocketReady = false;
        boostTickActive = false;
        if (nullCheck() || mc.player == null || mc.gameMode == null) {
            resetCruise();
            invalidatePlanning();
            return;
        }
        warnDualViewOnce();
        updateViewAnchor();
        if (ticksSinceRocket < Integer.MAX_VALUE) ticksSinceRocket++;
        if (correctionPending.getAndSet(false)) {
            resetFlightState();
            boostRetryTick = (long) mc.player.tickCount + correctionPauseTicks.getValue();
        }
        scanAttachedBoosts();
        syncPlanningContext();
        if (!cruiseEligible()) resetCruise();
        if (dev.guizhou.features.modules.combat.GuangxiElytraChaseModule.isMaceDiveActive()) {
            invalidatePlanning();
            restoreNoGravity();
            accelerating = false;
            endFreeze();
            lastApplied = null;
            lastLook = null;
            return;
        }
        boolean coolingDown = mc.player.tickCount < boostRetryTick;

        if (safeLanding.getValue()
                && !dev.guizhou.features.modules.combat.GuangxiElytraChaseModule.preservesMaceDescent()
                && !mc.player.isFallFlying() && !mc.player.onGround()
                && (wasFlying || mc.player.fallDistance > 2.0)) {
            equipElytraIfNeeded();
            resumeGliding();
        }

        boolean flying = mc.player.isFallFlying();

        if (!flying) {
            invalidatePlanning();
            lastApplied = null;
            lastLook = null;
            accelerating = false;
            endFreeze();
            restoreNoGravity();
            if (wasFlying && mc.player.onGround() && landAutoClose.getValue() && !chaseWantsFlight()) {
                disable();
                return;
            }
            wasFlying = false;
            if (!coolingDown && (!groundHop.getValue() || !tickGroundHop())) maybeTakeoff();
            return;
        }
        wasFlying = true;
        holdJumpTicks = 0;
        if (!coolingDown && !needsLandingRecovery() && groundHop.getValue() && tickGroundHop()) {
            restoreNoGravity();
            lastApplied = null;
            lastLook = mc.player.getLookAngle();
            return;
        }

        // ---- GrimBoost: packet-freeze window owns the flight; vanilla physics
        // + rockets run, movement packets are cancelled in onPacketSend
        if (!coolingDown && useStandaloneGrimBoost() && !needsLandingRecovery() && !terrainGuard.getValue()) {
            restoreNoGravity();
            if (freezing && (long) mc.player.tickCount - freezeStartTick >= boostTimeoutTicks.getValue()) {
                accelerating = false;
                endFreeze();
                boostRetryTick = (long) mc.player.tickCount + correctionPauseTicks.getValue();
                return;
            }
            if (freeLook.getValue()) alignLook(viewAnchor.direction());
            tryRocket(accMax.getValue() * 0.9);
            scanAttachedBoosts();
            boostTickActive = attachedBoost;
            lastLook = mc.player.getLookAngle();
            return;
        }
        accelerating = false;
        endFreeze();

        // ---- build the request direction (ElytraFlight CONTROL/ROTATION) ----
        Vec3 dir = coolingDown ? viewAnchor.direction() : buildInputDirection();
        boolean hasInput = dir.lengthSqr() > 1.0E-6;

        if (!hasInput && autoFly.getValue()) {
            dir = viewAnchor.direction();
            hasInput = dir.lengthSqr() > 1.0E-6;
        }

        double amount = speed.getValue();
        Vec3 realVector = dir.lengthSqr() > 1.0E-9
                ? dir.normalize().scale(amount) : Vec3.ZERO;

        // acceleration smoothing toward the previous applied velocity
        double a = motionLerp.getValue();
        if (hasInput && a < 1.0 && lastApplied != null) {
            Vec3 blended = realVector.scale(a).add(lastApplied.scale(1.0 - a));
            if (blended.lengthSqr() > 1.0E-9) {
                realVector = blended.normalize().scale(amount);
            }
        }

        // ---- behaviour seam: the chase bot may overwrite vector & speed ----
        if (cruiseEligible()) {
            CoupledCruise.Config config = cruiseConfig();
            CoupledCruise.Selection selection = cruiseController.select(config, cruiseInput(),
                    viewAnchor.yaw(), mc.player.getY(), dualViewAvailable());
            if (selection.active()) {
                pendingCruise = selection;
                pendingCruiseConfig = config;
                // Preview only for terrain planning. The movement hook uses the actual
                // vanilla calculation, after rotation arbitration, to apply the envelope.
                Vec3 incoming = mc.player.getDeltaMovement();
                Vec3 simulated = ElytraPhysics.simulateGlide(incoming, selection.look(),
                        mc.player.getAttributeValue(Attributes.GRAVITY));
                CoupledCruise.Result preview = cruiseController.apply(config, selection, incoming, simulated,
                        selection.look(), lastLook == null ? selection.look() : lastLook);
                realVector = preview.feasible() ? preview.velocity() : simulated;
                amount = config.speedCap();
            }
        }
        FlightVelocity fv = new FlightVelocity(realVector, amount);
        if (!coolingDown) dev.guizhou.features.modules.combat.GuangxiElytraChaseModule.onElytra(fv);
        applyTerrainSafety(fv);
        if (safetySteering || fv.isAvoiding() || fv.isPrecise() || fv.isDirectional()) resetCruise();
        if (coolingDown) {
            restoreNoGravity();
            if (safetySteering) alignLook(fv.isBraking() ? brakingLook() : fv.value());
            return;
        }
        realVector = fv.value();
        if (realVector == null || !Double.isFinite(realVector.x)
                || !Double.isFinite(realVector.y) || !Double.isFinite(realVector.z)) {
            restoreNoGravity();
            lastApplied = null;
            return;
        }

        // hover took over (zero-speed request with floating utils on)
        if (realVector.lengthSqr() < 1.0E-4) {
            lastApplied = null;
            restoreNoGravity();
            if (fv.isBraking()) {
                // Turn against momentum without firing another rocket into a blocked route.
                alignLook(brakingLook());
                pendingMotion = motionMode.getValue() == MotionMode.VOID ? Vec3.ZERO : null;
                pendingSafetySteering = true;
            } else {
                idleHover();
            }
            lastLook = mc.player.getLookAngle();
            return;
        }

        // level flight: kill gravity while commanding a near-zero Y component
        if (!safetySteering && motionMode.getValue() == MotionMode.VOID && horizontalNoGravity.getValue()
                && Math.abs(realVector.y) <= 1.0E-2 && !mc.player.onGround()) {
            if (!noGravitySet && !mc.player.isNoGravity()) {
                mc.player.setNoGravity(true);
                noGravitySet = true;
            }
        } else {
            restoreNoGravity();
        }

        if (motionMode.getValue() == MotionMode.FIREWORKS) {
            alignLook(pendingCruise != null ? pendingCruise.look() : realVector);
            if (!safetySteering || safetyRocketReady) {
                tryRocket(pendingCruise != null || autoRescale.getValue() ? Double.MAX_VALUE : speed.getValue() * 0.5);
            }
        }

        pendingMotion = realVector;
        pendingSafetySteering = safetySteering;
        pendingPrecision = fv.isPrecise();
        pendingDirectional = fv.isDirectional();
        cruiseTickActive = pendingCruise != null;
    }

    /** Called after vanilla glide acceleration, immediately before movement. */
    public Vec3 applyFlightVelocity(Vec3 vanillaVelocity, Vec3 incomingVelocity, Vec3 actualLook) {
        Vec3 request = pendingMotion;
        pendingMotion = null;
        CoupledCruise.Selection cruise = pendingCruise;
        CoupledCruise.Config config = pendingCruiseConfig;
        pendingCruise = null;
        pendingCruiseConfig = null;
        Vec3 previousLook = lastLook != null ? lastLook : actualLook;
        lastLook = actualLook;
        if (request == null || !isEnabled() || correctionPending.get()
                || mc.player == null || mc.player.tickCount < boostRetryTick) return vanillaVelocity;

        Vec3 applied = request;
        CoupledCruise.Result cruiseResult = null;
        if (cruise != null) {
            scanAttachedBoosts();
            if (attachedBoost && !pendingSafetySteering && cruiseEligible()) {
                cruiseResult = cruiseController.apply(config, cruise, incomingVelocity, vanillaVelocity, actualLook, previousLook);
            }
            applied = cruiseResult != null && cruiseResult.feasible() ? cruiseResult.velocity() : vanillaVelocity;
            // Report the box look ahead of this tick's movement packet so the widened
            // envelope stays consistent with a rotation the server actually saw.
            if (cruise.dualActive() && mc.getConnection() != null) sendDualViewSnap(cruise);
        } else if (motionMode.getValue() == MotionMode.FIREWORKS && autoRescale.getValue()) {
            // The expansion models a rocket; without an attached rocket only glide is available.
            scanAttachedBoosts();
            applied = attachedBoost && !pendingSafetySteering
                    ? ElytraPhysics.clampToGrimEnvelope(request, incomingVelocity, vanillaVelocity,
                            actualLook, previousLook, rescaleAmount.getValue(), cornerClimb.getValue() && !pendingDirectional,
                            yCap.getValue(), legacyVerticalBoost.getValue() ? zeroPointThree.getValue() : 0.0,
                            pendingPrecision)
                    : vanillaVelocity;
        }
        if (!Double.isFinite(applied.x) || !Double.isFinite(applied.y) || !Double.isFinite(applied.z)) {
            applied = vanillaVelocity;
        }
        if ((terrainGuard.getValue() || safeLanding.getValue()) && !applied.equals(vanillaVelocity)
                && (!clearRelativePath(Vec3.ZERO, applied)
                || !FlightTerrainSafety.trajectoryClear(applied, actualLook,
                mc.player.getAttributeValue(Attributes.GRAVITY),
                dev.guizhou.features.modules.combat.GuangxiElytraChaseModule.hasMaceDescentIntent() ? 1 : 4,
                attachedBoost, this::clearRelativePath))) {
            applied = vanillaVelocity;
            safetyHoldTicks = 6;
        }
        lastApplied = applied;
        lastRequestedBpt = cruiseResult != null ? cruiseResult.velocity().length() : request.length();
        lastAppliedBpt = applied.length();
        lastAppliedTick = mc.player.tickCount;
        if (cruise != null && cruiseSpeedLog.getValue() && mc.player.tickCount % 20 == 0) {
            String phase = switch (cruise.phase()) {
                case LEVEL -> cruise.vertical() > 0 ? "手动拉升" : cruise.vertical() < 0 ? "手动下降" : "巡航";
                case DESCEND -> "脉动下降";
                case CLIMB -> "脉动爬升";
            };
            String limit = cruiseResult == null ? " 等待烟花/交接" : !cruiseResult.feasible() ? " 约束冲突，保留滑翔"
                    : cruiseResult.descentUnmet() ? " 下降目标未达" : cruiseResult.speedLimited() ? " 已限速" : "";
            if (cruise.dualActive() && cruise.boxLook() != null) {
                limit += String.format(java.util.Locale.ROOT, " | 双视角β%.0f°",
                        Math.toDegrees(-Math.asin(cruise.boxLook().y)));
            }
            dev.guizhou.features.commands.Command.sendMessage(String.format(java.util.Locale.ROOT,
                    "[GZBoost] %s | 应用速度 %.2f b/t | 垂直 %.2f b/t%s", phase, applied.length(), applied.y, limit));
        }
        return applied;
    }

    private boolean cruiseEligible() {
        return coupledCruise.getValue() && motionMode.getValue() == MotionMode.FIREWORKS && !grimBoost.getValue()
                && mc.player != null && mc.player.isFallFlying() && !mc.player.onGround()
                && !mc.player.isInWater() && !mc.player.isInLava() && !groundHop.getValue()
                && mc.player.tickCount >= boostRetryTick && !correctionPending.get()
                && !needsLandingRecovery() && !chaseActive() && !chaseWantsFlight()
                && !dev.guizhou.features.modules.combat.GuangxiElytraChaseModule.isMaceDiveActive()
                && !dev.guizhou.features.modules.combat.GuangxiElytraChaseModule.preservesMaceDescent();
    }

    private CoupledCruise.Intent cruiseInput() {
        double strafe = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
        double forward = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
        double vertical = (mc.options.keyJump.isDown() ? 1 : 0) - (mc.options.keyShift.isDown() ? 1 : 0);
        Vec3 horizontal = ElytraPhysics.movementInputToVelocity(new Vec3(strafe, 0, forward), 1, viewAnchor.yaw());
        return new CoupledCruise.Intent(horizontal, vertical, autoFly.getValue());
    }

    private CoupledCruise.Config cruiseConfig() {
        return new CoupledCruise.Config(cruisePitch.getValue(), cruiseClimbPitch.getValue(), cruiseDescentPitch.getValue(),
                cruiseSmartClimb.getValue(), cruiseDescentFloor.getValue(), cruisePorpoise.getValue(),
                cruiseHeightWindow.getValue(), cruiseSpeedCap.getValue(), cruiseBoxCap.getValue(), cruiseInset.getValue(),
                cruiseDualClimbAngle.getValue(), cruiseDualDiveAngle.getValue());
    }

    /** Dual view runs only where duplicate rotation packets are accepted (Via target ≤ 1.20.7). */
    private boolean dualViewAvailable() {
        return cruiseDualLook.getValue() && DualViewSupport.isDupRotSupported();
    }

    /** Once per session: tell the customer why dual view fell back to single view. */
    private void warnDualViewOnce() {
        if (dualViewWarned || !coupledCruise.getValue() || !cruiseDualLook.getValue()
                || mc.player == null || mc.getConnection() == null) return;
        DualViewSupport.Status status = DualViewSupport.status();
        if (status == DualViewSupport.Status.SUPPORTED || status == DualViewSupport.Status.OFFLINE) return;
        dualViewWarned = true;
        dev.guizhou.features.commands.Command.sendMessage("[GZBoost] 双视角不可用（"
                + DualViewSupport.describe(status)
                + "）。请安装 ViaFabricPlus 并通过 Via 连接 1.20.7 及以下版本服务器；已自动回退单视角。");
    }

    /** Same-position rotation packet carrying the box look, sent before the movement packet. */
    private void sendDualViewSnap(CoupledCruise.Selection cruise) {
        Vec3 boxLook = cruise.boxLook();
        if (boxLook == null || !Double.isFinite(boxLook.x) || !Double.isFinite(boxLook.y)
                || !Double.isFinite(boxLook.z) || mc.player == null) return;
        float yaw = ElytraPhysics.rotationToYaw(boxLook);
        float pitch = ElytraPhysics.rotationToPitch(boxLook);
        sendRotationPacket(yaw, pitch);
        dualSnapYaw = yaw;
        dualSnapPitch = pitch;
    }

    /**
     * After a dual-view tick the server's last rotation is the box look, while
     * vanilla bookkeeping holds the motion view. Restore the bookkeeping view
     * before this tick's movement packet; the dual logic re-snaps later if it
     * stays active (reference-client resyncSnap order).
     */
    private void resyncDualViewRotation() {
        if (Float.isNaN(dualSnapYaw) || mc.player == null || mc.getConnection() == null) return;
        float yaw, pitch;
        RotationManager rm = GuizhouWare.rotationManager;
        if (rm != null && rm.isRotating()) {
            yaw = rm.getServerYaw();
            pitch = rm.getServerPitch();
        } else {
            yaw = mc.player.getYRot();
            pitch = mc.player.getXRot();
        }
        float snapYaw = dualSnapYaw, snapPitch = dualSnapPitch;
        dualSnapYaw = Float.NaN;
        dualSnapPitch = Float.NaN;
        if (Math.abs(yaw - snapYaw) < 1e-4 && Math.abs(pitch - snapPitch) < 1e-4) return;
        sendRotationPacket(yaw, pitch);
    }

    private void sendRotationPacket(float yaw, float pitch) {
        mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                yaw, pitch, mc.player.onGround(), mc.player.horizontalCollision));
    }

    private void resetCruise() {
        cruiseController.reset();
        pendingCruise = null;
        pendingCruiseConfig = null;
        cruiseTickActive = false;
    }

    /** Passive diagnostics; never drives movement or rocket decisions. */
    public boolean isCruiseActive() { return isEnabled() && coupledCruise.getValue() && cruiseTickActive; }

    /** Observation-only accessors for the flight diagnostics window. */
    public boolean isBoostAttached() {
        return attachedBoost;
    }

    public boolean isArmorSwapActive() {
        return armorSwap.active();
    }

    public double lastRequestedBpt() {
        return lastRequestedBpt;
    }

    public double lastAppliedBpt() {
        return lastAppliedBpt;
    }

    /** Player tick of the last applyFlightVelocity; older stamps are not fresh samples. */
    public int lastAppliedTick() {
        return lastAppliedTick;
    }

    public int lastRocketBlock() {
        return lastRocketBlock;
    }

    /** CONTROL / ROTATION direction per rennaikanksmp ElytraFlight. */
    private Vec3 buildInputDirection() {
        boolean forward = mc.options.keyUp.isDown();
        boolean back = mc.options.keyDown.isDown();
        boolean left = mc.options.keyLeft.isDown();
        boolean right = mc.options.keyRight.isDown();
        boolean up = mc.options.keyJump.isDown();
        boolean down = mc.options.keyShift.isDown();
        if (!forward && !back && !left && !right && !up && !down) {
            return Vec3.ZERO;
        }

        float yaw = viewAnchor.yaw();
        if (flightMode.getValue() == FlightMode.CONTROL) {
            double strafe = (left ? 1.0 : 0.0) - (right ? 1.0 : 0.0);
            double fwd = (forward ? 1.0 : 0.0) - (back ? 1.0 : 0.0);
            double vert = (up ? 1.0 : 0.0) - (down ? 1.0 : 0.0);
            Vec3 input = new Vec3(strafe, vert, fwd);
            Vec3 world = ElytraPhysics.movementInputToVelocity(input, 1.0F, yaw);
            if (input.horizontalDistanceSqr() > 0.0) {
                if (input.y > 0.0 && pullupAngle.getValue() > 0.1) {
                    double y = Math.tan(Math.toRadians(pullupAngle.getValue()))
                            * world.horizontalDistance();
                    world = new Vec3(world.x, y, world.z);
                } else if (input.y < 0.0 && downwardAngle.getValue() > 0.1) {
                    double y = Math.tan(Math.toRadians(downwardAngle.getValue()))
                            * world.horizontalDistance();
                    world = new Vec3(world.x, -y, world.z);
                }
            }
            return world;
        }

        // Forward follows camera pitch; strafe stays horizontal.
        Vec3 look = viewAnchor.direction();
        double fwd = (forward ? 1.0 : 0.0) - (back ? 1.0 : 0.0);
        double strafe = (left ? 1.0 : 0.0) - (right ? 1.0 : 0.0);
        double y = (up ? 1.0 : 0.0) - (down ? 1.0 : 0.0);
        return look.scale(fwd).add(ElytraPhysics.movementInputToVelocity(
                new Vec3(strafe, y, 0.0), 1.0F, yaw));
    }

    private void updateViewAnchor() {
        viewAnchor.update(freeLook.getValue(), mc.player.getYRot(), mc.player.getXRot());
    }

    /** point the look vector along the motion; ±0.01 yaw jitter each other tick */
    private void alignLook(Vec3 motion) {
        if (motion.lengthSqr() < 1.0E-9) return;
        Vec3 n = motion.normalize();
        float yaw = ElytraPhysics.rotationToYaw(n);
        float pitch = ElytraPhysics.rotationToPitch(n);
        yawJitter++;
        if ((yawJitter & 1) == 0) {
            yaw += 0.01F;
        } else {
            yaw -= 0.01F;
        }
        submitFlightRotation(yaw, pitch);
    }

    private Vec3 brakingLook() {
        Vec3 velocity = mc.player.getDeltaMovement();
        Vec3 horizontal = new Vec3(-velocity.x, 0.0, -velocity.z).normalize();
        if (horizontal.lengthSqr() < 1.0E-6) {
            Vec3 look = viewAnchor.direction();
            horizontal = new Vec3(look.x, 0.0, look.z).normalize();
        }
        if (horizontal.lengthSqr() < 1.0E-6) horizontal = new Vec3(0.0, 0.0, 1.0);
        return horizontal.add(0.0, 0.7, 0.0).normalize();
    }

    private void submitFlightRotation(float yaw, float pitch) {
        if (GuizhouWare.rotationManager != null) {
            GuizhouWare.rotationManager.submit(new RotationRequest(
                    safetySteering ? SAFETY_ROTATION_ID : ROTATION_ID, safetySteering ? 90 : 5, yaw, pitch,
                    RotationRequest.Mode.MOTION, true, true));
        }
    }

    private void clearFlightRotation() {
        if (GuizhouWare.rotationManager != null) {
            GuizhouWare.rotationManager.cancel(ROTATION_ID);
            GuizhouWare.rotationManager.cancel(SAFETY_ROTATION_ID);
        }
    }

    /** zero-request hover: FIREWORKS flips yaw 180° every 2 ticks (vanilla lift) */
    private void idleHover() {
        if (motionMode.getValue() == MotionMode.FIREWORKS && flipHover.getValue()) {
            restoreNoGravity();
            float yaw = viewAnchor.yaw() + ((flipTick++ & 2) == 0 ? 180.0F : 0.0F);
            submitFlightRotation(yaw, 0.0F);
        }
    }

    private void restoreNoGravity() {
        if (noGravitySet) {
            if (statePlayer != null) {
                statePlayer.setNoGravity(false);
            }
            noGravitySet = false;
        }
    }

    // ---------------------------------------------------------------
    // takeoff (jump-hold) & ground hop
    // ---------------------------------------------------------------

    private void maybeTakeoff() {
        if (dev.guizhou.features.modules.combat.GuangxiElytraChaseModule.preservesMaceDescent()) return;
        if (usableElytra(mc.player.getItemBySlot(EquipmentSlot.CHEST))) {
            // wings on — proceed below
        } else {
            equipElytraIfNeeded();
            return; // swap lands next tick at the earliest
        }
        if (!takeoff.getValue()) return;

        if (mc.options.keyJump.isDown()) {
            holdJumpTicks++;
        } else {
            holdJumpTicks = 0;
        }
        boolean chaseWantsFlight = chaseWantsFlight();
        boolean wantTakeoff = chaseWantsFlight
                || (holdJumpTicks >= takeoffTicks.getValue() && !mc.player.onGround());
        if (!wantTakeoff || mc.player.onGround()) {
            if (wantTakeoff && mc.player.onGround()) {
                Vec3 vel = mc.player.getDeltaMovement();
                mc.player.setDeltaMovement(vel.x, 0.42, vel.z);
                mc.player.setSprinting(true);
            }
            return;
        }
        if (canStartGliding() && mc.player.tryToStartFallFlying() && mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(
                    mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        }
    }

    private boolean canStartGliding() {
        ItemStack wings = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        return !mc.player.onGround() && !mc.player.isInWater() && !mc.player.isInLava()
                && !mc.player.isPassenger() && usableElytra(wings);
    }

    private boolean resumeGliding() {
        if (dev.guizhou.features.modules.combat.GuangxiElytraChaseModule.preservesMaceDescent()) return false;
        if (mc.player == null || mc.getConnection() == null || mc.player.isFallFlying()
                || !canStartGliding() || !mc.player.tryToStartFallFlying()) return false;
        mc.getConnection().send(new ServerboundPlayerCommandPacket(
                mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        return true;
    }

    public boolean needsLandingRecovery() {
        if (!safeLanding.getValue() || mc.player == null || mc.level == null) return false;
        if (safetyProbeTick != mc.player.tickCount) {
            safetyProbeTick = mc.player.tickCount;
            boolean risk = !mc.player.onGround() && !mc.player.isInWater() && !mc.player.isInLava()
                    && FlightTerrainSafety.landingRisk(mc.player.getDeltaMovement(),
                    mc.player.getAttributeValue(Attributes.GRAVITY), 16, this::clearRelativePath);
            if (risk) landingHoldTicks = 6;
            else if (landingHoldTicks > 0) landingHoldTicks--;
            landingDanger = risk || landingHoldTicks > 0;
        }
        return landingDanger;
    }

    public double navigationLookahead() {
        double speed = mc.player == null ? 0.0 : mc.player.getDeltaMovement().length();
        return Math.min(48.0, Math.max(terrainLookahead.getValue(), speed * 10.0 + 2.0));
    }

    /** Short swept segments avoid treating an entire diagonal route as a filled box. */
    public boolean clearNavigationPath(Vec3 from, Vec3 to) {
        if (mc.player == null || mc.level == null || !finite(from) || !finite(to)) return false;
        Vec3 delta = to.subtract(from);
        double length = delta.length();
        if (length > 96.0) return false;
        int steps = Math.max(1, (int) Math.ceil(length / 0.5));
        Vec3 step = delta.scale(1.0 / steps);
        AABB body = mc.player.getBoundingBox().inflate(0.06, 0.02, 0.06)
                .move(from.subtract(mc.player.position()));
        for (int index = 0; index < steps; index++) {
            AABB swept = body.expandTowards(step);
            if (!mc.level.hasChunkAt(BlockPos.containing(swept.minX, swept.minY, swept.minZ))
                    || !mc.level.hasChunkAt(BlockPos.containing(swept.maxX, swept.maxY, swept.maxZ))
                    || !mc.level.getWorldBorder().isWithinBounds(swept)
                    || mc.level.getBlockCollisions(mc.player, swept).iterator().hasNext()) return false;
            body = body.move(step);
        }
        return true;
    }

    private boolean clearRelativePath(Vec3 from, Vec3 to) {
        Vec3 origin = mc.player.position();
        return clearNavigationPath(origin.add(from), origin.add(to));
    }

    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private void applyTerrainSafety(FlightVelocity intent) {
        Vec3 desired = intent.value();
        if (!finite(desired)) return;
        scanAttachedBoosts();
        syncPlanningContext();
        double amount = Math.max(0.25, Math.min(speed.getValue(), desired.length()));
        if (terrainGuard.getValue() && !chaseActive() && desired.lengthSqr() > 1.0E-6) {
            double lookahead = navigationLookahead();
            Vec3 displacement = desired.normalize().scale(lookahead);
            var route = parallelPlanningEnabled()
                    ? asyncTerrainRoute.routeTo(mc.player.position(), mc.player.position().add(displacement),
                    lookahead, mc.player.tickCount, this::planningSnapshot, this::clearNavigationPath)
                    : terrainRoute.route(mc.player.position(), displacement,
                    lookahead, mc.player.tickCount, this::clearNavigationPath);
            if (route.blocked()) intent.requestBraking();
            else if (route.avoiding()) intent.requestAvoidance(route.direction()
                    .scale(Math.min(desired.length(), route.distance())));
        }
        Vec3 velocity = mc.player.getDeltaMovement();
        Vec3 look = intent.isBraking() || intent.value().lengthSqr() < 1.0E-6
                ? lastLook != null ? lastLook : viewAnchor.direction() : intent.value().normalize();
        boolean attackDescent = dev.guizhou.features.modules.combat.GuangxiElytraChaseModule.hasMaceDescentIntent();
        boolean landing = !attackDescent && needsLandingRecovery();
        boolean momentumBlocked = terrainGuard.getValue() && !FlightTerrainSafety.trajectoryClear(
                velocity, look, mc.player.getAttributeValue(Attributes.GRAVITY), attackDescent ? 1 : 8,
                attachedBoost, this::clearRelativePath);
        Vec3 preferred = intent.isBraking() ? brakingLook() : look;
        if ((terrainGuard.getValue() || safeLanding.getValue()) && !attackDescent
                && (landing || momentumBlocked || intent.isAvoiding() || intent.isBraking()
                || velocity.y < FlightTerrainSafety.FAST_DESCENT || look.y < -0.25)) {
            queueRecovery(velocity, preferred);
        }
        if (landing || intent.isBraking() || momentumBlocked) {
            var recovery = recoveryLook(velocity, preferred);
            if (recovery.isPresent()) {
                intent.requestAvoidance(recovery.get().scale(amount));
            } else if (landing) {
                Vec3 flat = new Vec3(look.x, 0.0, look.z).normalize();
                if (flat.lengthSqr() < 1.0E-6) flat = new Vec3(1.0, 0.0, 0.0);
                Vec3 up = flat.add(0.0, 0.7, 0.0).normalize();
                if (clearRelativePath(Vec3.ZERO, up.scale(0.75))) intent.requestAvoidance(up.scale(amount));
                else intent.requestBraking();
            } else {
                intent.requestBraking();
            }
        }
        boolean danger = landing || momentumBlocked || intent.isAvoiding() || intent.isBraking();
        if (danger) safetyHoldTicks = 6;
        else if (safetyHoldTicks > 0) safetyHoldTicks--;
        safetySteering = danger || safetyHoldTicks > 0;
        if (safetySteering) {
            restoreNoGravity();
            accelerating = false;
            endFreeze();
            Vec3 escape = intent.value();
            safetyRocketReady = danger && !intent.isBraking() && escape.lengthSqr() > 1.0E-6
                    && safetyRocketAligned(escape)
                    && rocketTrajectoryClear(escape);
        }
    }

    private void invalidatePlanning() {
        planningGeneration++;
        recoveryTask.invalidate();
        asyncTerrainRoute.invalidate();
        planningContext = null;
        planningPlayer = null;
        planningLevel = null;
        planningChest = ItemStack.EMPTY;
        planningSnapshot = null;
        recoverySubmitTick = Integer.MIN_VALUE;
    }

    private void syncPlanningContext() {
        AABB body = mc.player.getBoundingBox();
        PlanningContext current = new PlanningContext(isEnabled(), parallelPlanningEnabled(),
                mc.player.isFallFlying(), mc.player.onGround(),
                dev.guizhou.features.modules.combat.GuangxiElytraChaseModule.isMaceDiveActive(),
                armorSwap.active(), awaitingLanding, terrainGuard.getValue(), safeLanding.getValue(),
                mc.player.tickCount < boostRetryTick, attachedBoost, mc.player.getPose().ordinal(),
                dev.guizhou.features.modules.combat.GuangxiElytraChaseModule.planningPhaseToken(),
                motionMode.getValue(), (float) body.getXsize(), (float) body.getYsize(), (float) body.getZsize(),
                mc.player.getAttributeValue(Attributes.GRAVITY));
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (planningPlayer == mc.player && planningLevel == mc.level && current.equals(planningContext)
                && ItemStack.matches(planningChest, chest)) return;
        if (planningContext != null && current.parallel() != planningContext.parallel()) terrainRoute.reset();
        invalidatePlanning();
        planningContext = current;
        planningPlayer = mc.player;
        planningLevel = mc.level;
        planningChest = chest.copy();
    }

    public FlightCollisionSnapshot planningSnapshot() {
        if (!parallelPlanningEnabled() || !mc.isSameThread() || mc.player == null || mc.level == null
                || correctionPending.get()) return null;
        scanAttachedBoosts();
        syncPlanningContext();
        int tick = mc.player.tickCount;
        if (tick == planningSnapshotTick) return planningSnapshot;
        if (tick >= planningSnapshotTick && (long) tick - planningSnapshotTick < 4) return null;
        planningSnapshotTick = tick;
        planningSnapshot = FlightCollisionSnapshot.capture(mc.player, mc.level, 12.0);
        return planningSnapshot;
    }

    private void queueRecovery(Vec3 velocity, Vec3 preferred) {
        if (!parallelPlanningEnabled() || correctionPending.get()) return;
        int tick = mc.player.tickCount;
        if (tick >= recoverySubmitTick && (long) tick - recoverySubmitTick < 4) return;
        FlightCollisionSnapshot snapshot = planningSnapshot();
        if (snapshot == null) return;
        recoverySubmitTick = tick;
        recoveryTask.submit(new FlightRecoveryPlan(planningGeneration, tick, mc.player.position(),
                velocity, preferred, mc.player.getAttributeValue(Attributes.GRAVITY), attachedBoost, snapshot));
    }

    private Optional<Vec3> recoveryLook(Vec3 velocity, Vec3 preferred) {
        if (parallelPlanningEnabled()) {
            queueRecovery(velocity, preferred);
            var completed = recoveryTask.latest();
            if (completed != null && completed.output().isPresent()) {
                Vec3 candidate = completed.output().get();
                if (completed.input().matches(planningGeneration, mc.player.tickCount,
                        mc.player.position(), velocity, preferred)
                        && planningPlayer == mc.player && planningLevel == mc.level && !correctionPending.get()
                        && clearRelativePath(Vec3.ZERO, candidate.scale(0.75))
                        && FlightTerrainSafety.trajectoryClear(velocity, candidate,
                        mc.player.getAttributeValue(Attributes.GRAVITY), 12, attachedBoost, this::clearRelativePath)) {
                    return Optional.of(candidate);
                }
            }
        }
        return FlightTerrainSafety.recoveryLook(velocity, preferred,
                mc.player.getAttributeValue(Attributes.GRAVITY), 12, attachedBoost, this::clearRelativePath);
    }

    private Vec3 safetyFlightLook() {
        // Our rotation request is temporarily cleared before this tick's steering is submitted.
        return finite(lastLook) && lastLook.lengthSqr() > 1.0E-9 ? lastLook : viewAnchor.direction();
    }

    private boolean safetyRocketAligned(Vec3 escape) {
        return safetyFlightLook().normalize().dot(escape.normalize()) >= 0.985;
    }

    private void cancelLandingRecovery() {
        if (awaitingLanding) EVENT_BUS.unregister(landingRecovery);
        awaitingLanding = false;
        invalidatePlanning();
        clearFlightRotation();
    }

    private final class LandingRecovery {
        @Subscribe(priority = -90)
        private void tick(PreTickEvent event) {
            if (statePlayer != mc.player || stateLevel != mc.level || mc.player == null
                    || mc.player.isDeadOrDying() || isEnabled() || !weEquipped) {
                cancelLandingRecovery();
                return;
            }
            if (armorSwap.active()
                    || dev.guizhou.features.modules.combat.GuangxiElytraChaseModule.isMaceDiveActive()) return;
            if (!mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
                weEquipped = false;
                cancelLandingRecovery();
                return;
            }
            clearFlightRotation();
            if (mc.player.onGround() || mc.player.isInWater() || mc.player.isInLava()) {
                swapBackChest();
                return;
            }
            if (safeLanding.getValue()) {
                resumeGliding();
                if (needsLandingRecovery()) {
                    safetySteering = true;
                    scanAttachedBoosts();
                    syncPlanningContext();
                    recoveryLook(mc.player.getDeltaMovement(), viewAnchor.direction())
                            .ifPresent(GuangxiElytraModule.this::alignLook);
                }
            }
        }

        @Subscribe
        private void disconnect(DisconnectEvent event) {
            LocalPlayer departed = statePlayer;
            mc.execute(() -> {
                if (statePlayer != departed) return;
                weEquipped = false;
                cancelLandingRecovery();
            });
        }
    }

    /** pitch-locked bunny-hop skimming near the ground (ex-ElytraJump). */
    private boolean tickGroundHop() {
        if (chaseWantsFlight() || chaseActive()) return false;
        boolean flying = mc.player.isFallFlying();
        if (!usableElytra(mc.player.getItemBySlot(EquipmentSlot.CHEST))) {
            equipElytraIfNeeded();
            return false;
        }

        Vec3 from = mc.player.position();
        net.minecraft.world.phys.BlockHitResult hit = mc.level.clip(new net.minecraft.world.level.ClipContext(
                from, from.add(0, -hopHeight.getValue(), 0),
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
        boolean groundNear = hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS
                || mc.player.onGround();
        if (!groundNear) return false;

        submitFlightRotation(viewAnchor.yaw(), hopPitch.getValue().floatValue());
        if (mc.player.onGround()) {
            Vec3 vel = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(vel.x, 0.42, vel.z);
            mc.player.setSprinting(true);
        } else if (!flying && mc.player.getDeltaMovement().y < 0 && canStartGliding()
                && mc.player.tryToStartFallFlying() && mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(
                    mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        }
        return true;
    }

    // ---------------------------------------------------------------
    // GrimBoost: freeze movement packets inside the acceleration window
    // (port of OpenKalama ElytraGrimAcc)
    // ---------------------------------------------------------------

    @Subscribe
    private void onPacketSend(PacketEvent.Send event) {
        if (mc.player == null) return;
        if (event.getPacket() == sendingTrigger) return;

        if (event.getPacket() instanceof ServerboundAcceptTeleportationPacket) {
            correctionPending.set(true);
            return;
        }
        if (!(event.getPacket() instanceof ServerboundMovePlayerPacket)) {
            return;
        }

        boolean boostActive = useStandaloneGrimBoost()
                && boostTickActive
                && motionMode.getValue() == MotionMode.FIREWORKS
                && isEnabled()
                && !correctionPending.get()
                && mc.player.tickCount >= boostRetryTick
                && mc.player.isFallFlying()
                && !mc.player.onGround();
        if (!boostActive) {
            boostTickActive = false;
            accelerating = false;
            if (freezing) {
                endFreeze();
            }
            return;
        }

        double vel = mc.player.getDeltaMovement().length();
        double upper = Math.max(accMin.getValue(), accMax.getValue());
        double lower = Math.min(accMin.getValue(), accMax.getValue());
        if (accelerating) {
            if (vel >= upper) {
                accelerating = false;
            }
        } else if (vel < lower) {
            accelerating = true;
        }

        if (!accelerating) {
            if (freezing) {
                endFreeze();
            }
            return;
        }

        if (!freezing) {
            freezing = true;
            freezeAnchor = mc.player.position();
            freezeStartTick = mc.player.tickCount;
            if (mc.player.tickCount - lastTriggerTick >= 20) {
                lastTriggerTick = mc.player.tickCount;
                sendingTrigger = buildSetbackTrigger();
                try {
                    mc.getConnection().send(sendingTrigger);
                } finally {
                    sendingTrigger = null;
                }
            }
        }

        event.cancel();
        mc.player.setPos(freezeAnchor.x, freezeAnchor.y, freezeAnchor.z);
    }

    private ServerboundMovePlayerPacket buildSetbackTrigger() {
        float yaw = mc.player.getYRot();
        float pitch = mc.player.getXRot();
        boolean hc = mc.player.horizontalCollision;
        return switch (setbackMode.getValue()) {
            case SIMULATION -> new ServerboundMovePlayerPacket.PosRot(
                    mc.player.getX(),
                    mc.player.getY() + 2.5 * (mc.player.tickCount % 3 + 1),
                    mc.player.getZ(), yaw, pitch, mc.player.onGround(), hc);
            case CRASH_PACKETS -> new ServerboundMovePlayerPacket.PosRot(
                    3.9999999E7,
                    mc.player.getY() + 2.5 * (mc.player.tickCount % 3 + 1),
                    3.9999999E7, yaw, pitch, true, hc);
        };
    }

    private void endFreeze() {
        freezing = false;
        freezeAnchor = null;
    }

    @Subscribe
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket
                || event.getPacket() instanceof ClientboundRespawnPacket) {
            correctionPending.set(true);
            return;
        }
        // Velocity filtering belongs to the optional freeze exploit, not normal flight.
        if (!useStandaloneGrimBoost() || !velFilter.getValue() || !freezing
                || correctionPending.get() || mc.player == null) return;
        if (event.getPacket() instanceof ClientboundSetEntityMotionPacket motion
                && motion.getId() == mc.player.getId()
                && mc.player.isFallFlying()) {
            Vec3 packetVel = motion.getMovement();
            if (packetVel.horizontalDistanceSqr() < 0.01) {
                event.cancel();
                return;
            }
            Vec3 current = mc.player.getDeltaMovement();
            if (current.horizontalDistanceSqr() > 0.01
                    && new Vec3(current.x, 0, current.z).dot(new Vec3(packetVel.x, 0, packetVel.z)) < 0.0) {
                event.cancel();
            }
        }
    }

    // ---------------------------------------------------------------
    // fireworks: refire with attached-rocket reuse check
    // ---------------------------------------------------------------

    /** Let automatic spear use release the hand for one pending rocket refresh. */
    /** True only after tryRocket consumed a firework on this tick. Armor return waits for the next window. */
    public boolean rocketSentThisTick() {
        return rocketSentThisTick;
    }

    /**
     * Already gliding on elytra and a firework is due. The armor cycle may open its
     * rocket window before swapping back to chestplate. A held item (charged spear)
     * makes the firework yield.
     */
    public boolean holdChestplateForBoost() {
        if (mc.player == null || !mc.player.isFallFlying() || mc.player.isUsingItem()) return false;
        if (motionMode.getValue() != MotionMode.FIREWORKS) return false;
        if (dev.guizhou.features.modules.combat.GuangxiElytraChaseModule.preservesMaceDescent()
                || dev.guizhou.features.modules.combat.GuangxiElytraChaseModule.isMaceDiveActive()) return false;
        if (!usableElytra(mc.player.getItemBySlot(EquipmentSlot.CHEST))) return false;
        if (!canUseRocket() || ticksSinceRocket < refireTicks || hasUsefulBoost()) return false;
        return InventoryUtil.find(GuangxiElytraModule::safeRocket, FULL_SCOPE).found();
    }

    public boolean needsSpearBoostRefresh() {
        if (!canUseRocket() || !mc.player.isFallFlying()
                || motionMode.getValue() != MotionMode.FIREWORKS || ticksSinceRocket < refireTicks
                || correctionPending.get() || mc.player.tickCount < boostRetryTick
                || dev.guizhou.features.modules.combat.GuangxiElytraChaseModule.preservesMaceDescent()) return false;
        double targetSpeed = useStandaloneGrimBoost() ? accMax.getValue() * 0.9
                : autoRescale.getValue() ? Double.MAX_VALUE : speed.getValue() * 0.5;
        if (targetSpeed != Double.MAX_VALUE
                && mc.player.getDeltaMovement().lengthSqr() >= targetSpeed * targetSpeed) return false;
        scanAttachedBoosts();
        return !attachedBoost && InventoryUtil.find(GuangxiElytraModule::safeRocket, FULL_SCOPE).found()
                && rocketTrajectoryClear(rocketLaunchDirection());
    }

    private void tryRocket(double targetSpeed) {
        if (!canUseRocket()) {
            lastRocketBlock = dev.guizhou.util.telemetry.FlightSummary.BLOCK_NOT_ENABLED;
            return;
        }
        if (mc.player.isUsingItem()) {
            lastRocketBlock = dev.guizhou.util.telemetry.FlightSummary.BLOCK_USING_ITEM;
            return;
        }
        // Defer the actual use until this armor cycle has restarted gliding.
        // Keep this out of canUseRocket: spear must be able to release its hand
        // before the equipment controller can prepare that restart window.
        if (armorSwap.active() && !armorSwap.rocketWindowOpen()) {
            lastRocketBlock = dev.guizhou.util.telemetry.FlightSummary.BLOCK_NOT_ENABLED;
            return;
        }
        if (dev.guizhou.features.modules.combat.GuangxiElytraChaseModule.preservesMaceDescent()) {
            lastRocketBlock = dev.guizhou.util.telemetry.FlightSummary.BLOCK_MACE_DESCENT;
            return;
        }
        double velocitySq = mc.player.getDeltaMovement().lengthSqr();
        if (targetSpeed != Double.MAX_VALUE && velocitySq >= targetSpeed * targetSpeed) {
            lastRocketBlock = dev.guizhou.util.telemetry.FlightSummary.BLOCK_SPEED_GATE;
            return;
        }
        if (ticksSinceRocket < refireTicks) {
            lastRocketBlock = dev.guizhou.util.telemetry.FlightSummary.BLOCK_REFIRE;
            return;
        }
        if (hasUsefulBoost()) {
            lastRocketBlock = dev.guizhou.util.telemetry.FlightSummary.BLOCK_USEFUL_BOOST;
            return;
        }

        Result rocket = InventoryUtil.find(GuangxiElytraModule::safeRocket, FULL_SCOPE);
        if (!rocket.found()) {
            ticksSinceRocket = -40;
            lastRocketBlock = dev.guizhou.util.telemetry.FlightSummary.BLOCK_NO_ROCKET;
            return;
        }

        if (!rocketTrajectoryClear(rocketLaunchDirection())) {
            lastRocketBlock = dev.guizhou.util.telemetry.FlightSummary.BLOCK_TRAJECTORY;
            return;
        }

        int nextRefire = estimatedRocketLifetime(rocket.stack()) - 1;
        boolean[] used = {false};
        boolean swapped = GuizhouWare.swapManager.withSwap(rocket, SwapMode.ALTSILENT, SwapPriority.UTILITY,
                () -> {
                    if (safeRocket(mc.player.getItemInHand(rocket.hand()))) {
                        used[0] = mc.gameMode.useItem(mc.player, rocket.hand()).consumesAction();
                    }
                });
        if (swapped && used[0]) {
            ticksSinceRocket = 0;
            refireTicks = nextRefire;
            boostScanTick = Integer.MIN_VALUE;
            rocketSentThisTick = true;
            lastRocketBlock = dev.guizhou.util.telemetry.FlightSummary.BLOCK_NONE;
        }
    }

    private boolean rocketTrajectoryClear(Vec3 look) {
        return !(terrainGuard.getValue() || safeLanding.getValue())
                || FlightTerrainSafety.rocketLaunchClear(mc.player.getDeltaMovement(), look,
                mc.player.getAttributeValue(Attributes.GRAVITY), 16, rocketArrivalTicks(), 10, this::clearRelativePath);
    }

    private int rocketArrivalTicks() {
        var info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return info == null ? 2 : (int) Math.clamp((info.getLatency() + 49L) / 50L + 1, 1, 10);
    }

    /** Match MixinMultiPlayerGameMode's use-packet rotation, including free-look. */
    private Vec3 rocketLaunchDirection() {
        var rotations = GuizhouWare.rotationManager;
        return rotations != null && rotations.isRotating() && !rotations.isBypassUseSpoof()
                ? Vec3.directionFromRotation(rotations.getRotationPitch(), rotations.getRotationYaw())
                : mc.player.getLookAngle();
    }

    private boolean canUseRocket() {
        return (isEnabled() || ArmorFlyModule.requested()) && mc.player != null && mc.level != null && mc.gameMode != null
                && mc.getConnection() != null && mc.screen == null
                && mc.player.containerMenu == mc.player.inventoryMenu
                && mc.player.containerMenu.getCarried().isEmpty()
                && (!safetySteering || safetyRocketReady)
                && (!armorSwap.active() || usableElytra(mc.player.getItemBySlot(EquipmentSlot.CHEST)))
                && !dev.guizhou.features.modules.combat.GuangxiElytraChaseModule.isMaceDiveActive();
    }

    private boolean hasUsefulBoost() {
        scanAttachedBoosts();
        return attachedBoost && (minBoostTicks.getValue() == 0 || boostRemaining > minBoostTicks.getValue());
    }

    private void scanAttachedBoosts() {
        if (boostScanTick == mc.player.tickCount) return;
        boostScanTick = mc.player.tickCount;
        attachedBoost = false;
        boostRemaining = Integer.MIN_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof net.minecraft.world.entity.projectile.FireworkRocketEntity rocket)) continue;
            FireworkRocketEntityAccessor accessor = (FireworkRocketEntityAccessor) rocket;
            int attachedId = rocket.getEntityData().get(FireworkRocketEntityAccessor.guizhouware$getAttachedTargetData())
                    .orElse(-1);
            if (!rocket.isAlive() || attachedId != mc.player.getId()) continue;
            attachedBoost = true;
            // Spawned client rockets do not receive the server's randomized lifetime.
            int estimate = estimatedRocketLifetime(rocket.getItem());
            boostRemaining = Math.max(boostRemaining, estimate - accessor.guizhouware$getLife());
        }
    }

    private static int estimatedRocketLifetime(ItemStack stack) {
        Fireworks fireworks = stack.get(DataComponents.FIREWORKS);
        return 10 * ((fireworks != null ? fireworks.flightDuration() : 0) + 1);
    }

    private static boolean safeRocket(ItemStack stack) {
        Fireworks fireworks = stack.get(DataComponents.FIREWORKS);
        return stack.is(Items.FIREWORK_ROCKET) && (fireworks == null || fireworks.explosions().isEmpty());
    }

    // ---------------------------------------------------------------
    // auto equip (ex-ElytraDash): pull the elytra on for takeoff, put the
    // chestplate back when the module goes off
    // ---------------------------------------------------------------

    private void equipElytraIfNeeded() {
        if (!autoEquip.getValue()) return;
        if (armorSwap.active()) return;
        if (dev.guizhou.features.modules.combat.GuangxiElytraChaseModule.isMaceDiveActive()) return;
        if (usableElytra(mc.player.getItemBySlot(EquipmentSlot.CHEST))) return;
        if (!canSwapChest()) return;

        Result elytra = InventoryUtil.find(GuangxiElytraModule::usableElytra, FULL_SCOPE);
        if (!elytra.found()) return;

        int slot = containerSlotOf(elytra);
        ItemStack previousChest = mc.player.getItemBySlot(EquipmentSlot.CHEST).copy();
        if (!swapChestArmorWith(slot)) return;
        if (!weEquipped) {
            stowedChestSlot = slot;
            stowedChest = previousChest;
        }
        weEquipped = true;
    }

    private static boolean usableElytra(ItemStack stack) {
        return stack.is(Items.ELYTRA)
                && (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage() - 1);
    }

    /** Complete deferred module cleanup after the dive controller returns its owned equipment pair. */
    public void finishDiveRecovery() {
        if (mc.player == null || mc.player != statePlayer || mc.level != stateLevel) return;
        if (weEquipped && stowedChestSlot != -1) {
            ItemStack candidate = mc.player.inventoryMenu.getSlot(stowedChestSlot).getItem();
            if (!stowedChest.isEmpty() && !candidate.isEmpty()) {
                ItemStack expected = stowedChest.copy();
                ItemStack returned = candidate.copy();
                expected.remove(DataComponents.DAMAGE);
                returned.remove(DataComponents.DAMAGE);
                if (ItemStack.matches(expected, returned)) stowedChest = candidate.copy();
            }
        }
        if (!isEnabled()) swapBackChest();
        else if (safeLanding.getValue() && !correctionPending.get()) resumeGliding();
    }

    private void swapBackChest() {
        if (!weEquipped || mc.player == null) return;
        if (armorSwap.active()) return;
        if (dev.guizhou.features.modules.combat.GuangxiElytraChaseModule.isMaceDiveActive()) return;
        if (!mc.player.onGround() && !mc.player.isInWater() && !mc.player.isInLava()) {
            if (!awaitingLanding) {
                awaitingLanding = true;
                EVENT_BUS.register(landingRecovery, "LandingRecovery");
            }
            return;
        }
        if (!canSwapChest()) {
            return; // screen open — keep the elytra on rather than eating the armor
        }
        if (mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA) && stowedChestSlot != -1) {
            ItemStack candidate = mc.player.inventoryMenu.getSlot(stowedChestSlot).getItem();
            if (ItemStack.matches(stowedChest, candidate)) swapChestArmorWith(stowedChestSlot);
        }
        weEquipped = false;
        stowedChestSlot = -1;
        stowedChest = ItemStack.EMPTY;
        cancelLandingRecovery();
    }

    private boolean canSwapChest() {
        return mc.player != null && mc.gameMode != null
                && mc.screen == null && !mc.player.isUsingItem()
                && (GuizhouWare.swapManager == null || !GuizhouWare.swapManager.isLatched())
                && mc.player.containerMenu == mc.player.inventoryMenu
                && mc.player.containerMenu.getCarried().isEmpty();
    }

    private boolean swapChestArmorWith(int itemContainerSlot) {
        if (!canSwapChest() || itemContainerSlot < 9 || itemContainerSlot > OFFHAND_MENU_SLOT) return false;
        ItemStack expectedChest = mc.player.inventoryMenu.getSlot(itemContainerSlot).getItem().copy();
        if (itemContainerSlot >= 36) {
            int button = itemContainerSlot == OFFHAND_MENU_SLOT ? InventoryUtil.OFFHAND_SWAP_BUTTON
                    : itemContainerSlot - 36;
            InventoryUtil.click(CHEST_MENU_SLOT, button, net.minecraft.world.inventory.ClickType.SWAP);
            return ItemStack.matches(expectedChest, mc.player.getItemBySlot(EquipmentSlot.CHEST));
        }
        InventoryUtil.click(itemContainerSlot, 0, net.minecraft.world.inventory.ClickType.PICKUP);
        InventoryUtil.click(CHEST_MENU_SLOT, 0, net.minecraft.world.inventory.ClickType.PICKUP);
        InventoryUtil.click(itemContainerSlot, 0, net.minecraft.world.inventory.ClickType.PICKUP);
        return mc.player.containerMenu.getCarried().isEmpty()
                && ItemStack.matches(expectedChest, mc.player.getItemBySlot(EquipmentSlot.CHEST));
    }

    private int containerSlotOf(Result result) {
        if (result.type() == ResultType.OFFHAND) return OFFHAND_MENU_SLOT;
        int slot = result.slot();
        return slot < 9 ? slot + 36 : slot;
    }

    @Override
    public String getDisplayInfo() {
        String m = switch (motionMode.getValue()) {
            case FIREWORKS -> grimBoost.getValue() && !armorSwap.active() && !terrainGuard.getValue()
                    ? "GrimBoost" : (autoRescale.getValue() ? "Grim" : "Rocket");
            case VOID -> "Void";
        };
        if (freezing) return m + "·Freeze";
        if (armorFlyRequested()) return m + (armorSwap.active() ? "·ArmorFly" : "·ArmorWait");
        if (chaseActive()) return m + "·Chase";
        return m;
    }
}
