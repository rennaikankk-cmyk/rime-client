package dev.guizhou.features.modules.movement;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.combat.GuangxiElytraChaseModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.RotationRequest;
import dev.guizhou.mixin.entity.FireworkRocketEntityAccessor;
import dev.guizhou.util.elytra.ElytraPhysics;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.inventory.Result;
import dev.guizhou.util.inventory.ResultType;
import dev.guizhou.util.inventory.SwapMode;
import dev.guizhou.util.inventory.SwapPriority;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

import static dev.guizhou.util.inventory.InventoryUtil.FULL_SCOPE;

/**
 * Velocity elytra flight, ported from rennaikanksmp ElytraFlight + OpenKalama
 * ElytraGrimAcc/ElytraJump/ElytraSlowFall + shoreline ElytraBoost takeoff.
 *
 * Modes:
 * - VOID: raw setDeltaMovement — full control, for servers without movement AC
 * - FIREWORKS: requested motion clamped into the Grim acceptance envelope
 *   (auto-rescale V2 + optional yclimb corner law) with rocket thrust
 * - NOAC: maximum power for anticheat-less servers — no clamps, no gravity,
 *   instant takeoff; vanilla still kicks above ~10 blocks/tick
 *
 * GrimBoost (FIREWORKS only): OpenKalama's accelerate-window trick — while
 * the firework ramps speed through the [AccMin, AccMax] band, every movement
 * packet is cancelled and the client snaps back (the server never observes
 * the per-tick acceleration); one invalid "setback trigger" move packet is
 * sent at freeze start so Grim resets its simulation state via teleport.
 * Velocity packets that would brake the glide are filtered on arrival.
 *
 * GroundHop: OpenKalama ElytraJump — pitch-locked bunny-hop skimming, fully
 * vanilla-legal movement. FlipHover: OpenKalama ElytraSlowFall — the yaw-flip
 * hover (vanilla-legal, Grim-safe; replaces the noGravity hack in FIREWORKS).
 */
public class GuangxiElytraModule extends Module {

    public static final String ID = "GuangxiElytra";

    public enum MotionMode {
        /** pure rotation steering, vanilla physics — the legal tier (ex-ElytraFly) */
        STEER,
        VOID, FIREWORKS, NOAC
    }

    public enum SetbackMode {
        SIMULATION, CRASH_PACKETS
    }

    private final Setting<MotionMode> motionMode = mode("MotionMode", MotionMode.FIREWORKS);

    /** requested speed in blocks/tick (1.0 = 20 m/s) */
    private final Setting<Double> speed = num("Speed", 1.0, 0.05, 10.0);

    private final Setting<Boolean> bestClimb = bool("BestClimb", true);

    private final Setting<Double> motionLerp = num("MotionLerp", 1.0, 0.0, 1.0).setPage("Grim");

    // ---- FIREWORKS / grim-safe envelope ----
    private final Setting<Boolean> grimSafe = bool("GrimSafe", true).setPage("Grim");
    private final Setting<Double> rescaleAmount = num("RescaleAmount", 1.0, 0.05, 5.0).setPage("Grim");
    private final Setting<Boolean> cornerClimb = bool("CornerClimb", false).setPage("Grim");
    private final Setting<Double> yCap = num("YCap", 8.0, 0.0, 10.0).setPage("Grim");

    // ---- GrimBoost: packet-freeze acceleration (OpenKalama ElytraGrimAcc) ----
    private final Setting<Boolean> grimBoost = bool("GrimBoost", false).setPage("Grim");
    private final Setting<Double> accMin = num("AccMin", 3.6, 0.5, 20.0).setPage("Grim");
    private final Setting<Double> accMax = num("AccMax", 6.0, 1.0, 30.0).setPage("Grim");
    private final Setting<SetbackMode> setbackMode = mode("Setback", SetbackMode.SIMULATION).setPage("Grim");
    private final Setting<Boolean> velFilter = bool("VelFilter", true).setPage("Grim");

