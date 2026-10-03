package dev.guizhou.util.elytra;

import java.util.Optional;
import net.minecraft.world.phys.Vec3;

/** Local terrain prediction; avoiding impact does not reset server fall distance. */
public final class FlightTerrainSafety {
    public static final double FAST_DESCENT = -0.45;
    private static final int MAX_HORIZON = 40;
    private static final double EPSILON = 1.0E-8;

    @FunctionalInterface
    public interface Clearance {
        /** Sweep the whole player body between offsets from its current position. */
        boolean clear(Vec3 from, Vec3 to);
    }

    private FlightTerrainSafety() {
    }

    /**
     * Detect terrain under a fast descending path before actual contact. Without
     * a supplied look, continuing toward the current velocity is a conservative
     * dive estimate. The caller must also test newly requested downward motion.
     */
    public static boolean landingRisk(Vec3 velocity, double gravity, int horizonTicks, Clearance space) {
        if (!valid(velocity, gravity, horizonTicks, space)) return true;
        if (velocity.y >= FAST_DESCENT) return false;
        Vec3 look = velocity.normalize();
        Vec3 position = Vec3.ZERO;
        Vec3 predicted = velocity;
        double margin = 2.0 + Math.min(6.0, -velocity.y * 2.0);
        if (!space.clear(position, position.add(0.0, -margin, 0.0))) return true;
        for (int tick = 0; tick < Math.min(horizonTicks, MAX_HORIZON); tick++) {
            predicted = ElytraPhysics.simulateGlide(predicted, look, gravity);
            Vec3 next = position.add(predicted);
            if (!finite(predicted) || !finite(next)
                    || !space.clear(position, next)
                    || !space.clear(next, next.add(0.0, -margin, 0.0))) return true;
            position = next;
        }
        return false;
    }

    /** Predict each glide tick after steering, rather than extending old momentum in a straight line. */
    public static boolean trajectoryClear(Vec3 velocity, Vec3 look, double gravity,
            int horizonTicks, Clearance space) {
        return trajectoryClear(velocity, look, gravity, horizonTicks, false, space);
    }

    public static boolean trajectoryClear(Vec3 velocity, Vec3 look, double gravity,
            int horizonTicks, boolean rocketBoost, Clearance space) {
        if (!valid(velocity, gravity, horizonTicks, space) || !finite(look)
                || look.lengthSqr() < EPSILON) return false;
        Vec3 direction = look.normalize();
        if (!space.clear(Vec3.ZERO, Vec3.ZERO)) return false;
        // Entity tick order can put thrust on either side of glide; thrust may also expire.
        return sweepTrajectory(velocity, direction, gravity, horizonTicks, false, false, space)
                && (!rocketBoost
                || sweepTrajectory(velocity, direction, gravity, horizonTicks, true, false, space)
                && sweepTrajectory(velocity, direction, gravity, horizonTicks, false, true, space));
    }

    private static boolean sweepTrajectory(Vec3 velocity, Vec3 direction, double gravity,
            int horizonTicks, boolean boostBefore, boolean boostAfter, Clearance space) {
        Vec3 position = Vec3.ZERO;
        Vec3 predicted = velocity;
        for (int tick = 0; tick < Math.min(horizonTicks, MAX_HORIZON); tick++) {
            if (boostBefore) predicted = rocketVelocity(predicted, direction);
            predicted = ElytraPhysics.simulateGlide(predicted, direction, gravity);
            if (boostAfter) predicted = rocketVelocity(predicted, direction);
            Vec3 next = position.add(predicted);
            if (!finite(predicted) || !finite(next) || !space.clear(position, next)) return false;
            position = next;
        }
        return true;
    }

    private static Vec3 rocketVelocity(Vec3 velocity, Vec3 look) {
        // FireworkRocketEntity.tick: v + look*0.1 + (look*1.5-v)*0.5.
        return velocity.scale(0.5).add(look.scale(0.85));
    }

