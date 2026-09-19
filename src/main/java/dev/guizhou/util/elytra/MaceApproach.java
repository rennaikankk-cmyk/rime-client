package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;

/** Replanned each tick. Times are ticks, positions are blocks, speeds are blocks/tick.
 * This is a constant-speed rendezvous estimate, not a promise of server movement. */
public final class MaceApproach {
    private static final double MAX_TIME = 20.0;
    private static final double MAX_LEAD = 32.0;

    private MaceApproach() {}

    public record Plan(Vec3 aim, double height, double ticks, boolean reachable) {}

    /** Change climb pitch without changing the requested travel distance. */
    public static Vec3 pullUpIntent(Vec3 intent, double degrees) {
        if (!finite(intent)) return Vec3.ZERO;
        if (!Double.isFinite(degrees) || degrees <= 0.1 || degrees >= 90.0) return intent;
        double horizontal = intent.horizontalDistance();
        if (horizontal <= 1.0E-6) return intent;
        return new Vec3(intent.x, Math.tan(Math.toRadians(degrees)) * horizontal, intent.z)
                .normalize().scale(intent.length());
    }

    /**
     * Vertical drop-lane lookahead clamp. The pre-dive/during-dive collision
     * scan only needs a clear lane down to the attack plane (target box top);
     * scanning the full velocity lookahead treats the floor under the target
     * as an obstacle and aborts high-speed passes just above the smash window
     * ("falls halfway, pauses, falls again").
     *
     * @param stepY        requested downward scan extent, negative = down
     * @param feetToPlane  feet height above the attack plane
     * @return the scan extent, never scanning past the plane or upward
     */
    public static double dropStepClamp(double stepY, double feetToPlane) {
        double maxDown = Math.max(0.0, feetToPlane);
        if (stepY < -maxDown) return -maxDown;
        return Math.min(stepY, 0.0);
    }

    /** Long extrapolation requires both direction and speed to have settled. */
    public static boolean stable(Vec3 current, Vec3 previous, boolean sampled) {
        if (!sampled || !finite(current) || !finite(previous)) return false;
        double speed = current.length();
        return current.distanceTo(previous) <= Math.max(0.08, speed * 0.12)
                && (speed < 0.05 && previous.length() < 0.05
                || speed > 0.0 && previous.length() > 0.0
                && current.normalize().dot(previous.normalize()) >= 0.985);
    }

    /** Choose the highest affordable apex, including the distance back down.
     * Fleeing targets get a lower pass; an unreachable pass keeps horizontal pursuit. */
    public static Plan climb(Vec3 self, Vec3 target, Vec3 velocity, double speed,
                             double preferredHeight, double minimumFall, double latency, boolean stable) {
        if (!valid(self, target, velocity, speed) || !Double.isFinite(preferredHeight)
                || !Double.isFinite(minimumFall) || !Double.isFinite(latency)) return invalid(self);
        double height = Math.clamp(preferredHeight, 1.0, 64.0);
        double floor = Math.min(height, Math.max(3.0, minimumFall + 1.5));
        double delay = Math.clamp(latency * 0.5, 0.0, 1.5);
        Vec3 observed = target.add(velocity.scale(delay));
        // A stationary target cannot escape during the climb. Applying the moving-target
        // detour budget here strands a slow player beside it with a zero-height heading.
        if (stable && velocity.lengthSqr() < 1.0E-8) {
            Vec3 apex = target.add(0.0, height, 0.0);
            return new Plan(apex, height, (apex.distanceTo(self) + height) / speed, true);
        }
        double horizon = horizon(velocity, stable);
        double direct = interceptTime(observed.subtract(self), velocity, speed, 0.0);
        // Do not buy damage height with an arbitrarily long detour behind a fleeing target.
        double budget = Math.min(horizon, Double.isFinite(direct) ? direct + Math.max(6.0, direct * 0.5) : horizon);
        for (int step = 0; step <= 12; step++) {
            double candidate = height - (height - floor) * step / 12.0;
            Vec3 apex = observed.add(0.0, candidate, 0.0);
            // Meet the moving apex at the end of the climb. During the descent,
            // matching horizontal target motion leaves this vertical speed budget.
            // Leading the apex by the full round trip counts target motion twice
            // and sends a rising-target pursuit above its actual rendezvous.
            double climbTime = interceptTime(apex.subtract(self), velocity, speed, 0.0);
            double verticalBudgetSq = speed * speed - velocity.horizontalDistanceSqr();
            double closingDown = verticalBudgetSq > 0.0 ? Math.sqrt(verticalBudgetSq) + velocity.y : 0.0;
            double dropTime = closingDown > 1.0E-6 ? candidate / closingDown : Double.POSITIVE_INFINITY;
            double time = climbTime + dropTime;
            if (time <= budget) {
                return new Plan(apex.add(velocity.scale(climbTime)), candidate, time, true);
            }
        }
        // Preserve horizontal closing speed. Follow a rising target only as much as necessary;
        // do not command another full-height climb when the round trip cannot be caught.
        Vec3 shortLead = observed.add(velocity.scale(Math.min(2.0, horizon)));
        return new Plan(new Vec3(shortLead.x, Math.max(self.y, shortLead.y), shortLead.z), floor, 0.0, false);
    }