    // ---- takeoff / hover / tricks ----
    private final Setting<Boolean> autoTakeoff = bool("AutoTakeoff", true).setPage("Tricks");
    private final Setting<Integer> takeoffTicks = num("TakeoffTicks", 3, 1, 20).setPage("Tricks");
    private final Setting<Boolean> instantTakeoff = bool("InstantTakeoff", true).setPage("Tricks");
    private final Setting<Double> takeoffBoost = num("TakeoffBoost", 2.88, 0.0, 6.0).setPage("Tricks");
    private final Setting<Boolean> flipHover = bool("FlipHover", true).setPage("Tricks");
    private final Setting<Boolean> hoverNoGravity = bool("HoverNoGravity", true).setPage("Tricks");
    private final Setting<Boolean> landAutoClose = bool("LandAutoClose", false).setPage("Tricks");

    // ---- ground hop (OpenKalama ElytraJump) ----
    private final Setting<Boolean> groundHop = bool("GroundHop", false).setPage("Tricks");
    private final Setting<Double> hopHeight = num("HopHeight", 3.0, 1.0, 8.0).setPage("Tricks");
    private final Setting<Double> hopPitch = num("HopPitch", 80.0, 40.0, 89.0).setPage("Tricks");

    // ---- STEER mode (ex-ElytraFly) ----
    private final Setting<Double> climbPitch = num("ClimbPitch", 45.0, 15.0, 89.0).setPage("Tricks");
    private final Setting<Double> steerHoverPitch = num("SteerHoverPitch", -1.3, -45.0, 0.0).setPage("Tricks");

    // ---- auto equip (ex-ElytraDash) ----
    private final Setting<Boolean> autoEquip = bool("AutoEquip", true).setPage("Tricks");
    private final Setting<Integer> minBoostTicks = num("MinBoostTicks", 3, 0, 20).setPage("Tricks");

    private static final int CHEST_MENU_SLOT = 6;
    private static final int OFFHAND_MENU_SLOT = 45;
    private boolean steering;
    private boolean weEquipped;
    private int stowedChestSlot = -1;

    private Vec3 lastVelocity = null;
    private Vec3 lastLook = null;
    private boolean wasFlying = false;
    private int holdJumpTicks;
    private int takeoffPhase = 0; // 0 idle, 1 jumped, 2 sent start-flying
    private boolean noGravitySet = false;
    private int flipTick;

    private int ticksSinceRocket = Integer.MAX_VALUE / 2;
    private int refireTicks = 12;

    // GrimBoost state
    private boolean accelerating;
    private boolean freezing;
    private Vec3 freezeAnchor;
    private int lastTriggerTick = Integer.MIN_VALUE / 2;

    public GuangxiElytraModule() {
        super("GuangxiElytra", "Velocity elytra flight: Grim envelope, packet-freeze boost, ground hop, NoAC mode.", Category.MOVEMENT);
        rescaleAmount.setVisibility(v -> grimSafe.getValue() && motionMode.getValue() == MotionMode.FIREWORKS);
        cornerClimb.setVisibility(v -> grimSafe.getValue() && motionMode.getValue() == MotionMode.FIREWORKS);
        yCap.setVisibility(v -> cornerClimb.getValue() && grimSafe.getValue() && motionMode.getValue() == MotionMode.FIREWORKS);
        accMin.setVisibility(v -> grimBoost.getValue());
        accMax.setVisibility(v -> grimBoost.getValue());
        setbackMode.setVisibility(v -> grimBoost.getValue());
        takeoffTicks.setVisibility(v -> autoTakeoff.getValue() && !instantTakeoff.getValue());
        takeoffBoost.setVisibility(v -> instantTakeoff.getValue());
        hopHeight.setVisibility(v -> groundHop.getValue());
        hopPitch.setVisibility(v -> groundHop.getValue());
    }

