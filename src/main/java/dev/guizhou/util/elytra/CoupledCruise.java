package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;

/**
 * Manual/AutoFly cruise. Select a look before vanilla glide, then apply its
 * envelope to that completed glide calculation. No packets or entities.
 * The local envelope is a heuristic, not evidence of server acceptance.
 *
 * Dual view (reference-client parity): when the caller passes dualView=true
 * and the vertical intent is nonzero, the selection also carries a decoupled
 * box look (level on climb, a solved dive pair on descent). apply() then
 * anchors the envelope's second view on the box look instead of the previous
 * tick's real look. The box look is only legitimate when the caller reported
 * it to the server with a duplicate rotation packet on a Via target protocol
 * before 1.21 (elytra-capable releases only) — the caller owns that capability gate
 * (DualViewSupport) and the extra packet.
 */
public final class CoupledCruise {
    public record Config(double cruisePitch, double climbPitch, double descentPitch,
                         boolean smartClimb, double descentFloor, boolean porpoise,
                         double heightWindow, double speedCap, double boxCap, double inset,
                         double dualClimbAngle, double dualDiveAngle) {
        public Config {
            cruisePitch = bounded(cruisePitch, -89.5, 89.5, 15);
            climbPitch = bounded(climbPitch, -89.5, 0, -45);
            descentPitch = bounded(descentPitch, 0, 89.5, 30);
            descentFloor = bounded(descentFloor, -20, 0, -1.7);
            heightWindow = bounded(heightWindow, 1, 1200, 60);
            speedCap = bounded(speedCap, 0.05, 15, 15);
            boxCap = bounded(boxCap, 0.01, 5, 1.7);
            inset = bounded(inset, 0, 0.1, 0.002);
            dualClimbAngle = bounded(dualClimbAngle, 0, 89.5, 0);
            dualDiveAngle = bounded(dualDiveAngle, 0, 30, 0);
        }

        public Config(double cruisePitch, double climbPitch, double descentPitch,
                      boolean smartClimb, double descentFloor, boolean porpoise,
                      double heightWindow, double speedCap, double boxCap, double inset) {
            this(cruisePitch, climbPitch, descentPitch, smartClimb, descentFloor, porpoise,
                    heightWindow, speedCap, boxCap, inset, 0, 0);
        }

        public static Config defaults() {
            return new Config(15, -45, 30, true, -1.7, false, 60, 15, 1.7, .002);
        }
    }

    public enum Phase { LEVEL, DESCEND, CLIMB }

    public enum BoostMode { Gentle, Aggressive }

    /** Vertical means manual jump/sneak; horizontal is the world-space WASD wish. */
    public record Intent(Vec3 horizontal, double vertical, boolean autoFly, boolean accelerated, BoostMode boostMode) {
        public Intent {
            if (boostMode == null) boostMode = BoostMode.Aggressive;
        }
        public Intent(Vec3 horizontal, double vertical, boolean autoFly, boolean accelerated) {
            this(horizontal, vertical, autoFly, accelerated, BoostMode.Aggressive);
        }
        public Intent(Vec3 horizontal, double vertical, boolean autoFly) {
            this(horizontal, vertical, autoFly, false);
        }
    }

    /** boxLook is the decoupled second view; null unless dualActive. */
    public record Selection(Vec3 look, Phase phase, int vertical, boolean pureVertical, boolean active,
                            boolean dualActive, Vec3 boxLook, boolean banked, boolean maximumLift) {
        public Selection(Vec3 look, Phase phase, int vertical, boolean pureVertical, boolean active,
                         boolean dualActive, Vec3 boxLook, boolean banked) {
            this(look, phase, vertical, pureVertical, active, dualActive, boxLook, banked, false);
        }
        public Selection(Vec3 look, Phase phase, int vertical, boolean pureVertical, boolean active,
                         boolean dualActive, Vec3 boxLook) {
            this(look, phase, vertical, pureVertical, active, dualActive, boxLook, false);
        }
        private static Selection idle() {
            return new Selection(Vec3.ZERO, Phase.LEVEL, 0, false, false, false, null);
        }
    }

    /** Invalid/infeasible results are never applied; the caller keeps vanilla glide. */
    public record Result(Vec3 velocity, boolean feasible, boolean speedLimited,
                         boolean descentUnmet, boolean horizontalUnmet) {}

