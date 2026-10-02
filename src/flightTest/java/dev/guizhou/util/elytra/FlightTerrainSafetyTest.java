package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;

public final class FlightTerrainSafetyTest {
    private static final double GRAVITY = 0.08;
    private static final Vec3 FORWARD = new Vec3(0.0, 0.0, 1.0);
    private static final FlightTerrainSafety.Clearance OPEN = (from, to) -> true;
    private static int checks;

    public static void main(String[] args) {
        landingPrediction();
        movingTerrain();
        trajectories();
        rocketTrajectories();
        rocketLaunchRecovery();
        recoveryHeadroom();
        invalidInputs();
        boundedHorizon();
        System.out.println("FlightTerrainSafetyTest passed: " + checks + " checks");
    }

    private static void landingPrediction() {
        check(risk(new Vec3(0.0, -0.8, 1.0), floor(-3.0)), "Fast descent close to ground needs recovery");
        check(!risk(new Vec3(0.0, -0.8, 1.0), floor(-100.0)), "High descent over distant ground is not landing");
        check(risk(new Vec3(0.0, -2.0, 0.2), floor(-18.0)), "Low horizontal speed needs early descent recovery");
        check(!risk(new Vec3(0.0, -0.45, 1.0), floor(-0.2)), "Exact threshold does not classify a fast descent");
        check(risk(new Vec3(0.0, -0.4501, 1.0), floor(-0.2)), "Just beyond threshold enables the ground check");
        check(!risk(new Vec3(0.0, -0.2, 1.0), floor(-0.2)), "Gentle descent is not proof of a dangerous landing");
        check(!risk(new Vec3(0.0, 1.0, 1.0), floor(-0.2)), "Rising motion does not trigger descent handling");
    }

    private static void movingTerrain() {
        FlightTerrainSafety.Clearance platform = obstacle(-4.0, -10.0, 3.0, 4.0, -1.5, 12.0);
        check(platform.clear(Vec3.ZERO, new Vec3(0.0, -10.0, 0.0)), "There is no floor directly below the player");
        check(risk(new Vec3(0.0, -0.6, 1.0), platform), "Prediction sees a platform along horizontal travel");
        check(!risk(new Vec3(0.0, -0.6, -1.0), platform), "A platform behind the player does not block descent");
        FlightTerrainSafety.Clearance slope = (from, to) -> aboveSlope(from) && aboveSlope(to);
        check(risk(new Vec3(0.0, -0.6, 1.2), slope), "Rising terrain is considered along the projected path");
    }

    private static void trajectories() {
        Vec3 velocity = new Vec3(0.0, -0.1, 1.0);
        check(FlightTerrainSafety.trajectoryClear(velocity, FORWARD, GRAVITY, 12, OPEN), "Open glide is clear");
        FlightTerrainSafety.Clearance wall = obstacle(-4.0, -10.0, 5.0, 4.0, 10.0, 6.0);
        check(FlightTerrainSafety.trajectoryClear(velocity, FORWARD, GRAVITY, 1, wall), "Distant wall is beyond first tick");
        check(!FlightTerrainSafety.trajectoryClear(velocity, FORWARD, GRAVITY, 12, wall), "Multiple ticks detect distant wall");
        FlightTerrainSafety.Clearance narrowWall = obstacle(-0.2, -10.0, 4.0, 0.2, 10.0, 5.0);
        check(!narrowWall.clear(Vec3.ZERO, velocity.scale(8.0)), "Straight old-momentum extrapolation meets wall");
        check(FlightTerrainSafety.trajectoryClear(velocity, new Vec3(1.0, 0.0, 0.0), GRAVITY, 12, narrowWall),
                "A gradual turn can clear a wall that old-momentum extrapolation would reject");
        check(!FlightTerrainSafety.trajectoryClear(new Vec3(0.0, 0.4, 1.0), FORWARD, GRAVITY, 4,
                obstacle(-10.0, 0.9, -10.0, 10.0, 2.0, 10.0)), "Whole-body sweep detects low ceiling");
    }