    @Override
    public void onEnable() {
        lastVelocity = null;
        lastLook = null;
        holdJumpTicks = 0;
        takeoffPhase = 0;
        accelerating = false;
        freezing = false;
        steering = false;
    }

    @Override
    public void onDisable() {
        restoreNoGravity();
        endFreeze();
        stopSteer();
        swapBackChest();
        wasFlying = false;
    }

    private void restoreNoGravity() {
        if (noGravitySet && mc.player != null) {
            mc.player.setNoGravity(false);
            noGravitySet = false;
        }
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (nullCheck() || mc.player == null) return;

        boolean flying = mc.player.isFallFlying();

        // ---- ground hop: standalone vanilla-legal skimming travel ----
        if (groundHop.getValue() && tickGroundHop()) {
            return;
        }

        if (!flying) {
            maybeTakeoff();
            lastVelocity = null;
            lastLook = null;
            restoreNoGravity();
            stopSteer();
            if (wasFlying && mc.player.onGround() && landAutoClose.getValue()) {
                disable();
            }
            wasFlying = flying;
            return;
        }
        wasFlying = true;
        takeoffPhase = 0;

        Vec3 tickStartVel = mc.player.getDeltaMovement();
        Vec3 look = mc.player.getLookAngle();

        // ---- STEER: pure rotation steering, vanilla physics does the rest ----
        if (motionMode.getValue() == MotionMode.STEER) {
            tickSteer();
            tryRocket(Double.MAX_VALUE);
            lastLook = look;
            return;
        }

        // ---- GrimBoost: packet-freeze acceleration owns the flight; vanilla
        // physics + rockets run, movement packets are frozen by onPacketSend
        if (motionMode.getValue() == MotionMode.FIREWORKS && grimBoost.getValue()) {
            restoreNoGravity();
            tryRocket(accMax.getValue() * 0.9);
            lastLook = look;
            return;
        }

        // chase override: the pursuit bot owns the heading while it has a target
        Vec3 chaseDir = GuangxiElytraChaseModule.provideDirection();
        double chaseSpeedMult = GuangxiElytraChaseModule.provideSpeedMultiplier();

        Vec3 controlMotion;
        boolean hasInput;
        if (chaseDir != null && chaseDir.lengthSqr() > 1.0E-9) {
            double max = speed.getValue() * chaseSpeedMult;
            double len = Math.min(chaseDir.length(), max);
            controlMotion = chaseDir.normalize().scale(len);
            hasInput = true;
        } else {
            controlMotion = wasdMotion();
            hasInput = controlMotion.lengthSqr() > 1.0E-9;
        }

        double motionAmount = speed.getValue();

        if (!hasInput) {
            idleHover(look);
            lastLook = look;
            return;
        }

        Vec3 way = controlMotion.normalize();
        if (bestClimb.getValue() && motionMode.getValue() != MotionMode.NOAC) {
            if (way.y > 0.1) {
                way = ElytraPhysics.bestPullupSpeed(way).normalize();
            } else if (way.y < -0.1) {
                way = ElytraPhysics.bestDownForwardSpeed(way).normalize();
            }
        }
        Vec3 realVector = way.scale(motionAmount);

        // acceleration smoothing: blend with last tick's velocity; while the
        // player actively steers, hold top speed (rennaikanksmp lerp semantics)
        double a = motionLerp.getValue();
        if (a < 1.0 && lastVelocity != null) {
            Vec3 blended = realVector.scale(a).add(lastVelocity.scale(1.0 - a));
            if (blended.lengthSqr() > 1.0E-9) {
                realVector = blended.normalize().scale(motionAmount);
            }
        }
        lastVelocity = realVector.length() < 0.1 * motionAmount ? Vec3.ZERO : realVector;

        // level flight: kill gravity while we command a near-zero Y vector
        boolean wantNoGravity = (hoverNoGravity.getValue() || motionMode.getValue() == MotionMode.NOAC)
                && Math.abs(realVector.y) <= 1.0E-2 && !mc.player.onGround();
        if (wantNoGravity) {
            if (!noGravitySet) {
                mc.player.setNoGravity(true);
                noGravitySet = true;
            }
        } else if (noGravitySet) {
            mc.player.setNoGravity(false);
            noGravitySet = false;
        }

        if (motionMode.getValue() == MotionMode.FIREWORKS && grimSafe.getValue()) {
            Vec3 lastLookSafe = lastLook != null ? lastLook : look;
            Vec3 lastSim = ElytraPhysics.simulateGlide(tickStartVel, lastLookSafe, ElytraPhysics.DEFAULT_GRAVITY);
            realVector = ElytraPhysics.clampToGrimEnvelope(realVector, tickStartVel, lastSim,
                    look, lastLookSafe, rescaleAmount.getValue(),
                    cornerClimb.getValue(), yCap.getValue());
            tryRocket(speed.getValue() * 0.5);
        }

        mc.player.setDeltaMovement(realVector);
        lastLook = look;
    }

