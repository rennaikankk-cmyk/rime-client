package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;

/**
 * Single-look manual/AutoFly cruise. Select a look before vanilla glide, then
 * apply its envelope to that completed glide calculation. No packets or entities.
 * The local envelope is a heuristic, not evidence of server acceptance.
 */
public final class CoupledCruise {
    public record Config(double cruisePitch, double climbPitch, double descentPitch,
                         boolean smartClimb, double descentFloor, boolean porpoise,
                         double heightWindow, double speedCap, double boxCap, double inset) {
        public Config {
            cruisePitch = bounded(cruisePitch, -89.5, 89.5, 15);
            climbPitch = bounded(climbPitch, -89.5, 0, -45);
            descentPitch = bounded(descentPitch, 0, 89.5, 30);
            descentFloor = bounded(descentFloor, -20, 0, -1.7);
            heightWindow = bounded(heightWindow, 1, 256, 60);
            speedCap = bounded(speedCap, 0.05, 15, 15);
            boxCap = bounded(boxCap, 0.01, 5, 1.7);
            inset = bounded(inset, 0, 0.1, 0.002);
        }

        public static Config defaults() {
            return new Config(15, -45, 30, true, -1.7, false, 60, 15, 1.7, .002);
        }
    }

    public enum Phase { LEVEL, DESCEND, CLIMB }

    /** Vertical means manual jump/sneak; horizontal is the world-space WASD wish. */
    public record Intent(Vec3 horizontal, double vertical, boolean autoFly) {}

    public record Selection(Vec3 look, Phase phase, int vertical, boolean pureVertical, boolean active) {
        private static Selection idle() { return new Selection(Vec3.ZERO, Phase.LEVEL, 0, false, false); }
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

    private double referenceY = Double.NaN;
    private Phase phase = Phase.LEVEL;
    private Config lastConfig;

    public void reset() {
        referenceY = Double.NaN;
        phase = Phase.LEVEL;
        lastConfig = null;
    }

    public Phase phase() { return phase; }

    /** headingYaw is in degrees; NaN means the caller has no heading. */
    public Selection select(Config config, Intent intent, double headingYaw, double playerY) {
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
        // Preserve pure vertical explicitly; cos(89.5 degrees) is not zero.
        if (pureVertical) return new Selection(new Vec3(0, vertical, 0), phase, vertical, true, true);
        double yaw = horizontal ? Math.atan2(-intent.horizontal().x, intent.horizontal().z)
                : Math.toRadians(headingYaw % 360);
        double pitch = vertical > 0 ? config.climbPitch() : vertical < 0
                ? config.descentPitch() : config.cruisePitch();
        if (vertical > 0 && config.smartClimb()) {
            double axis = Math.max(Math.abs(Math.sin(yaw)), Math.abs(Math.cos(yaw)));
            pitch = -Math.toDegrees(Math.acos(Math.min(1, 1 / (2 * axis))));
        }
        double radians = Math.toRadians(pitch);
        Vec3 look = new Vec3(-Math.sin(yaw) * Math.cos(radians), -Math.sin(radians),
                Math.cos(yaw) * Math.cos(radians));
        return new Selection(look, phase, vertical, false, true);
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
        Envelope box = envelope(config, previousVelocity, simulatedVelocity, look, previousLook);
        if (box == null) return rejected();
        double x = selection.pureVertical() ? clamp(0, box.min.x, box.max.x)
                : edge(selection.look().x, box.min.x, box.max.x);
        double z = selection.pureVertical() ? clamp(0, box.min.z, box.max.z)
                : edge(selection.look().z, box.min.z, box.max.z);
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

    private static boolean controlledDescentUnmet(Config config, Selection selection, Vec3 result) {
        return selection.vertical() < 0 && !selection.pureVertical() && result.y < config.descentFloor() - 1e-9;
    }

    /** The two look samples are consecutive single-look ticks, never duplicate rotation packets. */
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
