package dev.guizhou.util.elytra;

import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class FlightRecoveryPlanTest {
    private static final Vec3 FORWARD = new Vec3(0.0, 0.0, 1.0);
    private static final Vec3 VELOCITY = new Vec3(0.0, -0.6, 1.0);
    private static final AABB BODY = new AABB(-0.36, -0.02, -0.36, 0.36, 0.62, 0.36);
    private static final AABB BOUNDS = new AABB(-12.0, -12.0, -12.0, 12.0, 12.0, 12.0);
    private static int checks;

    public static void main(String[] args) {
        FlightCollisionSnapshot open = new FlightCollisionSnapshot(Vec3.ZERO, BODY, BOUNDS, List.of());
        FlightRecoveryPlan plan = new FlightRecoveryPlan(7, 100, Vec3.ZERO, VELOCITY, FORWARD, 0.08, false, open);
        check(plan.calculate().orElseThrow().y > 0.5, "Open snapshot provides a pull-up candidate");
        check(new FlightRecoveryPlan(7, 100, Vec3.ZERO, VELOCITY, FORWARD, 0.08, true, open)
                .calculate().isPresent(), "A bounded local rocket prediction still provides a candidate");
        FlightCollisionSnapshot blocked = new FlightCollisionSnapshot(Vec3.ZERO, BODY, BOUNDS,
                List.of(new AABB(-1.0, -1.0, -1.0, 1.0, 1.0, 1.0)));
        check(new FlightRecoveryPlan(7, 100, Vec3.ZERO, VELOCITY, FORWARD, 0.08, false, blocked)
                .calculate().isEmpty(), "Blocked snapshots never provide a heading");
        check(new FlightRecoveryPlan(7, 100, new Vec3(20.0, 0.0, 0.0), VELOCITY, FORWARD, 0.08, false, open)
                .calculate().isEmpty(), "Snapshot bounds cannot be treated as clear unknown terrain");
        check(plan.matches(7, 101, Vec3.ZERO, VELOCITY, FORWARD), "Fresh unchanged context can reuse its candidate");
        check(plan.matches(7, 104, new Vec3(0.0, 0.0, 3.0), VELOCITY, FORWARD),
                "A nearby candidate remains eligible through the four-tick capture interval");
        check(!plan.matches(8, 101, Vec3.ZERO, VELOCITY, FORWARD), "World or phase generation changes reject the old plan");
        check(!plan.matches(7, 105, Vec3.ZERO, VELOCITY, FORWARD), "Results older than four ticks are rejected");
        check(!plan.matches(7, 99, Vec3.ZERO, VELOCITY, FORWARD), "A rewound session tick rejects results");
        check(!plan.matches(7, 101, new Vec3(4.1, 0.0, 0.0), VELOCITY, FORWARD),
                "Moving out of the result's local origin rejects it");
        check(!plan.matches(7, 101, Vec3.ZERO, VELOCITY.scale(3.0), FORWARD), "Large momentum changes reject the old plan");
        check(!plan.matches(7, 101, Vec3.ZERO, VELOCITY, FORWARD.scale(-1.0)), "Changing the intended direction rejects it");
        check(!plan.matches(7, 101, Vec3.ZERO, VELOCITY, Vec3.ZERO), "An undefined heading cannot reuse a plan");
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            Vec3 bad = new Vec3(invalid, 0.0, 0.0);
            check(!plan.matches(7, 101, bad, VELOCITY, FORWARD), "Invalid position rejects results");
            check(!plan.matches(7, 101, Vec3.ZERO, bad, FORWARD), "Invalid velocity rejects results");
            check(!plan.matches(7, 101, Vec3.ZERO, VELOCITY, bad), "Invalid preferred heading rejects results");
            check(new FlightRecoveryPlan(7, 100, Vec3.ZERO, bad, FORWARD, 0.08, false, open)
                    .calculate().isEmpty(), "Invalid worker input produces no candidate");
        }
        System.out.println("FlightRecoveryPlanTest passed: " + checks + " checks");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
