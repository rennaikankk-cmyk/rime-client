package dev.guizhou.features.modules.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.TotemPopEvent;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.movement.GuangxiElytraModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.RotationRequest;
import dev.guizhou.util.MathUtil;
import dev.guizhou.util.elytra.ElytraPhysics;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.inventory.Result;
import dev.guizhou.util.inventory.ResultType;
import dev.guizhou.util.inventory.SwapMode;
import dev.guizhou.util.inventory.SwapPriority;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.Iterator;

import static dev.guizhou.util.inventory.InventoryUtil.FULL_SCOPE;

/**
 * Elytra pursuit bot ported from rennaikanksmp ElytraBotV2 (FOLLOW + MACE).
 *
 * Drives GuangxiElytra's velocity instead of WASD while a target is held:
 * - FOLLOW: terrain-aware chase with an intercept course (cut off a runner
 *   instead of tail-chasing) and convergence braking near the target
 * - MACE: a pull-up / follow / hover / down-attack state machine — bank
 *   height, dive on the strike window, mace damage scales with fall distance
 *
 * Target intelligence: totem awareness (pop packet opens a kill window with
 * relaxed attack gates) and pearl awareness (track a thrown pearl, simulate
 * its landing, intercept there instead of chasing the stale position).
 */
public class GuangxiElytraChaseModule extends Module {

    public static final String ID = "GuangxiElytraChase";

    private static final int ROTATION_PRIORITY = 6;

    public enum Mode {
        FOLLOW, MACE, SPEAR
    }

    private enum Action {
        AFK, SLOW_SPEED, CIRCLING, TOWARDS, ESCAPING, PEARL_ESCAPE
    }

    private final Setting<Mode> mode = mode("Mode", Mode.FOLLOW);

    private final Setting<Double> targetRange = num("TargetRange", 80.0, 16.0, 256.0);
    private final Setting<Boolean> playerOnly = bool("PlayersOnly", true);
    private final Setting<Boolean> dynamicTarget = bool("DynamicTarget", true);

    // attack execution
    private final Setting<Double> attackRange = num("AttackRange", 3.2, 1.0, 6.0);
    private final Setting<Double> combatRange = num("CombatRange", 10.0, 2.0, 32.0);
    private final Setting<Double> attackCadence = num("AttackCadence", 0.9, 0.2, 1.0);
    private final Setting<Integer> attackMinInterval = num("AttackMinInterval", 3, 1, 20);
    private final Setting<Boolean> useMace = bool("UseMace", true);

    // totem intelligence
    private final Setting<Boolean> totemAware = bool("TotemAware", true);
    private final Setting<Integer> killWindowTicks = num("KillWindowTicks", 60, 10, 200);
    private final Setting<Boolean> totemHover = bool("TotemHover", false);
    private final Setting<Double> totemHoverHeight = num("TotemHoverHeight", 14.0, 4.0, 40.0);

    // pearl intelligence
    private final Setting<Boolean> pearlAware = bool("PearlAware", true);
    private final Setting<Integer> pearlSimTicks = num("PearlSimTicks", 80, 20, 200);

    // pursuit
    private final Setting<Boolean> intercept = bool("Intercept", true);
    private final Setting<Double> interceptLead = num("InterceptLead", 1.0, 0.0, 1.0);
    private final Setting<Boolean> terrainAware = bool("TerrainAware", true);
    private final Setting<Double> terrainLookahead = num("TerrainLookahead", 8.0, 2.0, 32.0);
    private final Setting<Integer> lostTargetTicks = num("LostTargetTicks", 40, 5, 200);
    private final Setting<Boolean> converge = bool("Converge", true);
    private final Setting<Double> groundFollowHeight = num("GroundFollowHeight", 2.0, 0.0, 8.0);

    // mace behaviour
    private final Setting<Double> maceHeight = num("MaceHeight", 10.0, 2.0, 64.0);
    private final Setting<Double> maceHeightGround = num("MaceHeightGround", 10.0, 2.0, 64.0);
    private final Setting<Integer> maceMaxPullUpTick = num("MaceMaxPullUpTicks", 40, 5, 200);
    private final Setting<Double> maceFollowMinHeight = num("MaceFollowMinHeight", 1.5, 0.0, 16.0);
    private final Setting<Boolean> maceDownAttack = bool("MaceDownAttack", true);
    private final Setting<Boolean> maceAngleOptimize = bool("MaceAngleOptimize", true);
    private final Setting<Double> maceAngleRange = num("MaceAngleRange", 16.0, 4.0, 64.0);

    private final Setting<Boolean> autoTakeoff = bool("AutoTakeoff", true);

    // spear behaviour (ported SpearV2)
    private final Setting<Double> combatSpearRange = num("SpearRange", 11.0, 2.0, 32.0);
    private final Setting<Boolean> spearPullOver = bool("SpearPullOver", true);
    private final Setting<Boolean> autoSpearCharge = bool("AutoSpearCharge", true);
    private final Setting<Double> spearChargeMinFall = num("SpearChargeMinFall", 1.5, 0.0, 16.0);
    private final Setting<Integer> spearChargeTicks = num("SpearChargeTicks", 8, 1, 40);
    private final Setting<Boolean> spearMaxDamage = bool("SpearMaxDamage", true);
    private final Setting<Double> spearStabMinSpeed = num("SpearStabMinSpeed", 1.1, 0.1, 5.0);
    private final Setting<Boolean> relativeSpeedGate = bool("RelativeSpeedGate", true);
    private final Setting<Double> dodgeIntensity = num("DodgeIntensity", 1.0, 0.1, 3.0);
    private final Setting<Integer> dodgeCycleTicks = num("DodgeCycleTicks", 8, 2, 40);

    // ---------------------------------------------------------------
    // runtime state
    // ---------------------------------------------------------------

    private static GuangxiElytraChaseModule instance;

    private int tick;
    private Player target;

    private Vec3 movementDirection;
    private double speedMultiplier = 1.0;

    private boolean attackFlag;

    private int totemPopTick = Integer.MIN_VALUE;

    private Entity trackedPearl;
    private Vec3 pearlLanding;
    private int pearlGraceTick = Integer.MIN_VALUE;

    private Action action = Action.CIRCLING;
    private boolean targetOnGround;
    private boolean inCombatRange;

    // last-sighting memory (ghost course after losing the target)
    private Vec3 lastSeenPos;
    private Vec3 lastSeenVel = Vec3.ZERO;
    private int lastSeenTick = Integer.MIN_VALUE;

