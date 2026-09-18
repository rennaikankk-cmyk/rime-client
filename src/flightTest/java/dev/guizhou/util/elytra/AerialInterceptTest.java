package dev.guizhou.util.elytra;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class AerialInterceptTest {
    private static int checks;

    public static void main(String[] args) {
        close(3.0, lead(new Vec3(6, 0, 0), Vec3.ZERO, 2), "Stationary target");
        close(2.0, lead(new Vec3(6, 0, 0), new Vec3(-1, 0, 0), 2), "Head-on meeting");
        close(4.0, lead(new Vec3(6, 0, 0), new Vec3(1, 0, 0), 2), "Bound long pursuit");
        close(0.5, lead(new Vec3(6, 0, 0), new Vec3(3, 0, 0), 2), "Unreachable fleeing target");
        close(1.5, lead(new Vec3(6, 0, 0), new Vec3(-2, 0, 0), 2), "Linear equal-speed case");
        close(Math.sqrt(12), lead(new Vec3(6, 0, 0), new Vec3(0, 1, 0), 2), "Transverse intercept");
        close(1.0, AerialIntercept.leadTicks(new Vec3(20, 0, 0), new Vec3(0, 0, 2), 3, 3, false),
                "Uncertain direction limits prediction");
        close(0.0, lead(new Vec3(Double.NaN, 0, 0), Vec3.ZERO, 2), "Reject invalid position");
        close(0.0, lead(Vec3.ZERO, Vec3.ZERO, -1), "Reject invalid speed");
        close(1.0, AerialIntercept.leadTicks(Vec3.ZERO, Vec3.ZERO, 1, 3, false),
                "Coincident uncertain target respects the horizon");
        AABB target = new AABB(-0.3, 0.0, -0.3, 0.3, 1.8, 0.3);
        check(AerialIntercept.dropHitTick(new Vec3(0, 8, 0), new Vec3(0, -0.6, 0), target,
                Vec3.ZERO, 3, 1.5, 12) > 0, "Aligned descending drop can reach");
        check(AerialIntercept.dropHitTick(new Vec3(0, 8, 0), new Vec3(3, -0.6, 0), target,
                Vec3.ZERO, 3, 1.5, 12) < 0, "Lateral overshoot must not start dive");
        check(AerialIntercept.dropHitTick(new Vec3(0, 8, 0), new Vec3(0, 0.6, 0), target,
                Vec3.ZERO, 3, 1.5, 12) < 0, "Climbing player cannot begin drop");
        check(AerialIntercept.dropHitTick(new Vec3(0, 8, 0), new Vec3(0, -0.6, 0), target,
                new Vec3(3, 0, 0), 3, 1.5, 12) < 0, "Fast departing target cannot be reached");
        check(AerialIntercept.dropHitTick(new Vec3(0, 8, 0), new Vec3(0, -0.6, 0), target,
                Vec3.ZERO, 3, 30, 12) < 0, "Configured fall requirement applies");
        check(AerialIntercept.dropHitTick(new Vec3(0, 8, 0), new Vec3(0, -0.6, 0), target,
                Vec3.ZERO, Double.NaN, 1.5, 12) < 0, "Invalid range cannot start drop");
        check(AerialIntercept.canStartDrop(new Vec3(0, 9.62, 0), new Vec3(0, -0.6, 0), target,
                Vec3.ZERO, 3, 1.5), "Aligned drop tolerates glide-exit delay");
        check(!AerialIntercept.canStartDrop(new Vec3(0, 4.62, 0), new Vec3(0, -3, 0), target,
                Vec3.ZERO, 3, 1.5), "Very brief low-height pass cannot tolerate delayed glide exit");
        Vec3 fastDrop = new Vec3(0, -6, 0), rising = new Vec3(0, 3, 0);
        check(AerialIntercept.canStartDrop(new Vec3(0, 30, 0), fastDrop, target, rising, 3, 4),
                "A fast head-on pass has an earlier feasible glide-exit window");
        check(!AerialIntercept.canStartDrop(new Vec3(0, 14, 0), fastDrop, target, rising, 3, 4),
                "Waiting for fourteen blocks misses that same fast pass");
        check(AerialIntercept.dropApproachRange(fastDrop, rising, 3) >= 30,
                "Approach gate must admit the earlier proven drop window");
        close(14, AerialIntercept.dropApproachRange(new Vec3(0, -0.6, 0), Vec3.ZERO, 3),
                "Slow drop retains the existing approach range");
        close(64, AerialIntercept.dropApproachRange(new Vec3(0, -20, 0), new Vec3(0, 20, 0), 3),
                "Approach range remains bounded");
        for (int i = 1; i <= 1000; i++) {
            double t = lead(new Vec3(i * 0.03, i % 3, 1), new Vec3((i % 7) * 0.1, 0, 0), 1.5);
            check(Double.isFinite(t) && t >= 0.0 && t <= 4.0, "Prediction stays finite and bounded");
        }
        System.out.println("AerialInterceptTest passed: " + checks + " checks");
    }

    private static double lead(Vec3 offset, Vec3 velocity, double speed) {
        return AerialIntercept.leadTicks(offset, velocity, speed, 0, true);
    }

    private static void close(double expected, double actual, String message) {
        check(Math.abs(expected - actual) < 1.0E-7, message + ": " + actual);
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