    /** A new rocket has a finite guaranteed burn after its bounded arrival delay.
     * Unlike an already attached rocket, it cannot expire before it starts. */
    public static boolean rocketLaunchClear(Vec3 velocity, Vec3 look, double gravity,
            int horizonTicks, int maxDelayTicks, int burnTicks, Clearance space) {
        if (!valid(velocity, gravity, horizonTicks, space) || !finite(look)
                || look.lengthSqr() < EPSILON || maxDelayTicks < 0 || maxDelayTicks > 10
                || burnTicks < 1 || !space.clear(Vec3.ZERO, Vec3.ZERO)) return false;
        Vec3 direction = look.normalize();
        int horizon = Math.min(horizonTicks, MAX_HORIZON);
        for (int delay = 0; delay <= maxDelayTicks; delay++) {
            for (boolean boostBefore : new boolean[]{false, true}) {
                Vec3 position = Vec3.ZERO;
                Vec3 predicted = velocity;
                for (int tick = 0; tick < horizon; tick++) {
                    boolean burning = tick >= delay && (long) tick - delay < burnTicks;
                    if (burning && boostBefore) predicted = rocketVelocity(predicted, direction);
                    predicted = ElytraPhysics.simulateGlide(predicted, direction, gravity);
                    if (burning && !boostBefore) predicted = rocketVelocity(predicted, direction);
                    Vec3 next = position.add(predicted);
                    if (!finite(predicted) || !finite(next) || !space.clear(position, next)) return false;
                    position = next;
                }
            }
        }
        return true;
    }

    /**
     * Prefer a clear level escape before pulling up. Both
     * initial headroom and the ensuing glide must be clear. Server corrections
     * are outside this prediction.
     */
    public static Optional<Vec3> recoveryLook(Vec3 velocity, Vec3 preferredLook, double gravity,
            int horizonTicks, Clearance space) {
        return recoveryLook(velocity, preferredLook, gravity, horizonTicks, false, space);
    }

    public static Optional<Vec3> recoveryLook(Vec3 velocity, Vec3 preferredLook, double gravity,
            int horizonTicks, boolean rocketBoost, Clearance space) {
        if (!valid(velocity, gravity, horizonTicks, space) || !finite(preferredLook)) return Optional.empty();
        Vec3 horizontal = new Vec3(preferredLook.x, 0.0, preferredLook.z);
        if (horizontal.lengthSqr() < EPSILON) horizontal = new Vec3(velocity.x, 0.0, velocity.z);
        if (horizontal.lengthSqr() < EPSILON) horizontal = new Vec3(0.0, 0.0, 1.0);
        float yaw = ElytraPhysics.rotationToYaw(horizontal);
        for (float pitch : new float[]{0.0F, -20.0F, -35.0F}) {
            for (float turn : new float[]{0.0F, 30.0F, -30.0F, 60.0F, -60.0F, 90.0F, -90.0F, 180.0F}) {
                Vec3 look = ElytraPhysics.pitchYawToRotation(pitch, yaw + turn).normalize();
                if (space.clear(Vec3.ZERO, look.scale(0.75))
                        && trajectoryClear(velocity, look, gravity, horizonTicks, rocketBoost, space)) return Optional.of(look);
            }
        }
        return Optional.empty();
    }

    /** A newly opened wing may need a rocket to make any takeoff path viable. */
    public static Optional<Vec3> takeoffRocketLook(Vec3 velocity, Vec3 preferred, double gravity,
                                                  int arrivalTicks, Clearance space) {
        if (!finite(velocity) || !finite(preferred) || space == null) return Optional.empty();
        Vec3 flat = new Vec3(preferred.x, 0, preferred.z);
        if (flat.lengthSqr() < EPSILON) flat = new Vec3(velocity.x, 0, velocity.z);
        if (flat.lengthSqr() < EPSILON) flat = new Vec3(0,0,1);
        float yaw = ElytraPhysics.rotationToYaw(flat);
        for (float pitch : new float[]{-35,-55,-75}) {
            for (float turn : new float[]{0,30,-30}) {
                Vec3 look = ElytraPhysics.pitchYawToRotation(pitch,yaw+turn);
                if (space.clear(Vec3.ZERO,look.scale(.75))
                        && rocketLaunchClear(velocity,look,gravity,16,arrivalTicks,10,space)) return Optional.of(look);
            }
        }
        return Optional.empty();
    }

    private static boolean valid(Vec3 velocity, double gravity, int horizonTicks, Clearance space) {
        return finite(velocity) && Double.isFinite(gravity) && gravity >= 0.0
                && horizonTicks > 0 && space != null;
    }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y)
                && Double.isFinite(value.z) && Double.isFinite(value.lengthSqr());
    }
}