    /**
     * idle behaviour: FIREWORKS uses the yaw-flip hover (vanilla-legal, the
     * server simulation agrees because it IS vanilla physics); VOID/NOAC just
     * damp velocity and hold no-gravity
     */
    private void idleHover(Vec3 look) {
        if (motionMode.getValue() == MotionMode.FIREWORKS && flipHover.getValue()) {
            // OpenKalama ElytraSlowFall: pitch 0, flip yaw 180 every 2 ticks —
            // vanilla glide lift cancels gravity, Grim sees pure vanilla input
            restoreNoGravity();
            mc.player.setXRot(0.0F);
            if ((flipTick++ & 2) == 0) {
                mc.player.setYRot(mc.player.getYRot() + 180.0F);
            }
            return;
        }
        Vec3 base = lastVelocity != null ? lastVelocity : Vec3.ZERO;
        Vec3 damped = base.scale(0.7);
        mc.player.setDeltaMovement(damped);
        if (damped.lengthSqr() < 1.0E-4 && !mc.player.onGround()) {
            if (!noGravitySet) {
                mc.player.setNoGravity(true);
                noGravitySet = true;
            }
        }
        lastVelocity = damped;
    }

    private Vec3 wasdMotion() {
        boolean forward = mc.options.keyUp.isDown();
        boolean back = mc.options.keyDown.isDown();
        boolean left = mc.options.keyLeft.isDown();
        boolean right = mc.options.keyRight.isDown();
        boolean up = mc.options.keyJump.isDown();
        boolean down = mc.options.keyShift.isDown();

        double strafe = (right ? 1.0 : 0.0) - (left ? 1.0 : 0.0);
        double fwd = (forward ? 1.0 : 0.0) - (back ? 1.0 : 0.0);
        double vert = (up ? 1.0 : 0.0) - (down ? 1.0 : 0.0);
        if (strafe == 0 && fwd == 0 && vert == 0) {
            return Vec3.ZERO;
        }
        return ElytraPhysics.movementInputToVelocity(
                new Vec3(strafe, vert, fwd), 1.0F, mc.player.getYRot());
    }

    // ---------------------------------------------------------------
    // GrimBoost: freeze movement packets inside the acceleration window
    // (port of OpenKalama ElytraGrimAcc)
    // ---------------------------------------------------------------