    /** Exposed for independent constraint checks; bounds are inclusive. */
    public record Envelope(Vec3 min, Vec3 max) {
        public boolean contains(Vec3 v, double tolerance) {
            return finite(v) && v.x >= min.x - tolerance && v.x <= max.x + tolerance
                    && v.y >= min.y - tolerance && v.y <= max.y + tolerance
                    && v.z >= min.z - tolerance && v.z <= max.z + tolerance;
        }
    }

    private double acceleratedReferenceY = Double.NaN;
    private boolean bankSide;
    private Vec3 combatClimbAxis = Vec3.ZERO;
    private double referenceY = Double.NaN;
    private Phase phase = Phase.LEVEL;
    private Config lastConfig;

    public void reset() {
        referenceY = Double.NaN;
        acceleratedReferenceY = Double.NaN;
        bankSide = false;
        combatClimbAxis = Vec3.ZERO;
        phase = Phase.LEVEL;
        lastConfig = null;
    }

    public Phase phase() { return phase; }

    /**
     * Aggressive manual climb: retain the pilot's heading and compare four cardinal
     * secondary views. Horizontal support feeds next tick's glide lift. This is
     * bounded work (four envelopes), not a full yaw sweep, and grants no thrust
     * by itself: apply still requires the caller's actual glide and rocket gate.
     */
    public static Selection fastClimb(Config config, Selection selected, Vec3 previous, double gravity) {
        if (config == null || selected == null || !selected.active() || !selected.dualActive()
                || selected.vertical() <= 0 || selected.pureVertical() || !validMotion(previous)
                || !finite(selected.look()) || Math.hypot(selected.look().x, selected.look().z) < 1e-9
                || !Double.isFinite(gravity) || gravity < 0) return selected;
        double yaw = Math.atan2(-selected.look().x, selected.look().z);
        Vec3 look = lookAt(-89.9, yaw);
        Vec3 simulated = ElytraPhysics.simulateGlide(previous, look, gravity);
        Vec3 best = null;
        double score = Double.NEGATIVE_INFINITY;
        for (Vec3 axis : CLIMB_AXES) {
            Envelope box = envelope(config, previous, simulated, look, axis);
            if (box == null) return selected;
            double x = farEdge(box.min.x, box.max.x), z = farEdge(box.min.z, box.max.z);
            double candidate = x * x + z * z;
            if (candidate > score + 1e-9) { score = candidate; best = axis; }
        }
        return new Selection(look, selected.phase(), 1, false, true, true, best, false, true);
    }

    private static final Vec3[] CLIMB_AXES = {
            new Vec3(0, 0, 1), new Vec3(-1, 0, 0), new Vec3(0, 0, -1), new Vec3(1, 0, 0)
    };

    /** Preview the next selection without advancing banking or height-cycle state. */
    public Selection previewSelection(Config config, Intent intent, double headingYaw, double playerY, boolean dualView) {
        CoupledCruise copy = new CoupledCruise();
        copy.acceleratedReferenceY = acceleratedReferenceY;
        copy.bankSide = bankSide;
        copy.combatClimbAxis = combatClimbAxis;
        copy.referenceY = referenceY;
        copy.phase = phase;
        copy.lastConfig = lastConfig;
        return copy.select(config, intent, headingYaw, playerY, dualView);
    }

    /** headingYaw is in degrees; NaN means the caller has no heading. */
    public Selection select(Config config, Intent intent, double headingYaw, double playerY) {
        return select(config, intent, headingYaw, playerY, false);
    }

