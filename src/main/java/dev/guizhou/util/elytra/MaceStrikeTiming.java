package dev.guizhou.util.elytra;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A one-tick charge estimate for an already ready natural fall; never authorizes a hit. */
public final class MaceStrikeTiming {
    private MaceStrikeTiming() {}

    /** One extra charge tick is allowed only after a consecutive, stable observation.
     * Never renew the budget on a second callback, a missed tick or a turn reversal. */
    public static boolean mayReserve(int reserved, long tick, long previous, boolean stable) {
        return reserved == 0 || reserved == 1 && stable && tick > previous && tick - previous == 1;
    }

    public static boolean reserveOneTick(Vec3 eye, Vec3 motion, AABB target, Vec3 targetMotion,
                                          double fall, double reach) {
        if (!finite(eye) || !motion(motion) || !motion(targetMotion) || target == null
                || !Double.isFinite(fall) || fall <= 1.5 || fall >= 4.5
                || motion.y >= -.15 || !Double.isFinite(reach) || reach <= .35 || reach > 16
                || target.distanceToSqr(eye) > reach * reach) return false;
        // Reserve room for both the current momentum and the next gravity/drag step.
        // An escaping target or an exhausted window must be struck immediately.
        AABB future = target.move(targetMotion);
        Vec3 next = new Vec3(motion.x * .91, (motion.y - .08) * .98, motion.z * .91);
        double margin = (reach - .35) * (reach - .35);
        return future.distanceToSqr(eye.add(motion)) <= margin
                && future.distanceToSqr(eye.add(next)) <= margin;
    }

    private static boolean motion(Vec3 value) { return finite(value) && value.lengthSqr() <= 32 * 32; }
    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y)
                && Double.isFinite(value.z) && Double.isFinite(value.lengthSqr());
    }
}
