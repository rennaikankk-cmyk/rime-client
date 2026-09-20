package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;

/** Near-pass speed request. The flight envelope and terrain guard still decide the applied motion. */
public final class SpearApproach {
    private SpearApproach() {}

    /**
     * Leave room for another charged query before entering the weapon's inner dead zone.
     * Account for target motion along our approach, so a fleeing target does not cause
     * the same braking as a stationary target. Never reverse or increase the request.
     * Returning the input means this sample cannot justify a shorter pass.
     */
    public static Vec3 limitStep(Vec3 requested, Vec3 toTarget, Vec3 targetVelocity,
                                 double boxDistance, double minRange, double maxRange) {
        if (!finite(requested) || !finite(toTarget) || !finite(targetVelocity)
                || !Double.isFinite(boxDistance) || !Double.isFinite(minRange)
                || !Double.isFinite(maxRange) || minRange < 0 || maxRange <= minRange
                || boxDistance < minRange + 0.35 || boxDistance > maxRange + 3.0
                || targetVelocity.lengthSqr() > 400.0) return requested;
        double speed = requested.length();
        if (speed < 0.65 || toTarget.lengthSqr() < 1.0E-9) return requested;
        Vec3 axis = toTarget.normalize();
        double aligned = requested.normalize().dot(axis);
        // Charge-room, escape and lateral threat avoidance own their vectors.
        if (aligned < 0.8) return requested;
        double targetAdvance = targetVelocity.dot(axis);
        double room = Math.max(0.0, boxDistance - minRange - 0.35);
        double limit = Math.max(0.65, (room * 0.65 + targetAdvance) / aligned);
        return limit < speed ? requested.scale(limit / speed) : requested;
    }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x)
                && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    /** At most two ticks per continuous opportunity, never renewed by policy re-entry. */
    public static final class CommitWindow {
        private Object target;
        private long started = Long.MIN_VALUE;
        private long previous = Long.MIN_VALUE;

        public boolean admit(long tick, Object identity, boolean eligible) {
            if (!eligible || identity == null) { reset(); return false; }
            if (target != identity || tick < previous || started == Long.MIN_VALUE) {
                target = identity;
                started = tick;
            }
            previous = tick;
            return tick >= started && tick - started < 2;
        }

        public void reset() { target = null; started = previous = Long.MIN_VALUE; }
    }
}