    /**
     * dualView requests the reference client's dual-look pair on climb/dive.
     * The caller must have verified the connection accepts duplicate rotation
     * packets (DualViewSupport) and must report selection.boxLook() to the
     * server ahead of the movement packet.
     */
    public Selection select(Config config, Intent intent, double headingYaw, double playerY, boolean dualView) {
        if (config == null || intent == null || !finite(intent.horizontal())
                || !Double.isFinite(intent.vertical()) || !Double.isFinite(playerY)) {
            reset();
            return Selection.idle();
        }
        if (!config.equals(lastConfig)) reset();
        lastConfig = config;
        boolean horizontal = Math.hypot(intent.horizontal().x, intent.horizontal().z) > 1e-6;
        int vertical = intent.vertical() > 1e-9 ? 1 : intent.vertical() < -1e-9 ? -1 : 0;
        boolean pureVertical = !horizontal && vertical != 0;
        if ((!horizontal && vertical == 0 && !intent.autoFly())
                || (!horizontal && !pureVertical && !Double.isFinite(headingYaw))) {
            reset();
            return Selection.idle();
        }
        if (config.porpoise() && vertical == 0 && !pureVertical) {
            if (!Double.isFinite(referenceY)) {
                referenceY = playerY;
                phase = Phase.DESCEND;
            }
            if (phase == Phase.DESCEND && playerY <= referenceY - config.heightWindow()) {
                phase = Phase.CLIMB;
            } else if (phase == Phase.CLIMB && playerY >= referenceY) {
                // Keep the original top. Accumulating each tick's apex overshoot
                // makes long-distance porpoising climb without any user input.
                phase = Phase.DESCEND;
            }
            vertical = phase == Phase.CLIMB ? 1 : -1;
        } else {
            referenceY = Double.NaN;
            phase = Phase.LEVEL;
        }
        boolean accelerated = intent.accelerated() && dualView && !pureVertical && vertical <= 0;
        if (!accelerated) acceleratedReferenceY = Double.NaN;
        // Preserve pure vertical explicitly; cos(89.5 degrees) is not zero.
        if (pureVertical) return new Selection(new Vec3(0, vertical, 0), phase, vertical, true, true, false, null);
        double yaw = horizontal ? Math.atan2(-intent.horizontal().x, intent.horizontal().z)
                : Math.toRadians(headingYaw % 360);
        // GZBoost climbs distribute horizontal support between two envelope views.
        // Near an axis this includes real side-to-side motion; near a diagonal it
        // alternates the axis budget. Smooth weights avoid an angle threshold jump.
        if (vertical > 0 && dualView && intent.accelerated()) {
            double axisAngle = Math.toDegrees(Math.atan2(Math.min(Math.abs(Math.sin(yaw)), Math.abs(Math.cos(yaw))),
                    Math.max(Math.abs(Math.sin(yaw)), Math.abs(Math.cos(yaw)))));
            double axial = smoothUnit(1 - axisAngle / 5);
            double diagonal = smoothUnit((axisAngle - 42) / 3);
            double basePitch = dualClimbAngle(config.dualClimbAngle(), Math.max(Math.abs(Math.sin(yaw)), Math.abs(Math.cos(yaw))));
            boolean gentle = intent.boostMode() == BoostMode.Gentle;
            double axialPitch = gentle ? -86.25 : -89.25;
            double pitch = basePitch + axial * (axialPitch - basePitch) + diagonal * (-89.25 - basePitch);
            double span = (gentle ? 20 : 72.5) * axial + 45 * diagonal;
            bankSide = !bankSide;
            return new Selection(lookAt(pitch, yaw), phase, vertical, false, true, true,
                    lookAt(0, yaw + Math.toRadians(bankSide ? span : -span)), true);
        }
        if (accelerated) {
            boolean descentRequested = vertical < 0;
            if (!descentRequested) {
                if (!Double.isFinite(acceleratedReferenceY)) acceleratedReferenceY = playerY;
                vertical = playerY > acceleratedReferenceY ? -1 : 1;
            } else acceleratedReferenceY = Double.NaN;
            Vec3 motion = lookAt(0, yaw);
            Vec3 box = new Vec3(0, vertical, 0);
            // Banking gives a small additional gain only along a cardinal axis.
            // It is deliberately opt-in through GZBoost, as it moves sideways.
            boolean banked = descentRequested && (Math.abs(motion.x) < 1e-6 || Math.abs(motion.z) < 1e-6);
            if (banked) {
                bankSide = !bankSide;
                box = lookAt(71.85, yaw + (bankSide ? Math.PI / 2 : -Math.PI / 2));
            }
            return new Selection(motion, phase, vertical, false, true, true, box, banked);
        }
        double pitch = vertical > 0 ? config.climbPitch() : vertical < 0
                ? config.descentPitch() : config.cruisePitch();
        if (vertical > 0 && config.smartClimb()) {
            double axis = Math.max(Math.abs(Math.sin(yaw)), Math.abs(Math.cos(yaw)));
            pitch = -Math.toDegrees(Math.acos(Math.min(1, 1 / (2 * axis))));
        }
        boolean dualActive = false;
        Vec3 boxLook = null;
        if (dualView && vertical != 0) {
            double axisX = Math.abs(Math.sin(yaw)), axisZ = Math.abs(Math.cos(yaw));
            double aMax = Math.max(axisX, axisZ);
            if (vertical > 0) {
                pitch = dualClimbAngle(config.dualClimbAngle(), aMax);
                boxLook = lookAt(0, yaw);
                dualActive = true;
            } else {
                double aMin = axisX > 1e-6 && axisZ > 1e-6 ? Math.min(axisX, axisZ) : 1.0;
                double[] pair = solveDivePair(config.dualDiveAngle(), aMin);
                if (pair == null && aMin < aMax) pair = solveDivePair(config.dualDiveAngle(), aMax);
                if (pair != null) {
                    pitch = pair[0];
                    boxLook = lookAt(pair[1], yaw);
                    dualActive = true;
                }
            }
        }
        Vec3 look = lookAt(pitch, yaw);
        return new Selection(look, phase, vertical, false, true, dualActive, boxLook);
    }