    // target position ring buffer for velocity estimate + action classification
    private final ArrayDeque<Integer> posTicks = new ArrayDeque<>();
    private final ArrayDeque<Vec3> posRing = new ArrayDeque<>();

    // opponent pressure meter: their main-hand swings in the recent window
    private final ArrayDeque<Integer> targetSwings = new ArrayDeque<>();
    private static final int PRESSURE_WINDOW = 40;
    private static final int PRESSURE_THRESHOLD = 3;

    // mace state machine
    private static final int STATE_NONE = 0;
    private static final int STATE_PULL_UP = 1;
    private static final int STATE_FOLLOW = 2;
    private static final int STATE_WAIT_ATTACK = 3;
    private static final int STATE_HOVER = 4;
    private static final int STATE_DOWN_ATTACK = 5;

    private int maceState = STATE_NONE;
    private int startPullUpTick;
    private int downAttackTicks;
    private int waitAttackTicks;
    private double hoverAngle;
    private double lastFallDistance;
    private int lastAttackTick = Integer.MIN_VALUE / 2;

    // spear state machine (ported SpearV2)
    private static final int S_ORBIT = 0;
    private static final int S_ENGAGE = 1;
    private static final int S_DODGE = 2;
    private static final int S_PULL_OVER = 3;
    private int spearState = S_ORBIT;
    private double orbitAngle;
    private int dodgeTicksLeft;
    private int dodgeCooldownUntil = Integer.MIN_VALUE / 2;
    private int feintSign = 1;
    private int nextFeintFlip;
    private int engageUntil = Integer.MIN_VALUE / 2;
    private int reengageCooldownUntil = Integer.MIN_VALUE / 2;
    private int pullOverTicks;
    private boolean wasTargetUsingSpear;

    // auto spear charge state
    private int spearPrevSlot = -1;

    public GuangxiElytraChaseModule() {
        super("GuangxiElytraChase", "Elytra pursuit bot: intercept-course chase and mace dive with totem/pearl awareness.", Category.COMBAT);
        totemHoverHeight.setVisibility(v -> totemHover.getValue() && mode.getValue() == Mode.MACE);
        maceHeight.setVisibility(v -> mode.getValue() == Mode.MACE);
        maceHeightGround.setVisibility(v -> mode.getValue() == Mode.MACE);
        maceMaxPullUpTick.setVisibility(v -> mode.getValue() == Mode.MACE);
        maceFollowMinHeight.setVisibility(v -> mode.getValue() == Mode.MACE);
        maceDownAttack.setVisibility(v -> mode.getValue() == Mode.MACE);
        maceAngleOptimize.setVisibility(v -> mode.getValue() == Mode.MACE);
        maceAngleRange.setVisibility(v -> maceAngleOptimize.getValue() && mode.getValue() == Mode.MACE);
        instance = this;
    }

    @Override
    public void onEnable() {
        maceState = STATE_NONE;
        movementDirection = null;
        attackFlag = false;
        Module fly = GuizhouWare.moduleManager.getModuleByName(GuangxiElytraModule.ID);
        if (fly != null && !fly.isEnabled()) {
            fly.enable();
        }
    }

    @Override
    public void onDisable() {
        movementDirection = null;
        attackFlag = false;
        releaseAutoSpear();
    }

    // ---------------------------------------------------------------
    // static bridge for GuangxiElytra (its PreTick may run before ours)
    // ---------------------------------------------------------------

    public static Vec3 provideDirection() {
        GuangxiElytraChaseModule m = instance;
        if (m == null || !m.isEnabled() || m.target == null || m.movementDirection == null) {
            return null;
        }
        return m.movementDirection;
    }

    public static double provideSpeedMultiplier() {
        GuangxiElytraChaseModule m = instance;
        return m != null && m.isEnabled() ? m.speedMultiplier : 1.0;
    }

    public static boolean wantsFlightControl() {
        GuangxiElytraChaseModule m = instance;
        return m != null && m.isEnabled() && m.autoTakeoff.getValue() && m.target != null;
    }

    // ---------------------------------------------------------------
    // main tick
    // ---------------------------------------------------------------

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (nullCheck()) return;
        tick++;
        speedMultiplier = 1.0;

        refreshTarget();
        updateIntelligence();

        if (target == null) {
            movementDirection = null;
            attackFlag = false;
            releaseAutoSpear();
            return;
        }

        switch (mode.getValue()) {
            case MACE -> maceUpdate();
            case SPEAR -> spearUpdate();
            default -> followerUpdate();
        }
        updateSpearCharge();

