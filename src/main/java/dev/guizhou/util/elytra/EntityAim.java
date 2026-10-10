package dev.guizhou.util.elytra;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Main-thread aim math for players, slimes, and other mobs.
 * A slime hop must not be extrapolated through the landing bounce.
 */
public final class EntityAim {
    public enum Kind { PLAYER, SLIME, MOB }

    /** Copied onto the planner input. The worker never sees the entity. */
    public enum Motion { STRAIGHT, AIR_STRAFE, GROUND_STRAFE }

    /**
     * USE_EXISTING keeps the single straight lead already computed on the main thread.
     * BOX is the current box with no velocity lead.
     */
    public enum Pattern { USE_EXISTING, AIR_STRAFE, GROUND_STRAFE, GROUND_LEAD, BOX }

    public record Choice(Pattern pattern, Motion motion, Vec3 offset) {
        public Choice {
            if (pattern == null) pattern = Pattern.USE_EXISTING;
            if (motion == null) motion = Motion.STRAIGHT;
            offset = finite(offset) ? offset : Vec3.ZERO;
        }
    }

    private static final double GRAVITY = 0.08;

    private EntityAim() {}

    /** Point on the box facing the eye, matching the mace attack clamp. */
    public static Vec3 facingPoint(Vec3 eye, AABB box) {
        if (eye == null || box == null || !finite(eye)) return Vec3.ZERO;
        Vec3 clamped = new Vec3(
                clamp(eye.x, box.minX, box.maxX),
                clamp(eye.y, box.minY, box.maxY),
                clamp(eye.z, box.minZ, box.maxZ));
        Vec3 center = box.getCenter();
        return finite(center) ? clamped.lerp(center, 0.05) : clamped;
    }

    /**
     * Displacement to add to the current box. Slimes stay inside this hop.
     * Unstable mobs contribute nothing so the caller keeps the current box.
     */
    public static Vec3 lead(Kind kind, Vec3 velocity, boolean stable) {
        if (kind == null || kind == Kind.PLAYER || !finite(velocity)) return Vec3.ZERO;
        if (kind == Kind.SLIME) return hopLead(velocity);
        return stable ? velocity.scale(Math.min(1.0, 1.0)) : Vec3.ZERO;
    }

    /** Rising hop only. Downward or near-zero vertical motion is the landing, not the next bounce. */
    public static Vec3 hopLead(Vec3 velocity) {
        if (!finite(velocity) || velocity.y <= 0.02) return Vec3.ZERO;
        double ticksToApex = velocity.y / GRAVITY;
        double ticks = Math.min(0.5, ticksToApex * 0.5);
        Vec3 step = velocity.scale(ticks);
        return step.y > 0.0 ? step : Vec3.ZERO;
    }

    /**
     * Air duels and ground strafes. Slimes are not decided here.
     * {@code straightLead} is returned only for a committed airborne straight chase.
     */
    public static Choice choose(Kind kind, boolean onGround, boolean airborne, boolean armorStand,
                                double selfSpeed, Vec3 velocity, Vec3 previous, boolean twoSamples,
                                Vec3 straightLead) {
        if (kind == Kind.SLIME) return new Choice(Pattern.USE_EXISTING, Motion.STRAIGHT, hopLead(velocity));
        boolean player = kind == Kind.PLAYER;
        if (!twoSamples) return new Choice(Pattern.BOX, onGround ? Motion.GROUND_STRAFE : Motion.AIR_STRAFE, Vec3.ZERO);
        if (!finite(velocity) || !finite(previous)) {
            return new Choice(Pattern.BOX, onGround ? Motion.GROUND_STRAFE : Motion.AIR_STRAFE, Vec3.ZERO);
        }
        if (onGround) {
            if (armorStand && velocity.lengthSqr() < 0.05 * 0.05 && previous.lengthSqr() < 0.05 * 0.05) {
                return new Choice(Pattern.BOX, Motion.GROUND_STRAFE, Vec3.ZERO);
            }
            if (player && speedDisagrees(velocity, previous)) {
                return new Choice(Pattern.BOX, Motion.GROUND_STRAFE, Vec3.ZERO);
            }
            if (swings(velocity, previous) || !player && speedDisagrees(velocity, previous)) {
                double cap = player ? 0.35 : 0.8;
                return new Choice(Pattern.GROUND_STRAFE, Motion.GROUND_STRAFE, cappedAverage(velocity, previous, cap));
            }
            double cap = player ? 0.45 : 1.0;
            return new Choice(Pattern.GROUND_LEAD, Motion.STRAIGHT, shortStep(velocity, cap));
        }
        if (!airborne || !similarSpeed(selfSpeed, velocity.length())) {
            return new Choice(Pattern.USE_EXISTING, Motion.STRAIGHT, finite(straightLead) ? straightLead : Vec3.ZERO);
        }
        if (swings(velocity, previous)) {
            return new Choice(Pattern.AIR_STRAFE, Motion.AIR_STRAFE, cappedAverage(velocity, previous, 0.6));
        }
        return new Choice(Pattern.USE_EXISTING, Motion.STRAIGHT, finite(straightLead) ? straightLead : Vec3.ZERO);
    }

    static boolean similarSpeed(double self, double target) {
        if (!Double.isFinite(self) || !Double.isFinite(target) || self < 0.15 || target < 0.15) return false;
        double ratio = target / self;
        return ratio >= 0.65 && ratio <= 1.5;
    }

    private static boolean speedDisagrees(Vec3 current, Vec3 previous) {
        double currentSpeed = current.length();
        double previousSpeed = previous.length();
        double margin = Math.max(0.15, previousSpeed * 0.45);
        return Math.abs(currentSpeed - previousSpeed) > margin;
    }

    /** Reverse or a sideways swing while the two samples still have speed. */
    private static boolean swings(Vec3 current, Vec3 previous) {
        Vec3 a = new Vec3(current.x, 0.0, current.z);
        Vec3 b = new Vec3(previous.x, 0.0, previous.z);
        if (a.lengthSqr() < 1.0E-4 || b.lengthSqr() < 1.0E-4) return false;
        return a.normalize().dot(b.normalize()) < 0.35;
    }

    private static Vec3 cappedAverage(Vec3 current, Vec3 previous, double cap) {
        return shortStep(current.add(previous).scale(0.5), cap);
    }

    private static Vec3 shortStep(Vec3 step, double cap) {
        if (!finite(step) || step.lengthSqr() < 1.0E-8 || !(cap > 0.0)) return Vec3.ZERO;
        double length = step.length();
        return length > cap ? step.scale(cap / length) : step;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