    private static void recoveryHeadroom() {
        Vec3 velocity = new Vec3(0.0, -0.6, 1.0);
        var openRecovery = FlightTerrainSafety.recoveryLook(velocity, FORWARD, GRAVITY, 12, OPEN);
        check(openRecovery.isPresent() && Math.abs(openRecovery.get().y) < 1.0E-6,
                "A clear level escape must not repeatedly climb over a grounded combat target");
        var forcedClimb = FlightTerrainSafety.recoveryLook(velocity, FORWARD, GRAVITY, 12, floor(-3.0));
        check(forcedClimb.isPresent() && forcedClimb.get().y > 0.0,
                "When level travel would hit the floor recovery still pulls up");
        FlightTerrainSafety.Clearance ceiling = obstacle(-100.0, 0.8, -100.0, 100.0, 2.0, 100.0);
        var underCeiling = FlightTerrainSafety.recoveryLook(velocity, FORWARD, GRAVITY, 5, ceiling);
        check(underCeiling.isPresent(), "A level escape remains available below a low ceiling");
        check(Math.abs(underCeiling.orElseThrow().y) < 1.0E-6, "Low headroom excludes upward recovery requests");
        check(FlightTerrainSafety.recoveryLook(velocity, FORWARD, GRAVITY, 12, (from, to) -> false).isEmpty(),
                "No clear recovery is reported when all trajectories are obstructed");
        check(FlightTerrainSafety.recoveryLook(Vec3.ZERO, Vec3.ZERO, GRAVITY, 4, OPEN).isPresent(),
                "Stationary recovery obtains a finite fallback heading");
    }

    private static void rocketTrajectories() {
        Vec3 slow = new Vec3(0.0, -0.02, 0.1);
        FlightTerrainSafety.Clearance wall = obstacle(-4.0, -10.0, 3.0, 4.0, 10.0, 4.0);
        check(FlightTerrainSafety.trajectoryClear(slow, FORWARD, GRAVITY, 4, false, wall),
                "Without thrust the wall is beyond the horizon");
        check(!FlightTerrainSafety.trajectoryClear(slow, FORWARD, GRAVITY, 4, true, wall),
                "Rocket acceleration reaches a wall missed by glide prediction");
        check(FlightTerrainSafety.trajectoryClear(slow, FORWARD, GRAVITY, 4, true,
                obstacle(-4.0, -10.0, 20.0, 4.0, 10.0, 21.0)), "Distant terrain leaves a safe boosted path");
        check(FlightTerrainSafety.trajectoryClear(slow, FORWARD, GRAVITY, 12, true, OPEN),
                "Open boosted glide is clear");
        Vec3 rising = new Vec3(0.0, 0.1, 0.1);
        Vec3 upward = new Vec3(0.0, 1.0, 1.0);
        check(!FlightTerrainSafety.trajectoryClear(rising, upward, GRAVITY, 1, true,
                obstacle(-4.0, 1.24, -4.0, 4.0, 2.0, 4.0)),
                "Thrust before glide catches the higher first-tick ceiling impact");
        check(!FlightTerrainSafety.trajectoryClear(rising, upward, GRAVITY, 1, true,
                obstacle(-4.0, -10.0, 0.94, 4.0, 10.0, 2.0)),
                "Thrust after glide catches the farther first-tick wall impact");
        check(!FlightTerrainSafety.trajectoryClear(new Vec3(0.0, -0.8, 0.2), FORWARD, GRAVITY, 12,
                true, floor(-2.0)), "Rocket prediction still checks descent if thrust expires");
        check(FlightTerrainSafety.recoveryLook(slow, FORWARD, GRAVITY, 4, true, OPEN).isPresent(),
                "Boosted recovery is available with clear headroom");
        check(FlightTerrainSafety.recoveryLook(slow, FORWARD, GRAVITY, 4, true,
                (from, to) -> false).isEmpty(), "Boost cannot create a recovery through blocked terrain");
        check(!FlightTerrainSafety.trajectoryClear(slow, Vec3.ZERO, GRAVITY, 4, true, OPEN),
                "Boosted prediction rejects an undefined heading");
        FlightTerrainSafety.Clearance ceiling = obstacle(-100.0, 1.2, -100.0, 100.0, 3.0, 100.0);
        Vec3 pullUp = ElytraPhysics.pitchYawToRotation(-35.0F, 0.0F);
        check(FlightTerrainSafety.trajectoryClear(slow, pullUp, GRAVITY, 4, false, ceiling),
                "Unboosted pull-up fits below this ceiling");
        check(!FlightTerrainSafety.trajectoryClear(slow, pullUp, GRAVITY, 4, true, ceiling),
                "The same boosted pull-up would hit the ceiling");
        check(Math.abs(FlightTerrainSafety.recoveryLook(slow, FORWARD, GRAVITY, 4, true, ceiling)
                .orElseThrow().y) < 1.0E-6, "Boosted recovery rejects pull-ups that would hit the ceiling");
    }

