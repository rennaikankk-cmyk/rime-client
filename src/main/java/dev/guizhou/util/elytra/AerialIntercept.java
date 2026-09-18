package dev.guizhou.util.elytra;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Bounded aiming and conservative, unpowered drop estimates; never changes entity state. */
public final class AerialIntercept {
    private AerialIntercept() {
    }

    public static double leadTicks(Vec3 offset, Vec3 targetVelocity, double travelSpeed,
                                   double latencyTicks, boolean consistent) {
        if (!finite(offset) || !finite(targetVelocity) || !Double.isFinite(travelSpeed)
                || travelSpeed < 0.0 || !Double.isFinite(latencyTicks)) return 0.0;
        double limit = consistent ? 4.0 : 1.0;
        double latency = Math.clamp(latencyTicks, 0.0, 3.0) * 0.5;
        Vec3 relative = offset.add(targetVelocity.scale(latency));
        double speed = Math.max(0.5, travelSpeed);
        double a = targetVelocity.lengthSqr() - speed * speed;
        double b = 2.0 * relative.dot(targetVelocity);
        double c = relative.lengthSqr();
        if (c < 1.0E-9) return Math.min(limit, latency);
        double time = Double.POSITIVE_INFINITY;
        if (Math.abs(a) < 1.0E-9) {
            if (b < -1.0E-9) time = -c / b;
        } else {
            double discriminant = b * b - 4.0 * a * c;
            if (discriminant >= 0.0 && Double.isFinite(discriminant)) {
                double root = Math.sqrt(discriminant);
                double q = -0.5 * (b + Math.copySign(root, b));
                double first = q / a;
                double second = Math.abs(q) < 1.0E-12 ? Double.POSITIVE_INFINITY : c / q;
                if (first >= 0.0) time = first;
                if (second >= 0.0) time = Math.min(time, second);
            }
        }
        // An unreachable target gets a short lookahead rather than a maximum-distance extrapolation.
        if (!Double.isFinite(time)) time = 0.5;
        return Math.min(limit, time + latency);
    }

    public static int dropHitTick(Vec3 eye, Vec3 velocity, AABB target, Vec3 targetVelocity,
                                  double reach, double minimumFall, int maxTicks) {
        if (!finite(eye) || !finite(velocity) || !finite(targetVelocity) || !finite(target)
                || !Double.isFinite(reach) || reach <= 0.0 || !Double.isFinite(minimumFall)
                || minimumFall < 0.0 || maxTicks <= 0 || velocity.y >= 0.0) return -1;
        Vec3 position = eye;
        Vec3 motion = velocity;
        double fallen = 0.0;
        double marginReach = Math.max(0.0, reach - 0.35);
        for (int tick = 1; tick <= Math.min(16, maxTicks); tick++) {
            position = position.add(motion);
            fallen += Math.max(0.0, -motion.y);
            AABB future = target.move(targetVelocity.scale(tick));
            if (fallen > Math.max(1.5, minimumFall)
                    && future.distanceToSqr(position) <= marginReach * marginReach) return tick;
            motion = new Vec3(motion.x * 0.91, (motion.y - 0.08) * 0.98, motion.z * 0.91);
        }
        return -1;
    }

    public static boolean canStartDrop(Vec3 standingEye, Vec3 velocity, AABB target,
                                       Vec3 targetVelocity, double reach, double minimumFall) {
        if (!finite(standingEye) || !finite(velocity) || !finite(target) || !finite(targetVelocity)) return false;
        // Require room for two ticks of glide-exit latency, not just a single exact-tick hit.
        for (int delay = 0; delay <= 2; delay++) {
            if (dropHitTick(standingEye.add(velocity.scale(delay)), velocity,
                    target.move(targetVelocity.scale(delay)), targetVelocity, reach, minimumFall, 10) < 0) return false;
        }
        return true;
    }

    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y)
                && Double.isFinite(vector.z) && vector.lengthSqr() < 1.0E12;
    }

    private static boolean finite(AABB box) {
        return box != null && Double.isFinite(box.minX) && Double.isFinite(box.minY)
                && Double.isFinite(box.minZ) && Double.isFinite(box.maxX)
                && Double.isFinite(box.maxY) && Double.isFinite(box.maxZ);
    }
}