    @Subscribe
    private void onPacketSend(PacketEvent.Send event) {
        if (mc.player == null) return;

        if (event.getPacket() instanceof ServerboundAcceptTeleportationPacket) {
            // a setback teleport was confirmed: allow the next trigger immediately
            lastTriggerTick = Integer.MIN_VALUE / 2;
            return;
        }
        if (!(event.getPacket() instanceof ServerboundMovePlayerPacket)) {
            return;
        }

        boolean boostActive = grimBoost.getValue()
                && motionMode.getValue() == MotionMode.FIREWORKS
                && isEnabled()
                && mc.player.isFallFlying()
                && !mc.player.onGround();
        if (!boostActive) {
            if (freezing) {
                endFreeze();
            }
            return;
        }

        double vel = mc.player.getDeltaMovement().length();
        if (accelerating) {
            if (vel > accMax.getValue()) {
                accelerating = false;
            }
        } else if (vel < accMin.getValue()) {
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
            // one invalid move packet per window: Grim answers with a setback
            // teleport that resets its simulation state (rate-limited to 20t,
            // re-armed by the teleport confirm above)
            if (tickNow() - lastTriggerTick >= 20) {
                lastTriggerTick = tickNow();
                mc.getConnection().send(buildSetbackTrigger());
            }
        }

        // freeze: the server never sees this tick's movement; snap the client
        // back so the accumulated velocity stays coherent with our position
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
                    mc.player.getY() + 2.5 * (tickNow() % 3 + 1),
                    mc.player.getZ(), yaw, pitch, mc.player.onGround(), hc);
            case CRASH_PACKETS -> new ServerboundMovePlayerPacket.PosRot(
                    3.9999999E7,
                    mc.player.getY() + 2.5 * (tickNow() % 3 + 1),
                    3.9999999E7, yaw, pitch, true, hc);
        };
    }

    private void endFreeze() {
        freezing = false;
        freezeAnchor = null;
    }

    private int tickCache = -1;
    private int tickNow() {
        // player age advances once per tick and survives module reloads
        if (mc.player != null) {
            tickCache = mc.player.tickCount;
        }
        return tickCache;
    }

    @Subscribe
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!velFilter.getValue() || mc.player == null) return;
        // OpenKalama velocity filter: while gliding, drop server velocity
        // packets that would brake us (zero-horizontal or opposing direction)
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
    // takeoff / ground hop
    // ---------------------------------------------------------------

    /** jump-hold takeoff, instant when NOAC/chase wants flight; equips the elytra first */
    private void maybeTakeoff() {
        if (mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            // wings on — proceed below
        } else {
            equipElytraIfNeeded();
            return; // swap lands next tick at the earliest
        }
        if (!autoTakeoff.getValue()) return;

        boolean chaseWantsFlight = GuangxiElytraChaseModule.wantsFlightControl();
        boolean noac = motionMode.getValue() == MotionMode.NOAC;
        if (mc.options.keyJump.isDown()) {
            holdJumpTicks++;
        } else {
            holdJumpTicks = 0;
        }
        boolean wantTakeoff = chaseWantsFlight || noac
                || (holdJumpTicks >= takeoffTicks.getValue() && takeoffPhase == 0);

        if (!wantTakeoff) {
            takeoffPhase = 0;
            return;
        }

        if (mc.player.onGround()) {
            Vec3 vel = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(vel.x, 0.42, vel.z);
            mc.player.setSprinting(true);
            takeoffPhase = 1;
            if (noac && instantTakeoff.getValue()) {
                // shoreline ElytraBoost: rocket straight up and open the wings
                // this same tick — servers without an AC accept it
                mc.getConnection().send(new ServerboundPlayerCommandPacket(
                        mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                mc.player.startFallFlying();
                mc.player.setDeltaMovement(vel.x, 0.42 + takeoffBoost.getValue(), vel.z);
                takeoffPhase = 2;
            }
            return;
        }
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(
                    mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            mc.player.startFallFlying();
            takeoffPhase = 2;
            Vec3 look = mc.player.getLookAngle();
            if (look.lengthSqr() > 1.0E-6 && !noac) {
                mc.player.setDeltaMovement(look.scale(speed.getValue()));
            }
        }
    }

    /**
     * OpenKalama ElytraJump: pitch-locked bunny-hop skimming near the ground.
     * Fully vanilla movement — hop, open wings at a steep pitch (80° converts
     * altitude to horizontal speed), glide, land, hop again.
     *
     * @return true when the hop owned this tick
     */
    private boolean tickGroundHop() {
        if (GuangxiElytraChaseModule.provideDirection() != null) return false;
        boolean flying = mc.player.isFallFlying();
        if (!mc.player.onGround() && !flying) return false;

        Vec3 from = mc.player.position();
        BlockHitResult hit = mc.level.clip(new ClipContext(
                from, from.add(0, -hopHeight.getValue(), 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        boolean groundNear = hit.getType() != HitResult.Type.MISS || mc.player.onGround();
        if (!groundNear) return false;

        mc.player.setXRot(hopPitch.getValue().floatValue());
        if (mc.player.onGround()) {
            Vec3 vel = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(vel.x, 0.42, vel.z);
            mc.player.setSprinting(true);
        } else if (!flying && mc.player.getDeltaMovement().y < 0 && mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(
                    mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            mc.player.startFallFlying();
        }
        return true;
    }

    private void tryRocket(double targetSpeed) {
        ticksSinceRocket++;
        double horizSq = mc.player.getDeltaMovement().horizontalDistanceSqr();
        if (targetSpeed != Double.MAX_VALUE && horizSq >= targetSpeed * targetSpeed) return;
        if (ticksSinceRocket < refireTicks) return;
        if (hasUsefulBoost()) return; // a rocket is still pushing us (ex-ElytraDash)

        Result rocket = InventoryUtil.find(Items.FIREWORK_ROCKET, FULL_SCOPE);
        if (!rocket.found()) return;

        ticksSinceRocket = 0;
        refireTicks = InventoryUtil.fireworkRefireTicks(rocket.stack());
        GuizhouWare.swapManager.withSwap(rocket, SwapMode.ALTSILENT, SwapPriority.UTILITY,
                () -> mc.gameMode.useItem(mc.player, rocket.hand()));
    }

    /** an attached rocket still has more boost ticks than MinBoostTicks left */
    private boolean hasUsefulBoost() {
        int bestRemaining = Integer.MIN_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof FireworkRocketEntity rocket)) continue;
            if (((FireworkRocketEntityAccessor) rocket).guizhouware$getAttachedToEntity() != mc.player) continue;
            int remaining = estimateLifetime(rocket)
                    - ((FireworkRocketEntityAccessor) rocket).guizhouware$getLife();
            if (remaining > bestRemaining) bestRemaining = remaining;
        }
        return bestRemaining > minBoostTicks.getValue();
    }

    private int estimateLifetime(FireworkRocketEntity rocket) {
        ItemStack stack = rocket.getEntityData().get(FireworkRocketEntityAccessor.guizhouware$getFireworksItemData());
        Fireworks fireworks = stack.get(DataComponents.FIREWORKS);
        int flight = fireworks != null ? fireworks.flightDuration() : 1;
        return 10 * (flight + 1);
    }

    // ---------------------------------------------------------------
    // STEER mode (ex-ElytraFly): WASD → rotation requests, vanilla physics
    // ---------------------------------------------------------------

    private int idlePhase;

    private void tickSteer() {
        boolean forward = mc.options.keyUp.isDown();
        boolean back = mc.options.keyDown.isDown();
        boolean left = mc.options.keyLeft.isDown();
        boolean right = mc.options.keyRight.isDown();
        boolean up = mc.options.keyJump.isDown();
        boolean down = mc.options.keyShift.isDown();

        float realYaw = mc.player.getYRot();
        float strafe = (right ? 1f : 0f) - (left ? 1f : 0f);
        float fwd = (forward ? 1f : 0f) - (back ? 1f : 0f);
        boolean hasHorizontal = strafe != 0f || fwd != 0f;
        boolean wantUp = up && !down;
        boolean wantDown = down && !up;

        if (hasHorizontal) {
            float yaw = headingFromInput(realYaw, strafe, fwd);
            float targetPitch;
            if (wantUp) {
                targetPitch = -climbPitch.getValue().floatValue();
            } else if (wantDown) {
                targetPitch = climbPitch.getValue().floatValue();
            } else {
                targetPitch = mc.player.getXRot();
            }
            steerTo(yaw, targetPitch);
            return;
        }
        if (wantUp) {
            steerTo(realYaw, -90f);
            return;
        }
        if (wantDown) {
            steerTo(realYaw, 90f);
            return;
        }
        // idle: look slightly up and flip 180 every other phase so vanilla
        // lift cancels gravity — a legal hover
        boolean lookBack = (idlePhase++ & 1) == 1;
        steerTo(lookBack ? Mth.wrapDegrees(realYaw + 180f) : realYaw,
                steerHoverPitch.getValue().floatValue());
    }

    private void steerTo(float yaw, float pitch) {
        GuizhouWare.rotationManager.submit(new RotationRequest(
                ID, 50, yaw, Mth.clamp(pitch, -90f, 90f), RotationRequest.Mode.MOTION, true, true));
        steering = true;
    }

    private void stopSteer() {
        idlePhase = 0;
        if (steering) {
            GuizhouWare.rotationManager.cancel(ID);
            steering = false;
        }
    }

    private float headingFromInput(float cameraYaw, float strafe, float fwd) {
        double yawRad = Math.toRadians(cameraYaw);
        double fx = -Math.sin(yawRad), fz = Math.cos(yawRad);
        double rx = -Math.sin(yawRad + Math.PI / 2.0), rz = Math.cos(yawRad + Math.PI / 2.0);
        double wx = fx * fwd + rx * strafe;
        double wz = fz * fwd + rz * strafe;
        return (float) Math.toDegrees(Math.atan2(wz, wx)) - 90f;
    }

    // ---------------------------------------------------------------
    // auto equip (ex-ElytraDash): pull the elytra on for takeoff, put the
    // chestplate back when the module goes off
    // ---------------------------------------------------------------

    private void equipElytraIfNeeded() {
        if (!autoEquip.getValue()) return;
        if (mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) return;
        if (mc.gameMode == null || mc.player.containerMenu != mc.player.inventoryMenu) return;

        Result elytra = InventoryUtil.find(Items.ELYTRA, FULL_SCOPE);
        if (!elytra.found()) return;

        int slot = containerSlotOf(elytra);
        swapChestArmorWith(slot);
        stowedChestSlot = slot;
        weEquipped = true;
    }

    private void swapBackChest() {
        if (!weEquipped || mc.player == null) return;
        if (mc.gameMode == null || mc.player.containerMenu != mc.player.inventoryMenu) {
            return; // screen open — keep the elytra on rather than eating the armor
        }
        if (mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA) && stowedChestSlot != -1) {
            swapChestArmorWith(stowedChestSlot);
        }
        weEquipped = false;
        stowedChestSlot = -1;
    }

    private void swapChestArmorWith(int itemContainerSlot) {
        InventoryUtil.click(itemContainerSlot, 0, ClickType.PICKUP);
        InventoryUtil.click(CHEST_MENU_SLOT, 0, ClickType.PICKUP);
        InventoryUtil.click(itemContainerSlot, 0, ClickType.PICKUP);
    }

    private int containerSlotOf(Result result) {
        if (result.type() == ResultType.OFFHAND) return OFFHAND_MENU_SLOT;
        int slot = result.slot();
        return slot < 9 ? slot + 36 : slot;
    }

    @Override
    public String getDisplayInfo() {
        String mode = switch (motionMode.getValue()) {
            case STEER -> steering ? "Steer" : "Steer·Idle";
            case FIREWORKS -> grimBoost.getValue() ? "GrimBoost" : (grimSafe.getValue() ? "Grim" : "Rocket");
            case VOID -> "Void";
            case NOAC -> "NoAC";
        };
        if (freezing) return mode + "·Freeze";
        if (GuangxiElytraChaseModule.provideDirection() != null) return mode + "·Chase";
        return mode;
    }
}
