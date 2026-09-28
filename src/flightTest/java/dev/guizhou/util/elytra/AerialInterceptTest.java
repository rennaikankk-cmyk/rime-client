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
        Vec3 setup = AerialIntercept.dropSetupVelocity(new Vec3(0, 21.62, 0), target,
                new Vec3(0, 3, 0), 6, 3, 1.5);
        check(setup != null && setup.y < -0.15 && setup.length() <= 6,
                "Rising target gets a descending setup within the flight budget");
        check(AerialIntercept.canStartDrop(new Vec3(0, 21.62, 0), setup, target,
                new Vec3(0, 3, 0), 3, 1.5), "Setup survives all glide-exit delays");
        check(AerialIntercept.canStartDrop(new Vec3(0,21.62,0).add(setup),setup,
                target.move(0,3,0),new Vec3(0,3,0),3,1.5),
                "Setup reserves the powered tick before glide exit starts");
        check(AerialIntercept.dropSetupVelocity(new Vec3(0, 21.62, 0), target,
                new Vec3(12, 0, 0), 2, 3, 1.5) == null, "Cannot invent lateral speed to catch fleeing target");
        check(AerialIntercept.dropSetupVelocity(new Vec3(0, 21.62, 0), target,
                Vec3.ZERO, Double.NaN, 3, 1.5) == null, "Reject invalid setup budget");
        AABB flying = new AABB(-.3,0,-.3,.3,.6,.3);
        double height = AerialIntercept.minimumSetupHeight(flying,new Vec3(0,3,0),6,3,1.5,1.62);
        check(height > 3 && height <= 64, "Ascending flight needs more than the bare smash-charge floor");
        check(AerialIntercept.dropSetupVelocity(new Vec3(0,height+1.62,0),flying,new Vec3(0,3,0),6,3,1.5)!=null,
                "Computed height admits a real descending setup");
        var climb = MaceApproach.climb(Vec3.ZERO,new Vec3(0,10,0),new Vec3(0,3,0),6,20,1.5,0,false,height);
        check(climb.height() >= height && climb.aim().y > 10+height,
                "Short prediction horizon cannot flatten a catchable rising-target climb");
        java.util.function.UnaryOperator<Vec3> limited = request -> new Vec3(
                Math.clamp(request.x,-1.7,1.7), Math.max(request.y,-1.7), Math.clamp(request.z,-1.7,1.7));
        int feasible = 0;
        Vec3 moving = new Vec3(.2,1,.15);
        for (int h = 4; h <= 40; h++) {
            Vec3 eye = new Vec3(0,h,0);
            Vec3 requested = AerialIntercept.dropSetupVelocity(eye,target,moving,6,3,1.5,limited);
            if (requested == null) continue;
            feasible++;
            Vec3 applied = limited.apply(requested);
            check(AerialIntercept.canStartDrop(eye,applied,target,moving,3,1.5)
                            && AerialIntercept.canStartDrop(eye.add(applied),applied,target.move(moving),moving,3,1.5),
                    "A constrained request is accepted only when its actual movement leaves a delayed hit window");
        }
        check(feasible > 0,"The constrained solver still finds usable attack setups");
        check(AerialIntercept.dropSetupVelocity(new Vec3(0,20,0),target,moving,6,3,1.5,
                request -> new Vec3(0,1,0)) == null,"A controller still climbing cannot supply a fictitious descending setup");
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