        if (attackFlag) {
            attackFlag = false;
            tryAttack();
        }
    }

    @Subscribe
    private void onTotemPop(TotemPopEvent event) {
        if (target != null && event.getEntity() == target) {
            totemPopTick = tick;
        }
    }

    @Subscribe
    private void onPacketReceive(PacketEvent.Receive event) {
        if (target == null) return;
        // pressure meter: the target's main-hand swings (attacks and whiffs
        // both count — a rusher's tempo is the signal)
        if (event.getPacket() instanceof ClientboundAnimatePacket animate
                && animate.getAction() == ClientboundAnimatePacket.SWING_MAIN_HAND
                && animate.getId() == target.getId()) {
            targetSwings.addLast(tick);
        }
        while (!targetSwings.isEmpty() && tick - targetSwings.peekFirst() > PRESSURE_WINDOW) {
            targetSwings.pollFirst();
        }
    }

    // ---------------------------------------------------------------
    // targeting + intelligence
    // ---------------------------------------------------------------

    private void refreshTarget() {
        boolean invalid = target == null || !target.isAlive()
                || mc.player.distanceToSqr(target) > targetRange.getValue() * targetRange.getValue();
        if (dynamicTarget.getValue() || invalid) {
            Player best = null;
            double bestDist = Double.MAX_VALUE;
            for (Player player : mc.level.players()) {
                if (player == mc.player || player.isSpectator() || !player.isAlive()) continue;
                if (GuizhouWare.friendManager.isFriend(player)) continue;
                double dist = mc.player.distanceToSqr(player);
                if (dist < bestDist) {
                    best = player;
                    bestDist = dist;
                }
            }
        if (invalid) {
            target = best;
            onTargetSwitch();
        } else if (dynamicTarget.getValue() && best != null && best != target) {
            target = best;
            onTargetSwitch();
        }
    }
    }

    private void onTargetSwitch() {
        totemPopTick = Integer.MIN_VALUE;
        trackedPearl = null;
        pearlLanding = null;
        posTicks.clear();
        posRing.clear();
        targetSwings.clear();
        maceState = STATE_NONE;
    }

    private void updateIntelligence() {
        if (target != lastTrackedTarget) {
            lastTrackedTarget = target;
            onTargetSwitch();
        }
        updatePearl();
        if (target == null) return;

        // position ring buffer (3 ticks back) for velocity + classification
        posTicks.addLast(tick);
        posRing.addLast(target.position());
        while (posRing.size() > 3) {
            posTicks.pollFirst();
            posRing.pollFirst();
        }

        double rangeSq = combatRange.getValue() * combatRange.getValue();
        inCombatRange = mc.player.distanceToSqr(target) < rangeSq;
        targetOnGround = target.onGround();

        lastSeenPos = target.position();
        lastSeenVel = estimateTargetVelocity();
        lastSeenTick = tick;

        if (pearlActive()) {
            action = Action.PEARL_ESCAPE;
            return;
        }
        if (targetOnGround) {
            action = Action.SLOW_SPEED;
            return;
        }
        if (posRing.size() < 3) {
            action = Action.CIRCLING;
            return;
        }
        Iterator<Vec3> it = posRing.iterator();
        Vec3 pos0 = it.next();
        Vec3 pos1 = it.next();
        Vec3 pos2 = it.next();
        int oldestTick = posTicks.peekFirst();
        if (tick - oldestTick > 20) {
            action = Action.AFK;
            return;
        }
        double dist01 = pos0.distanceTo(pos1);
        double dist12 = pos1.distanceTo(pos2);
        if (dist01 < 1.0E-6 && dist12 < 1.0E-6) {
            action = Action.AFK;
        } else if (dist01 < 0.75 && dist12 < 0.75) {
            action = Action.SLOW_SPEED;
        } else {
            Vec3 ab = pos1.subtract(pos0);
            Vec3 bc = pos2.subtract(pos1);
            double dot = ab.dot(bc);
            double magAB = ab.length();
            double magBC = bc.length();
            double angleDeg = Math.toDegrees(Math.acos(Math.min(1.0, Math.max(-1.0, dot / (magAB * magBC)))));
            if (angleDeg < 60.0) {
                Vec3 toPlayer = mc.player.position().subtract(pos2);
                double moveDot = bc.normalize().dot(toPlayer.normalize());
                action = moveDot > 0 ? Action.TOWARDS : Action.ESCAPING;
            } else {
                action = Action.CIRCLING;
            }
        }
    }

    private Player lastTrackedTarget;

    /** per-tick velocity of the target from the tracked position ring, falling back to client velocity */
    private Vec3 estimateTargetVelocity() {
        if (posRing.size() >= 2) {
            Vec3 oldest = posRing.peekFirst();
            Vec3 newest = posRing.peekLast();
            int oldestTick = posTicks.peekFirst();
            int newestTick = posTicks.peekLast();
            int dt = newestTick - oldestTick;
            if (dt > 0) {
                return newest.subtract(oldest).scale(1.0 / dt);
            }
        }
        return target.getDeltaMovement();
    }

    // totem gates (ported v2 semantics)
    private boolean targetHasTotem() {
        if (!totemAware.getValue() || target == null) return false;
        boolean holds = target.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
                || target.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
        if (!holds) return false;
        // the item lingers one equipment sync after the pop: trust the pop packet
        return tick - totemPopTick > 3;
    }

    private boolean totemHoverActive() {
        return totemAware.getValue() && totemHover.getValue() && targetHasTotem();
    }

    private boolean killWindowOpen() {
        if (!totemAware.getValue() || totemPopTick == Integer.MIN_VALUE) return false;
        return tick - totemPopTick < killWindowTicks.getValue();
    }

    private boolean underHighPressure() {
        return targetSwings.size() >= PRESSURE_THRESHOLD;
    }

    // ---------------------------------------------------------------
    // pearl tracking (ported v2)
    // ---------------------------------------------------------------

    private void updatePearl() {
        if (!pearlAware.getValue() || target == null) {
            trackedPearl = null;
            pearlLanding = null;
            return;
        }
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.getType() != EntityType.ENDER_PEARL) continue;
            if (entity instanceof Projectile projectile
                    && projectile.getOwner() != null
                    && projectile.getOwner() != target) {
                continue;
            }
            double dist = entity.position().distanceToSqr(target.position());
            if (dist < 36.0 && dist < bestDist) {
                best = entity;
                bestDist = dist;
            }
        }
        if (best != null) {
            if (best != trackedPearl) {
                trackedPearl = best;
                pearlLanding = simulatePearlLanding(best.position(), best.getDeltaMovement());
            }
            pearlGraceTick = tick;
        } else if (trackedPearl != null) {
            // keep the landing spot valid briefly so the intercept vector stays
            // stable through the teleport frame
            if (tick - pearlGraceTick > 4) {
                trackedPearl = null;
                pearlLanding = null;
            }
        }
    }

    private boolean pearlActive() {
        return pearlLanding != null;
    }

    /** thrown item physics: 0.99 drag + 0.03 gravity per tick, block raycast per step */
    private Vec3 simulatePearlLanding(Vec3 pos, Vec3 velocity) {
        Vec3 current = pos;
        Vec3 motion = velocity;
        for (int i = 0; i < pearlSimTicks.getValue(); i++) {
            Vec3 next = current.add(motion);
            BlockHitResult hit = mc.level.clip(new ClipContext(
                    current, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() != HitResult.Type.MISS) {
                return hit.getLocation();
            }
            current = next;
            motion = motion.multiply(0.99, 0.99, 0.99).subtract(0, 0.03, 0);
            if (motion.lengthSqr() < 1.0E-6) break;
        }
        return current;
    }

    /** unified aim point: pearl landing overrides stale position data */
    private Vec3 predictTargetPos() {
        if (pearlActive()) {
            return pearlLanding;
        }
        return target.position();
    }

    // ---------------------------------------------------------------
    // FOLLOW behaviour (ported FollowerV2)
    // ---------------------------------------------------------------

    private void followerUpdate() {
        double cruise = Math.max(0.8, mc.player.getDeltaMovement().length());
        Vec3 aimPos = approachAim(predictTargetPos());
        Vec3 direction = aimPos.subtract(mc.player.position());
        if (targetOnGround) {
            direction = direction.add(0, groundFollowHeight.getValue(), 0);
        }
        if (terrainAware.getValue()) {
            direction = avoid(direction);
        }
        // convergence: inside the brake distance, stop sprinting through the
        // target and settle into a trailing follow
        double dist = direction.length();
        double convergeRadius = Math.max(4.0, cruise * 3.0);
        if (converge.getValue() && dist < convergeRadius) {
            double scale = 0.35 + 0.65 * (dist / convergeRadius);
            direction = direction.normalize().scale(cruise * scale);
        }
        movementDirection = direction;
        // FOLLOW also attacks when it happens to be in range
        if (inAttackRange() && attackGatesPass()) {
            attackFlag = true;
        }
    }

    // ---------------------------------------------------------------
    // MACE behaviour (ported MaceV2 state machine)
    // ---------------------------------------------------------------

    private void maceUpdate() {
        if (target == null) {
            Vec3 ghost = lostCourse(Math.max(0.8, mc.player.getDeltaMovement().length()));
            movementDirection = ghost;
            return;
        }
        switch (maceState) {
            case STATE_NONE -> maceState = stateNone();
            case STATE_PULL_UP -> maceState = statePullUp();
            case STATE_FOLLOW -> maceState = stateFollow();
            case STATE_WAIT_ATTACK -> maceState = stateWaitAttack();
            case STATE_HOVER -> maceState = stateHover();
            case STATE_DOWN_ATTACK -> maceState = stateDownAttack();
            default -> maceState = STATE_NONE;
        }
    }

    private int stateNone() {
        double fall = GuizhouWare.positionManager.getFallDistance();
        if (fall > 4 && mc.player.getY() > target.getY() + 4.0) {
            return STATE_FOLLOW;
        }
        return STATE_PULL_UP;
    }

    private int statePullUp() {
        if (startPullUpTick == 0) {
            startPullUpTick = tick;
        }
        // climb-out is also the long approach: intercept a runner's course
        // (XZ only — altitude stays owned by the pull-up state machine)
        Vec3 predictor = approachAimHorizontal(predictTargetPos());
        if (headBlocked()) {
            // cannot pull up here: follow out sideways first
            return transitionTo(STATE_FOLLOW);
        }
        // head-on closing: inside ~12 blocks while they press or trade, bank
        // 10 blocks of height over them first, then drop the mace; mace
        // damage scales with fall distance
        if (maceDownAttack.getValue()
                && (inCombatRange || action == Action.TOWARDS)
                && mc.player.distanceToSqr(target) < 150.0) {
            return transitionTo(STATE_DOWN_ATTACK);
        }
        double targetY = target.getY() + (targetOnGround ? maceHeightGround.getValue() : maceHeight.getValue());
        boolean mayFollow = mc.player.getY() >= targetY
                || tick > startPullUpTick + maceMaxPullUpTick.getValue();
        if (mayFollow) {
            // conservative play only: hover while their totem is up (off by
            // default — a totem is one extra life, breaking it IS progress)
            if (totemHoverActive()) {
                return transitionTo(STATE_HOVER);
            }
            return transitionTo(STATE_FOLLOW);
        }
        setTargetToPlayerUpper(predictor);
        return STATE_PULL_UP;
    }

    private int stateDownAttack() {
        if (downAttackTicks < 3 && (inCombatRange || action == Action.TOWARDS)) {
            downAttackTicks++;
            // climb at the point 10 above the target, braking on the steep
            // share so the 3-tick bank actually converts to height
            movementDirection = target.position().add(0, 10.0, 0).subtract(mc.player.position());
            Vec3 look = movementDirection.normalize();
            if (look.y < 0.0) {
                speedMultiplier = Math.min(1.0, 0.75 / Math.abs(look.y));
            }
            return STATE_DOWN_ATTACK;
        }
        if (inAttackRange()) {
            setTargetToPlayer(target.position().add(0, target.getBbHeight() * 0.5, 0));
            attackFlag = true;
            return transitionTo(STATE_WAIT_ATTACK);
        }
        // height banked or the window closed: hand over to the dive
        return transitionTo(STATE_FOLLOW);
    }

    private int stateHover() {
        if (!totemHoverActive()) {
            // totem gone (popped or never there): dive window
            return transitionTo(STATE_FOLLOW);
        }
        if (action == Action.ESCAPING || action == Action.PEARL_ESCAPE) {
            // target running: cut the orbit and reposition on its course
            setTargetToPlayerUpper(approachAimHorizontal(predictTargetPos()));
            return STATE_HOVER;
        }
        hoverAngle += (2 * Math.PI) / 40.0;
        double radius = combatRange.getValue() + 2.0;
        Vec3 orbitPoint = target.position().add(
                Math.cos(hoverAngle) * radius, totemHoverHeight.getValue(), Math.sin(hoverAngle) * radius);
        movementDirection = avoid(orbitPoint.subtract(mc.player.position()));
        if (movementDirection.length() < 5) {
            movementDirection = movementDirection.normalize().scale(5);
        }
        return STATE_HOVER;
    }

    private int stateFollow() {
        double fall = GuizhouWare.positionManager.getFallDistance();
        if (fall < 1.0E-6 && lastFallDistance > 1.0E-6) {
            // fall distance reset mid-chase: regain height first
            return transitionTo(STATE_PULL_UP);
        }
        lastFallDistance = fall;

        // conservative play only: abort the dive when they re-totemed
        if (totemHoverActive() && !inCombatRange && mc.player.getY() > target.getY() + 6.0) {
            return transitionTo(STATE_HOVER);
        }
        Vec3 targetPos = predictTargetPos();
        // terminal guidance: inside the strike window the aim snaps from the
        // predicted point to the live hitbox center — a sidestepping target
        // cannot slip the final ticks of the dive
        double strikeDist = mc.player.position().distanceTo(target.position());
        if (strikeDist < attackRange.getValue() * 1.6) {
            targetPos = target.position().add(0, target.getBbHeight() * 0.5, 0);
        }
        boolean inRange = inAttackRange();
        if (inRange && attackGatesPass()) {
            setTargetToPlayer(targetPos);
            attackFlag = true;
            return transitionTo(STATE_WAIT_ATTACK);
        }
        boolean feetBlocked = feetBlocked();
        // kill window: stay glued to the target, tolerate a bigger height
        // deficit before giving up the dive
        double followTolerance = maceFollowMinHeight.getValue() + (killWindowOpen() ? 3.0 : 0.0);
        boolean needPullUp = feetBlocked || target.getY() > mc.player.getY() + followTolerance;
        if (needPullUp) {
            if (inRange && attackGatesPass()) {
                setTargetToPlayer(targetPos);
                attackFlag = true;
                return transitionTo(STATE_WAIT_ATTACK);
            }
            return transitionTo(STATE_PULL_UP);
        }
        setTargetToPlayer(targetPos);
        // dive-speed shaping: the steeper the dive the harder we brake — more
        // ticks spent inside the strike window instead of blowing through it
        double verticalShare = 0.0;
        if (movementDirection.lengthSqr() > 1.0E-9) {
            verticalShare = Math.abs(movementDirection.normalize().y);
        }
        speedMultiplier = verticalShare > 0.75 ? Math.min(1.0, 0.75 / verticalShare) : 1.0;
        return STATE_FOLLOW;
    }

    private int stateWaitAttack() {
        if (action != Action.AFK && action != Action.SLOW_SPEED) {
            return transitionTo(STATE_PULL_UP);
        }
        if (++waitAttackTicks > 1) {
            if (targetOnGround) {
                return transitionTo(STATE_NONE);
            }
            return transitionTo(STATE_FOLLOW);
        }
        Vec3 targetPos = predictTargetPos();
        targetPos = new Vec3(targetPos.x, target.getY(), targetPos.z);
        setTargetToPlayer(targetPos);
        return STATE_WAIT_ATTACK;
    }

    private int transitionTo(int state) {
        if (state == STATE_PULL_UP) {
            startPullUpTick = tick;
        } else if (state == STATE_DOWN_ATTACK) {
            downAttackTicks = 0;
        } else if (state == STATE_WAIT_ATTACK) {
            waitAttackTicks = 0;
        }
        return state;
    }

    private void setTargetToPlayerUpper(Vec3 predictor) {
        Vec3 movement = new Vec3(predictor.x, predictor.y + maceHeight.getValue(), predictor.z)
                .subtract(mc.player.position());
        if (movement.length() < 5) {
            movement = movement.normalize().scale(5);
        }
        movement = clampApproachAngle(movement, predictor);
        movementDirection = avoid(movement);
    }

    private void setTargetToPlayer(Vec3 targetPos) {
        Vec3 movement = targetPos.subtract(mc.player.position()).normalize();
        if (targetOnGround) {
            movement = movement.add(0, groundFollowHeight.getValue(), 0);
        } else if (targetPos.y > mc.player.getY()) {
            // never chase upward in a mace dive: keep the height advantage
            movement = new Vec3(movement.x, 0, movement.z);
        }
        movement = clampApproachAngle(movement, targetPos);
        // v2 feint: alternate lateral drift while they aim a spear at us
        if (isTargetUsingSpear()) {
            movement = movement.add(lateralDodge(movement, dodgeIntensity.getValue() * 0.8)).normalize();
        }
        if (movement.length() < 5) {
            movement = movement.normalize().scale(5);
        }
        movementDirection = movement;
    }

    /**
     * angle clamp: far from the target, cap the climb/dive at 45° so the
     * horizontal speed — the thing that feeds elytra lift and closing rate —
     * survives the approach
     */
    private Vec3 clampApproachAngle(Vec3 movement, Vec3 aimPos) {
        if (!maceAngleOptimize.getValue() || targetOnGround) {
            return movement;
        }
        Vec3 flat = new Vec3(movement.x, 0, movement.z);
        double horizontalToTarget = mc.player.position().subtract(aimPos).horizontalDistance();
        if (horizontalToTarget <= maceAngleRange.getValue()) {
            return movement;
        }
        double horizontalDir = Math.max(Math.abs(movement.x), Math.abs(movement.z));
        if (horizontalDir < 0.1) {
            return movement;
        }
        if (movement.y > horizontalDir) {
            return new Vec3(movement.x, horizontalDir, movement.z);
        }
        if (movement.y < -horizontalDir) {
            return new Vec3(movement.x, -horizontalDir, movement.z);
        }
        return movement;
    }

    // ---------------------------------------------------------------
    // SPEAR behaviour (ported SpearV2): orbit outside their spear range,
    // engage on their spend / totem pop / our charged stab window
    // ---------------------------------------------------------------

    private static boolean isSpearItem(ItemStack stack) {
        return stack.is(Items.WOODEN_SPEAR) || stack.is(Items.STONE_SPEAR)
                || stack.is(Items.COPPER_SPEAR) || stack.is(Items.IRON_SPEAR)
                || stack.is(Items.GOLDEN_SPEAR) || stack.is(Items.DIAMOND_SPEAR)
                || stack.is(Items.NETHERITE_SPEAR);
    }

    public static boolean isUsingSpear(Player player) {
        return player != null && player.isUsingItem() && isSpearItem(player.getUseItem());
    }

    /** kinetic window: held long enough for charged (kinetic) damage */
    public boolean kineticReady(Player player) {
        return isUsingSpear(player) && player.getTicksUsingItem() >= spearChargeTicks.getValue();
    }

    public boolean isTargetUsingSpear() {
        return target != null && isUsingSpear(target);
    }

    /** impact speed for the kinetic gate: our speed plus their oncoming component */
    private double stabImpactSpeed() {
        double vel = mc.player.getDeltaMovement().length();
        if (relativeSpeedGate.getValue() && target != null) {
            Vec3 toTarget = target.position().subtract(mc.player.position());
            if (toTarget.lengthSqr() > 1.0E-4) {
                double closing = -target.getDeltaMovement().dot(toTarget.normalize());
                if (closing > 0) {
                    vel += closing;
                }
            }
        }
        return vel;
    }

    /** max-damage gate: stab only at speed peaks (kinetic damage ~ speed) */
    private boolean spearStabReady() {
        if (!spearMaxDamage.getValue()) {
            return true;
        }
        double minSpeed = spearStabMinSpeed.getValue();
        if (killWindowOpen()) {
            minSpeed *= 0.8;
        } else if (underHighPressure()) {
            minSpeed *= 0.85;
        }
        return stabImpactSpeed() >= minSpeed;
    }

    /** charged spear inside its range, aimed at our hemisphere */
    private boolean spearThreatens() {
        if (target == null || !kineticReady(target)) return false;
        double distance = mc.player.position().distanceTo(target.position());
        if (distance > combatSpearRange.getValue() + 3.0) return false;
        // actually aimed at us: a charged spear staring away threatens nobody
        Vec3 toUs = mc.player.position().subtract(target.position()).normalize();
        return target.getLookAngle().dot(toUs) > 0.25;
    }

    private void flipFeint() {
        feintSign = -feintSign;
    }

    /** lateral dodge relative to our current heading, sign alternates (feint) */
    private Vec3 lateralDodge(Vec3 heading, double magnitude) {
        Vec3 horizontal = new Vec3(heading.x, 0, heading.z);
        if (horizontal.lengthSqr() < 1.0E-4) {
            horizontal = new Vec3(1, 0, 0);
        }
        Vec3 side = new Vec3(0, 1, 0).cross(horizontal.normalize());
        return side.scale(magnitude);
    }

    private void spearUpdate() {
        if (target == null) {
            Vec3 ghost = lostCourse(Math.max(0.8, mc.player.getDeltaMovement().length()));
            movementDirection = ghost;
            return;
        }
        switch (spearState) {
            case S_ORBIT -> spearState = spearOrbit();
            case S_ENGAGE -> spearState = spearEngage();
            case S_DODGE -> spearState = spearDodge();
            case S_PULL_OVER -> spearState = spearPullOver();
            default -> spearState = S_ORBIT;
        }
    }

    private int spearOrbit() {
        // their spear was spent (or swapped away): strike window
        boolean spearDropped = wasTargetUsingSpear && !isTargetUsingSpear();
        wasTargetUsingSpear = isTargetUsingSpear();
        if (spearDropped || tick < engageUntil) {
            if (spearDropped) {
                engageUntil = tick + 40;
            }
            // their spear is spent — engage regardless of totem
            return S_ENGAGE;
        }
        // kill window: their totem just popped, dive in immediately
        if (killWindowOpen()) {
            return S_ENGAGE;
        }
        if (tick >= dodgeCooldownUntil && isTargetUsingSpear() && spearThreatens()) {
            dodgeTicksLeft = 5;
            dodgeCooldownUntil = tick + 20;
            flipFeint();
            return S_DODGE;
        }
        // proactive strike: our spear is charged and the impact gate is open
        if (tick >= reengageCooldownUntil
                && isUsingSpear(mc.player)
                && kineticReady(mc.player)
                && spearStabReady()
                && attackGatesPass()) {
            engageUntil = tick + 40;
            reengageCooldownUntil = engageUntil + 20;
            return S_ENGAGE;
        }
        movementDirection = avoid(spearOrbitHeading());
        return S_ORBIT;
    }

    private int spearDodge() {
        if (--dodgeTicksLeft <= 0) {
            return S_ORBIT;
        }
        Vec3 away = mc.player.position().subtract(target.position());
        Vec3 horizontalAway = new Vec3(away.x, 0, away.z).normalize();
        if (horizontalAway.lengthSqr() < 1.0E-4) {
            horizontalAway = new Vec3(1, 0, 0);
        }
        movementDirection = horizontalAway
                .scale(4.0 * dodgeIntensity.getValue())
                .add(lateralDodge(horizontalAway, 8.0 * dodgeIntensity.getValue() * feintSign))
                .add(0, 1.0, 0);
        return S_DODGE;
    }

    private int spearEngage() {
        if (tick >= dodgeCooldownUntil && isTargetUsingSpear() && spearThreatens()) {
            dodgeTicksLeft = 5;
            dodgeCooldownUntil = tick + 20;
            flipFeint();
            return S_DODGE;
        }
        if (tick >= engageUntil) {
            return S_ORBIT;
        }
        Vec3 targetPos = predictTargetPos();
        double range = combatSpearRange.getValue();
        boolean farApproach = mc.player.position().distanceTo(target.position()) > range + 8.0;
        if (farApproach) {
            // still closing in: cut the course, steer around terrain; the
            // final dive keeps the raw predicted point for accuracy
            targetPos = approachAimHorizontal(targetPos, interceptLead.getValue() * 0.7);
        }
        setTargetToPlayer(targetPos);
        if (farApproach) {
            movementDirection = avoid(movementDirection);
        }
        if (inAttackRange() && attackGatesPass()) {
            boolean charged = isUsingSpear(mc.player);
            if (!charged || (kineticReady(mc.player) && spearStabReady())) {
                attackFlag = true;
                // poke and retreat: strike thrown, peel off at 45° while the
                // cooldown recovers instead of hovering in their spear range
                if (spearPullOver.getValue()) {
                    pullOverTicks = 0;
                    return S_PULL_OVER;
                }
            }
        }
        return S_ENGAGE;
    }

    private int spearPullOver() {
        if (++pullOverTicks > 15) {
            return S_ORBIT;
        }
        // target fled hard mid-retreat: cut it short and re-press
        if (action == Action.ESCAPING
                && mc.player.position().distanceTo(target.position()) > combatSpearRange.getValue() + 6.0) {
            return S_ORBIT;
        }
        Vec3 away = mc.player.position().subtract(target.position());
        Vec3 flat = new Vec3(away.x, 0, away.z);
        if (flat.lengthSqr() < 1.0E-4) {
            flat = new Vec3(1, 0, 0);
        }
        double cruise = Math.max(0.8, mc.player.getDeltaMovement().length());
        // peel away level-or-up at 45° — keeps speed and banks a little
        // height for the next pass
        movementDirection = flat.normalize().add(0, 1.0, 0).normalize().scale(cruise);
        return S_PULL_OVER;
    }

    private Vec3 spearOrbitHeading() {
        if (tick >= nextFeintFlip) {
            flipFeint();
            nextFeintFlip = tick + dodgeCycleTicks.getValue();
        }
        // chasing: a runner is not pressed by an orbit — cut across its
        // course until we are in spear range
        boolean chasing = action == Action.ESCAPING
                || mc.player.position().subtract(target.position()).horizontalDistance()
                        > combatSpearRange.getValue() * 2.5;
        Vec3 heading;
        if (chasing) {
            Vec3 aim = approachAimHorizontal(predictTargetPos(), interceptLead.getValue());
            heading = aim.subtract(mc.player.position());
        } else {
            orbitAngle += (2 * Math.PI) / 36.0;
            double radius = combatSpearRange.getValue() + 2.0;
            // wave the orbit up/down: the periodic dip banks spear-charge
            // fall distance, so a full charge is available to press with
            double bob = 3.0 * Math.sin(2.0 * orbitAngle);
            Vec3 orbitPoint = target.position().add(
                    Math.cos(orbitAngle) * radius, 6.0 + bob, Math.sin(orbitAngle) * radius);
            heading = orbitPoint.subtract(mc.player.position());
        }
        if (isTargetUsingSpear()) {
            // feint: persistent small lateral drift that flips on a cycle
            heading = heading.add(lateralDodge(heading, 0.5 * dodgeIntensity.getValue() * feintSign));
        }
        if (heading.length() < 5) {
            heading = heading.normalize().scale(5);
        }
        return heading;
    }

    // ---------------------------------------------------------------
    // auto spear charge: hold use on the spear during the dive window so
    // kinetic (charged) hits actually deal damage
    // ---------------------------------------------------------------

    private void updateSpearCharge() {
        boolean diving = autoSpearCharge.getValue()
                && mode.getValue() != Mode.FOLLOW
                && mc.player.isFallFlying()
                && target != null
                && GuizhouWare.positionManager.getFallDistance() >= spearChargeMinFall.getValue();
        if (!diving) {
            releaseAutoSpear();
            return;
        }
        if (isUsingSpear(mc.player)) {
            return; // charging continues automatically while use is held
        }
        int spearSlot = findHotbarSlot(this::isSpearItemStack);
        if (spearSlot != -1) {
            if (spearPrevSlot == -1) {
                spearPrevSlot = mc.player.getInventory().getSelectedSlot();
            }
            if (mc.player.getInventory().getSelectedSlot() != spearSlot) {
                mc.player.getInventory().setSelectedSlot(spearSlot);
            }
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        }
    }

    private boolean isSpearItemStack(ItemStack stack) {
        return isSpearItem(stack);
    }

    private void releaseAutoSpear() {
        if (mc.player != null && mc.player.isUsingItem() && isUsingSpear(mc.player)) {
            mc.player.stopUsingItem();
        }
        if (spearPrevSlot != -1 && mc.player != null) {
            mc.player.getInventory().setSelectedSlot(spearPrevSlot);
            spearPrevSlot = -1;
        }
    }

    private int findHotbarSlot(java.util.function.Predicate<ItemStack> predicate) {
        for (int slot = 0; slot < 9; slot++) {
            if (predicate.test(mc.player.getInventory().getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------
    // pursuit helpers (ported PursuitUtils)
    // ---------------------------------------------------------------

    /** ghost course after losing the target: extrapolate the last sighting */
    private Vec3 lostCourse(double cruise) {
        if (lastSeenPos == null || tick - lastSeenTick >= lostTargetTicks.getValue()) {
            return null;
        }
        int dt = tick - lastSeenTick;
        Vec3 ghost = lastSeenPos.add(lastSeenVel.scale(Math.min(dt, 10)));
        Vec3 heading = ghost.subtract(mc.player.position());
        if (heading.lengthSqr() < 1.0E-9) {
            return null;
        }
        return heading.normalize().scale(cruise);
    }

    /** intercept course on the moving target; falls back to the given aim */
    private Vec3 approachAim(Vec3 fallbackAim) {
        return approachAim(fallbackAim, interceptLead.getValue());
    }

    private Vec3 approachAim(Vec3 fallbackAim, double leadScale) {
        if (!intercept.getValue() || pearlActive()) {
            return fallbackAim;
        }
        double cruise = Math.max(0.8, mc.player.getDeltaMovement().length());
        return interceptPoint(mc.player.position(), cruise,
                target.position(), estimateTargetVelocity(), leadScale, 40);
    }

    private Vec3 approachAimHorizontal(Vec3 fallbackAim) {
        return approachAimHorizontal(fallbackAim, interceptLead.getValue());
    }

    private Vec3 approachAimHorizontal(Vec3 fallbackAim, double leadScale) {
        Vec3 aim = approachAim(fallbackAim, leadScale);
        if (aim == fallbackAim) {
            return fallbackAim;
        }
        return new Vec3(aim.x, fallbackAim.y, aim.z);
    }

    /**
     * rendezvous point: solve |T + Vt*t - P| = Vp*t for the smallest positive
     * t; fallback is a lead point at t = |T-P|/Vp, capped so stale velocity
     * cannot project the target across the world
     */
    private static Vec3 interceptPoint(Vec3 playerPos, double playerSpeed,
            Vec3 targetPos, Vec3 targetVel, double leadScale, double maxLeadTicks) {
        double scale = Math.max(0.0, Math.min(1.0, leadScale));
        Vec3 vel = targetVel.scale(scale);
        Vec3 rel = targetPos.subtract(playerPos);
        double a = vel.dot(vel) - playerSpeed * playerSpeed;
        double b = 2.0 * rel.dot(vel);
        double c = rel.dot(rel);
        double t = -1.0;
        if (Math.abs(a) < 1.0E-9) {
            if (b < -1.0E-9) {
                t = -c / b;
            }
        } else {
            double disc = b * b - 4.0 * a * c;
            if (disc >= 0.0) {
                double sq = Math.sqrt(disc);
                double t1 = (-b - sq) / (2.0 * a);
                double t2 = (-b + sq) / (2.0 * a);
                if (t1 > 0.0 && t2 > 0.0) {
                    t = Math.min(t1, t2);
                } else {
                    t = Math.max(t1, t2);
                }
            }
        }
        if (t < 0.0) {
            t = playerSpeed > 1.0E-6 ? rel.length() / playerSpeed : 0.0;
        }
        t = Math.max(0.0, Math.min(maxLeadTicks, t));
        return targetPos.add(vel.scale(t));
    }

    private boolean headBlocked() {
        Vec3 from = mc.player.position();
        BlockHitResult hit = mc.level.clip(new ClipContext(
                from, from.add(0, 1.0, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() != HitResult.Type.MISS;
    }

    private boolean feetBlocked() {
        Vec3 from = mc.player.position();
        BlockHitResult hit = mc.level.clip(new ClipContext(
                from, from.add(0, -1.0, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() != HitResult.Type.MISS;
    }

    /** candidate heading offsets {yaw, pitch} in degrees, ordered by turn cost */
    private static final double[][] FAN_CANDIDATES = {
            {14, 0}, {-14, 0},
            {30, 0}, {-30, 0},
            {48, 0}, {-48, 0},
            {0, -15}, {0, 15},
            {30, -15}, {-30, -15},
    };

    private static final double BLOCKED_COST = 4.0;

    /**
     * terrain avoidance by fan sampling: raycast each candidate heading, keep
     * the cheapest clear one (smallest turn) so we side-step ridges at speed
     * instead of ballooning straight up
     */
    private Vec3 avoid(Vec3 desired) {
        if (!terrainAware.getValue()) return desired;
        double len = desired.length();
        if (len < 1.0E-6) return desired;
        Vec3 dir = desired.normalize();
        Vec3 eye = mc.player.getEyePosition();
        BlockHitResult centerHit = raycast(eye, dir, terrainLookahead.getValue());
        if (centerHit.getType() == HitResult.Type.MISS) {
            return desired;
        }
        double baseYaw = Math.atan2(-dir.x, dir.z);
        double basePitch = -Math.asin(Math.max(-1.0, Math.min(1.0, dir.y)));
        Vec3 best = null;
        double bestCost = Double.MAX_VALUE;
        for (double[] off : FAN_CANDIDATES) {
            Vec3 cand = fromYawPitch(baseYaw + Math.toRadians(off[0]), basePitch + Math.toRadians(off[1]));
            BlockHitResult hit = raycast(eye, cand, terrainLookahead.getValue());
            double cost;
            if (hit.getType() == HitResult.Type.MISS) {
                cost = Math.abs(off[0]) / 48.0 + Math.abs(off[1]) / 30.0;
            } else {
                double dist = eye.distanceTo(hit.getLocation());
                cost = BLOCKED_COST + (1.0 - dist / terrainLookahead.getValue()) * 2.0;
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = cand;
            }
        }
        if (bestCost < BLOCKED_COST) {
            return best.scale(len);
        }
        // everything sampled is blocked: slide along the wall face; climbing
        // harder here is how you get stuck under a nether ceiling
        return slideAlongFace(centerHit, dir, len);
    }

    private Vec3 slideAlongFace(BlockHitResult hit, Vec3 dir, double len) {
        Direction face = hit.getDirection();
        Vec3 horizontal = new Vec3(dir.x, 0, dir.z);
        if (horizontal.lengthSqr() < 1.0E-4) {
            horizontal = new Vec3(1, 0, 0);
        }
        Vec3 slide;
        if (face == Direction.DOWN) {
            // ceiling: press down and away
            slide = horizontal.normalize().add(0, -0.2, 0);
        } else if (face == Direction.UP) {
            // floor: lift off gently
            slide = horizontal.normalize().add(0, 0.4, 0);
        } else {
            Vec3 n = new Vec3(face.getStepX(), 0, face.getStepZ());
            Vec3 tangent = new Vec3(0, 1, 0).cross(n).normalize();
            if (tangent.dot(horizontal) < 0) {
                tangent = tangent.scale(-1);
            }
            // hug the wall sideways with a slight lift to peel off
            slide = tangent.add(0, 0.25, 0);
        }
        return slide.normalize().scale(len);
    }

    private BlockHitResult raycast(Vec3 eye, Vec3 dir, double lookahead) {
        return mc.level.clip(new ClipContext(
                eye, eye.add(dir.scale(lookahead)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
    }

    /** MC convention: yaw around Y, pitch positive down */
    private static Vec3 fromYawPitch(double yaw, double pitch) {
        double cp = Math.cos(pitch);
        return new Vec3(-Math.sin(yaw) * cp, -Math.sin(pitch), Math.cos(yaw) * cp);
    }

    // ---------------------------------------------------------------
    // attack execution (house style: silent rotation + LOS + swap)
    // ---------------------------------------------------------------

    private boolean inAttackRange() {
        if (target == null) return false;
        double rangeSq = attackRange.getValue() * attackRange.getValue();
        return target.getBoundingBox().distanceToSqr(mc.player.position()) <= rangeSq;
    }

    /** attack cadence: cooldown progress + min interval, relaxed in the kill window / under pressure */
    private boolean attackGatesPass() {
        boolean killWindow = killWindowOpen();
        boolean pressure = !killWindow && underHighPressure();
        double threshold = attackCadence.getValue();
        if (killWindow) {
            threshold = Math.min(0.7, threshold * 0.8);
        } else if (pressure) {
            threshold = Math.min(0.75, threshold * 0.85);
        }
        if (mc.player.getAttackStrengthScale(0.5F) < threshold) {
            return false;
        }
        int interval = attackMinInterval.getValue();
        if (killWindow || pressure) {
            interval = Math.max(1, interval - 1);
        }
        return tick - lastAttackTick >= interval;
    }

    private void tryAttack() {
        if (target == null || !target.isAlive()) return;

        Vec3 eyePos = mc.player.getEyePosition(1.0F);
        AABB box = target.getBoundingBox();
        Vec3 aimPoint = new Vec3(
                net.minecraft.util.Mth.clamp(eyePos.x, box.minX, box.maxX),
                net.minecraft.util.Mth.clamp(eyePos.y, box.minY, box.maxY),
                net.minecraft.util.Mth.clamp(eyePos.z, box.minZ, box.maxZ));
        float[] angles = MathUtil.calcAngle(eyePos, aimPoint);
        GuizhouWare.rotationManager.submit(new RotationRequest(
                ID, ROTATION_PRIORITY, angles[0], angles[1], RotationRequest.Mode.SILENT));

        float serverYaw = GuizhouWare.rotationManager.getServerYaw();
        float serverPitch = GuizhouWare.rotationManager.getServerPitch();
        if (!box.contains(eyePos)) {
            Vec3 lookVec = ElytraPhysics.pitchYawToRotation(serverPitch, serverYaw);
            Vec3 reachEnd = eyePos.add(lookVec.scale(attackRange.getValue()));
            if (box.clip(eyePos, reachEnd).isEmpty()) {
                return; // server-side look does not touch the hitbox yet
            }
        }

        lastAttackTick = tick;

        // charged spear stab: this IS the kinetic damage path, never swap out
        if (isUsingSpear(mc.player)) {
            mc.gameMode.attack(mc.player, target);
            mc.player.swing(InteractionHand.MAIN_HAND);
            return;
        }

        double fall = GuizhouWare.positionManager.getFallDistance();
        if (useMace.getValue() && fall > 1.5) {
            Result mace = findHotbar(Items.MACE);
            if (mace != null) {
                GuizhouWare.swapManager.withSwap(mace, SwapMode.SILENT, SwapPriority.WEAPON,
                        () -> {
                            mc.gameMode.attack(mc.player, target);
                            mc.player.swing(InteractionHand.MAIN_HAND);
                        });
                return;
            }
        }
        mc.gameMode.attack(mc.player, target);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private Result findHotbar(net.minecraft.world.item.Item item) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.is(item)) {
                return new Result(slot, stack, ResultType.HOTBAR);
            }
        }
        return null;
    }

    @Override
    public String getDisplayInfo() {
        if (target == null) return null;
        String base = switch (mode.getValue()) {
            case MACE -> "Mace";
            case SPEAR -> "Spear";
            default -> "Follow";
        };
        if (killWindowOpen()) return base + "·KillWindow";
        if (pearlActive()) return base + "·Pearl";
        if (isUsingSpear(mc.player)) return base + "·Charged";
        return base;
    }
}