    /** Use the pursuit heading for dual-view thrust, only outside precise strike/evade windows. */
    public Selection selectCombat(Config config, Vec3 request, double playerY,
                                  boolean dualView, boolean precision, boolean directional) {
        return selectCombat(config, request, playerY, dualView, precision, directional, BoostMode.Aggressive);
    }

    public Selection selectCombat(Config config, Vec3 request, double playerY,
                                  boolean dualView, boolean precision, boolean directional, BoostMode boostMode) {
        if (config == null || !dualView || precision || directional || !finite(request)
                || request.lengthSqr() < 1e-8) return null;
        // Combat owns its altitude; never introduce a manual cruise height wave.
        Config combat = new Config(config.cruisePitch(), config.climbPitch(), config.descentPitch(),
                config.smartClimb(), config.descentFloor(), false, config.heightWindow(),
                config.speedCap(), config.boxCap(), config.inset(), config.dualClimbAngle(), config.dualDiveAngle());
        double horizontal = Math.hypot(request.x, request.z);
        double vertical = Math.abs(request.y) > Math.max(.1, horizontal * .1) ? Math.signum(request.y) : 0;
        Vec3 heading = new Vec3(request.x, 0, request.z);
        if (vertical > 0 && horizontal > 1e-6) {
            // Axial banking is the proven high-lift profile. Keep it across small
            // bearing changes, but never let it point more than 60 degrees off pursuit.
            Vec3 direction = heading.normalize();
            if (combatClimbAxis.lengthSqr() > 0 && combatClimbAxis.dot(direction) >= .5)
                heading = combatClimbAxis;
            else heading = Math.abs(request.x) >= Math.abs(request.z)
                    ? new Vec3(Math.signum(request.x), 0, 0) : new Vec3(0, 0, Math.signum(request.z));
        }
        Selection selected = select(combat, new Intent(heading, vertical, false, true, boostMode), 0, playerY, true);
        combatClimbAxis = vertical > 0 ? heading.normalize() : Vec3.ZERO;
        return selected.active() && selected.dualActive() ? selected : null;
    }

    private static double smoothUnit(double value) {
        double x = Math.max(0, Math.min(1, value));
        return x * x * (3 - 2 * x);
    }

    private static Vec3 lookAt(double pitchDeg, double yawRad) {
        double radians = Math.toRadians(pitchDeg);
        return new Vec3(-Math.sin(yawRad) * Math.cos(radians), -Math.sin(radians),
                Math.cos(yawRad) * Math.cos(radians));
    }

    /** Reference-client dual climb angle: fixed override, or auto-solved from the axis budget. */
    private static double dualClimbAngle(double fixedDeg, double aMax) {
        double alpha = fixedDeg > 0 ? -fixedDeg
                : -(Math.toDegrees(Math.acos(Math.min(1, 1 / aMax - 1))) - 0.75);
        alpha = Math.max(-89.5, Math.min(-30.0, alpha));
        if (aMax * (Math.cos(Math.toRadians(alpha)) + 1) < 1) {
            alpha = -(Math.toDegrees(Math.acos(Math.min(1, 1 / aMax - 1))) - 0.75);
        }
        return alpha;
    }

    /** Dive pair [motion pitch, box pitch]; null when unsolvable. */
    static double[] solveDivePair(double fixedAlphaDeg, double aMin) {
        if (fixedAlphaDeg > 0) {
            double beta = solveDiveBeta(fixedAlphaDeg, aMin);
            return Double.isNaN(beta) ? null : new double[]{fixedAlphaDeg, beta};
        }
        for (double alpha = 1.0; alpha <= 30.0; alpha += 0.5) {
            double beta = solveDiveBeta(alpha, aMin);
            if (!Double.isNaN(beta)) return new double[]{alpha, beta};
        }
        return null;
    }