    private static void invalidInputs() {
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            Vec3 invalid = new Vec3(bad, -1.0, 0.0);
            check(risk(invalid, OPEN), "Invalid movement is never declared a safe landing");
            check(!FlightTerrainSafety.trajectoryClear(invalid, FORWARD, GRAVITY, 12, OPEN), "Invalid velocity cannot clear path");
            check(!FlightTerrainSafety.trajectoryClear(FORWARD, invalid, GRAVITY, 12, OPEN), "Invalid look cannot clear path");
            check(FlightTerrainSafety.landingRisk(FORWARD, bad, 12, OPEN), "Invalid gravity fails closed");
            check(FlightTerrainSafety.recoveryLook(FORWARD, invalid, GRAVITY, 12, OPEN).isEmpty(), "Invalid recovery look fails closed");
        }
        check(risk(null, OPEN), "Missing movement fails closed");
        check(!FlightTerrainSafety.trajectoryClear(FORWARD, Vec3.ZERO, GRAVITY, 12, OPEN), "Zero look has no defined path");
        check(FlightTerrainSafety.landingRisk(FORWARD, GRAVITY, 0, OPEN), "Zero horizon does not claim safety");
        check(!FlightTerrainSafety.trajectoryClear(FORWARD, FORWARD, -1.0, 12, OPEN), "Negative gravity is invalid");
        check(FlightTerrainSafety.landingRisk(FORWARD, GRAVITY, 12, null), "Missing collision world fails closed");
        check(risk(new Vec3(Double.MAX_VALUE, -1.0, Double.MAX_VALUE), OPEN), "Overflow magnitude fails closed");
    }

    private static void rocketLaunchRecovery() {
        Vec3 slow = new Vec3(0.0, -0.02, 0.1);
        Vec3 up = new Vec3(0.0, Math.sin(Math.toRadians(35)), Math.cos(Math.toRadians(35)));
        check(!FlightTerrainSafety.trajectoryClear(slow, up, GRAVITY, 16, true, floor(-1)),
                "Baseline reproduces low-altitude launch refusal when unboosted glide reaches the floor");
        check(FlightTerrainSafety.rocketLaunchClear(slow, up, GRAVITY, 16, 2, 10, floor(-1)),
                "A new rocket can recover low-speed flight when its delayed, finite burn clears the floor");
        var takeoff = FlightTerrainSafety.takeoffRocketLook(slow, FORWARD, GRAVITY, 2, floor(-1));
        check(takeoff.isPresent() && takeoff.get().y > 0, "takeoff can select a clear powered pull-up when coasting cannot escape");
        check(!FlightTerrainSafety.takeoffRocketLook(slow, FORWARD, GRAVITY, 2,
                obstacle(-100,0.8,-100,100,2,100)).isPresent(), "powered takeoff still respects a low ceiling");
        check(!FlightTerrainSafety.takeoffRocketLook(new Vec3(0,-1,.1), FORWARD, GRAVITY, 2, floor(-1)).isPresent(),
                "takeoff refuses a collision before the rocket can arrive");
        check(!FlightTerrainSafety.rocketLaunchClear(new Vec3(0,-1,0.1), up, GRAVITY, 16, 2, 10, floor(-1)),
                "Do not assume thrust arrives before a delayed launch would hit the floor");
        check(!FlightTerrainSafety.rocketLaunchClear(slow, up, GRAVITY, 16, 2, 10,
                obstacle(-100,0.8,-100,100,2,100)), "Recovery cannot launch into a low ceiling");
        check(!FlightTerrainSafety.rocketLaunchClear(slow, FORWARD, GRAVITY, 16, 2, 10,
                obstacle(-4,-10,3,4,10,4)), "Recovery cannot launch through a wall");
        check(!FlightTerrainSafety.rocketLaunchClear(slow, FORWARD, GRAVITY, 40, 0, 1, floor(-1)),
                "Launch prediction must also check the coast after a short burn ends");
        check(!FlightTerrainSafety.rocketLaunchClear(slow, up, GRAVITY, 16, -1, 10, OPEN)
                && !FlightTerrainSafety.rocketLaunchClear(slow, up, GRAVITY, 16, 11, 10, OPEN)
                && !FlightTerrainSafety.rocketLaunchClear(slow, up, GRAVITY, 16, 2, 0, OPEN),
                "Invalid arrival or burn bounds fail closed");
    }

    private static void boundedHorizon() {
        int[] calls = {0};
        FlightTerrainSafety.Clearance counted = (from, to) -> {
            calls[0]++;
            return true;
        };
        check(!FlightTerrainSafety.landingRisk(new Vec3(0.0, -1.0, 1.0), GRAVITY, Integer.MAX_VALUE, counted),
                "Oversized landing horizon is bounded");
        check(calls[0] <= 81, "Landing prediction has bounded collision queries");
        calls[0] = 0;
        check(FlightTerrainSafety.trajectoryClear(FORWARD, FORWARD, GRAVITY, Integer.MAX_VALUE, counted),
                "Oversized trajectory horizon is bounded");
        check(calls[0] <= 41, "Trajectory prediction has bounded collision queries");
        calls[0] = 0;
        check(FlightTerrainSafety.trajectoryClear(FORWARD, FORWARD, GRAVITY, Integer.MAX_VALUE, true, counted),
                "Oversized boosted trajectory horizon is bounded");
        check(calls[0] <= 121, "Both rocket orders and unboosted glide have bounded collision queries");
    }

    private static boolean risk(Vec3 velocity, FlightTerrainSafety.Clearance clearance) {
        return FlightTerrainSafety.landingRisk(velocity, GRAVITY, 12, clearance);
    }

    private static FlightTerrainSafety.Clearance floor(double height) {
        return (from, to) -> from.y > height && to.y > height;
    }

    private static boolean aboveSlope(Vec3 feet) {
        return feet.y > -8.0 + (feet.z + 0.3) * 0.5;
    }

    private static FlightTerrainSafety.Clearance obstacle(double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        // Expand the obstacle by a 0.6-wide, 0.6-high gliding body before sweeping its feet.
        double[] low = {minX - 0.3, minY - 0.6, minZ - 0.3};
        double[] high = {maxX + 0.3, maxY, maxZ + 0.3};
        return (from, to) -> {
            double[] start = {from.x, from.y, from.z};
            double[] end = {to.x, to.y, to.z};
            double enter = 0.0;
            double exit = 1.0;
            for (int axis = 0; axis < 3; axis++) {
                double delta = end[axis] - start[axis];
                if (Math.abs(delta) < 1.0E-10) {
                    if (start[axis] < low[axis] || start[axis] > high[axis]) return true;
                } else {
                    double first = (low[axis] - start[axis]) / delta;
                    double second = (high[axis] - start[axis]) / delta;
                    enter = Math.max(enter, Math.min(first, second));
                    exit = Math.min(exit, Math.max(first, second));
                    if (enter > exit) return true;
                }
            }
            return false;
        };
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