    public static Plan intercept(Vec3 self, Vec3 target, Vec3 velocity, double speed,
                                 double latency, boolean stable) {
        if (!valid(self, target, velocity, speed) || !Double.isFinite(latency)) return invalid(self);
        double delay = Math.clamp(latency * 0.5, 0.0, 1.5);
        Vec3 observed = target.add(velocity.scale(delay));
        double time = interceptTime(observed.subtract(self), velocity, speed, 0.0);
        double horizon = horizon(velocity, stable);
        boolean reachable = time <= horizon;
        double lead = reachable ? time : Math.min(2.0, horizon);
        return new Plan(observed.add(velocity.scale(lead)), 0.0, reachable ? time : 0.0, reachable);
    }

    /** Solve |offset + velocity*t| + extraDistance = speed*t, taking the first future root. */
    static double interceptTime(Vec3 offset, Vec3 velocity, double speed, double extraDistance) {
        if (!finite(offset) || !finite(velocity) || !Double.isFinite(speed) || speed <= 1.0E-6
                || !Double.isFinite(extraDistance) || extraDistance < 0.0) return Double.POSITIVE_INFINITY;
        double a = velocity.lengthSqr() - speed * speed;
        double b = 2.0 * (offset.dot(velocity) + speed * extraDistance);
        double c = offset.lengthSqr() - extraDistance * extraDistance;
        double minimum = extraDistance / speed;
        if (offset.lengthSqr() < 1.0E-12 && extraDistance == 0.0) return 0.0;
        double first, second;
        if (Math.abs(a) < 1.0E-9) {
            if (Math.abs(b) < 1.0E-9) return Double.POSITIVE_INFINITY;
            first = -c / b;
            second = Double.POSITIVE_INFINITY;
        } else {
            double disc = b * b - 4.0 * a * c;
            if (disc < 0.0 || !Double.isFinite(disc)) return Double.POSITIVE_INFINITY;
            double q = -0.5 * (b + Math.copySign(Math.sqrt(disc), b));
            first = q / a;
            second = Math.abs(q) < 1.0E-12 ? Double.POSITIVE_INFINITY : c / q;
        }
        double best = Double.POSITIVE_INFINITY;
        for (double time : new double[]{first, second}) {
            if (Double.isFinite(time) && time >= minimum && time >= 0.0
                    && Math.abs(offset.add(velocity.scale(time)).length() + extraDistance - speed * time)
                    < 1.0E-5 * Math.max(1.0, speed * time)) best = Math.min(best, time);
        }
        return best;
    }

    private static double horizon(Vec3 velocity, boolean stable) {
        return Math.min(stable ? MAX_TIME : 4.0, MAX_LEAD / Math.max(1.0E-6, velocity.length()));
    }

    private static boolean valid(Vec3 self, Vec3 target, Vec3 velocity, double speed) {
        return finite(self) && finite(target) && finite(velocity) && velocity.lengthSqr() <= 400.0
                && Double.isFinite(speed) && speed > 1.0E-6 && speed <= 20.0;
    }

    private static boolean finite(Vec3 v) {
        return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    private static Plan invalid(Vec3 self) {
        return new Plan(finite(self) ? self : Vec3.ZERO, 0.0, 0.0, false);
    }
}