    private static double solveDiveBeta(double alphaDeg, double aMin) {
        double ar = Math.toRadians(alphaDeg);
        double betaLo = Math.toDegrees(Math.asin(Math.min(1, 1 - Math.sin(ar)))) + 1;
        double cosBound = 1 / aMin - Math.cos(ar);
        if (cosBound > 1 || cosBound < -1) return Double.NaN;
        double betaHi = Math.toDegrees(Math.acos(cosBound)) - 1;
        return betaLo > betaHi ? Double.NaN : (betaLo + betaHi) / 2;
    }

    /**
     * Use actualLook from the SAME vanilla glide invocation as simulatedVelocity.
     * A competing motion rotation invalidates the selected intent for this tick.
     */
    public Result apply(Config config, Selection selection, Vec3 previousVelocity,
                        Vec3 simulatedVelocity, Vec3 actualLook, Vec3 previousLook) {
        if (config == null || selection == null || !selection.active()
                || !finite(selection.look()) || !validMotion(previousVelocity) || !validMotion(simulatedVelocity)
                || !finite(actualLook) || !finite(previousLook)) return rejected();
        Vec3 look = unit(actualLook);
        if (look == null || unit(selection.look()) == null
                || look.dot(unit(selection.look())) < 0.99999) return rejected();
        // The box's second view is the previous real look, or the dual-view box
        // look the caller reported with a duplicate rotation packet.
        Vec3 secondView = selection.dualActive() && finite(selection.boxLook())
                ? selection.boxLook() : previousLook;
        Envelope box = envelope(config, previousVelocity, simulatedVelocity, look, secondView);
        if (box == null) return rejected();
        double x = selection.pureVertical() ? clamp(0, box.min.x, box.max.x)
                : edge(selection.look().x, box.min.x, box.max.x);
        double z = selection.pureVertical() ? clamp(0, box.min.z, box.max.z)
                : edge(selection.look().z, box.min.z, box.max.z);
        if (selection.banked()) {
            if (Math.abs(selection.boxLook().x) > 1e-6 && (Math.abs(selection.look().x) < 1e-6 || selection.look().x * selection.boxLook().x <= 0)) x = edge(selection.boxLook().x, box.min.x, box.max.x);
            if (Math.abs(selection.boxLook().z) > 1e-6 && (Math.abs(selection.look().z) < 1e-6 || selection.look().z * selection.boxLook().z <= 0)) z = edge(selection.boxLook().z, box.min.z, box.max.z);
        }
        if (selection.maximumLift()) {
            x = farEdge(box.min.x, box.max.x);
            z = farEdge(box.min.z, box.max.z);
        }
        double y = selection.vertical() > 0 ? box.max.y
                : selection.vertical() < 0 && selection.pureVertical() ? box.min.y
                : clamp(selection.vertical() < 0 ? config.descentFloor() : 0, box.min.y, box.max.y);
        Vec3 wish = new Vec3(x, y, z);
        Vec3 nearest = clamp(Vec3.ZERO, box);
        double cap = config.speedCap();
        if (norm(nearest) > cap) return new Result(nearest, false, true,
                controlledDescentUnmet(config, selection, nearest), selection.pureVertical() && horizontal(nearest));
        boolean limited = norm(wish) > cap;
        Vec3 result = wish;
        if (limited) {
            // Euclidean projection: x(lambda)=clamp(wish/(1+lambda), box).
            // Bisection in scale [0,1] has fixed cost and never leaves the box.
            double low = 0, high = 1;
            result = nearest;
            for (int i = 0; i < 60; i++) {
                double scale = (low + high) * .5;
                Vec3 candidate = clamp(wish.scale(scale), box);
                if (norm(candidate) <= cap) {
                    low = scale;
                    result = candidate;
                } else high = scale;
            }
        }
        boolean feasible = box.contains(result, 1e-9) && norm(result) <= cap + 1e-9;
        return new Result(result, feasible, limited, controlledDescentUnmet(config, selection, result),
                selection.pureVertical() && horizontal(result));
    }

    /** Combat supplies the heading; only the available thrust is shared with cruise.
     * Precision and defensive directions must not be replaced by box-corner steering. */
    public static Result applyCombat(Config config, Vec3 request, Vec3 previous, Vec3 simulated,
                                     Vec3 look, Vec3 lastLook, boolean precision, boolean directional) {
        if (config == null || !finite(request)) return new Result(Vec3.ZERO, false, false, false, false);
        Envelope box = envelope(config, previous, simulated, look, lastLook);
        if (box == null) return new Result(Vec3.ZERO, false, false, false, false);
        Vec3 wish = ElytraPhysics.projectToEnvelope(request, box.min, box.max,
                !precision && !directional, 0, precision);
        Vec3 nearest = clamp(Vec3.ZERO, box);
        if (norm(nearest) > config.speedCap()) return new Result(nearest, false, true, false, false);
        boolean limited = norm(wish) > config.speedCap();
        Vec3 result = wish;
        if (limited) {
            double low = 0, high = 1;
            result = nearest;
            for (int i = 0; i < 60; i++) {
                double scale = (low + high) * .5;
                Vec3 candidate = clamp(wish.scale(scale), box);
                if (norm(candidate) <= config.speedCap()) { low = scale; result = candidate; }
                else high = scale;
            }
        }
        return new Result(result, box.contains(result, 1e-9) && norm(result) <= config.speedCap() + 1e-9,
                limited, false, false);
    }

    private static boolean controlledDescentUnmet(Config config, Selection selection, Vec3 result) {
        return selection.vertical() < 0 && !selection.pureVertical() && result.y < config.descentFloor() - 1e-9;
    }

    /**
     * The two look samples are the current motion view and the second reported
     * view: normally the previous tick's real look; with dual view, the box
     * look the caller sent as a duplicate rotation packet this tick.
     */
    public static Envelope envelope(Config config, Vec3 previous, Vec3 simulated, Vec3 look, Vec3 lastLook) {
        if (config == null || !validMotion(previous) || !validMotion(simulated)) return null;
        Vec3 current = unit(look), last = unit(lastLook);
        if (current == null || last == null) return null;
        double[] min = new double[3], max = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            double lo = Math.max(-config.boxCap(), config.boxCap()
                    * (Math.min(-.05, component(current, axis)) + Math.min(-.05, component(last, axis))));
            double hi = Math.min(config.boxCap(), config.boxCap()
                    * (Math.max(.05, component(current, axis)) + Math.max(.05, component(last, axis))));
            // Shrink is constrained by EACH actual axis width, including tiny boxes.
            double inset = Math.min(config.inset(), (hi - lo) * .5);
            min[axis] = component(simulated, axis) + Math.min(0, lo + inset - component(previous, axis));
            max[axis] = component(simulated, axis) + Math.max(0, hi - inset - component(previous, axis));
        }
        Vec3 lower = new Vec3(min[0], min[1], min[2]), upper = new Vec3(max[0], max[1], max[2]);
        return finite(lower) && finite(upper) ? new Envelope(lower, upper) : null;
    }

    private Result rejected() {
        reset();
        return new Result(Vec3.ZERO, false, false, false, false);
    }

    private static boolean horizontal(Vec3 v) { return Math.hypot(v.x, v.z) > 1e-9; }
    private static double edge(double direction, double lo, double hi) {
        return direction > 1e-6 ? hi : direction < -1e-6 ? lo : clamp(0, lo, hi);
    }
    private static double farEdge(double lo, double hi) { return Math.abs(lo) > Math.abs(hi) ? lo : hi; }
    private static Vec3 clamp(Vec3 v, Envelope box) {
        return new Vec3(clamp(v.x, box.min.x, box.max.x), clamp(v.y, box.min.y, box.max.y),
                clamp(v.z, box.min.z, box.max.z));
    }
    private static double clamp(double value, double lo, double hi) { return Math.max(lo, Math.min(hi, value)); }
    private static double bounded(double value, double lo, double hi, double fallback) {
        return Double.isFinite(value) ? clamp(value, lo, hi) : fallback;
    }
    private static double component(Vec3 v, int axis) { return axis == 0 ? v.x : axis == 1 ? v.y : v.z; }
    private static double norm(Vec3 v) { return Math.hypot(Math.hypot(v.x, v.y), v.z); }
    private static boolean finite(Vec3 v) {
        return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }
    // Reject corrupt motion well outside the controller's domain before subtracting
    // almost equal extreme doubles. Coordinates are not passed through this gate.
    private static boolean validMotion(Vec3 v) {
        return finite(v) && Math.max(Math.max(Math.abs(v.x), Math.abs(v.y)), Math.abs(v.z)) <= 1e6;
    }
    private static Vec3 unit(Vec3 v) {
        if (!finite(v)) return null;
        double scale = Math.max(Math.max(Math.abs(v.x), Math.abs(v.y)), Math.abs(v.z));
        if (scale == 0) return null;
        Vec3 scaled = new Vec3(v.x / scale, v.y / scale, v.z / scale);
        return scaled.scale(1 / norm(scaled));
    }
}
