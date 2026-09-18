package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;

public final class TargetMotionTrackerTest {
    private static int assertions;

    public static void main(String[] args) {
        measuredVelocityUsesElapsedTicks();
        duplicateTickDoesNotInventMotion();
        landingAndTakeoffResetHistory();
        teleportAndSessionGapResetHistory();
        invalidInputAndPredictionFallback();
        System.out.println("TargetMotionTrackerTest passed: " + assertions + " assertions");
    }

    private static void measuredVelocityUsesElapsedTicks() {
        TargetMotionTracker tracker = new TargetMotionTracker();
        tracker.sample(Vec3.ZERO, 100, false);
        tracker.sample(new Vec3(4, 2, -6), 102, false);
        vector(new Vec3(2, 1, -3), tracker.velocity(), "elapsed ticks normalize velocity");
        check(!tracker.hasTwoVelocities(), "one velocity cannot establish direction consistency");
        tracker.sample(new Vec3(10, 5, -15), 105, false);
        check(tracker.hasTwoVelocities(), "three positions establish two velocities");
        vector(new Vec3(2, 1, -3), tracker.previousVelocity(), "previous measured velocity");
        vector(new Vec3(2, 1, -3), tracker.velocity(), "current measured velocity");
        vector(new Vec3(2, 1, -3), tracker.predictionVelocity(new Vec3(99, 99, 99)),
                "measured motion wins over stale entity delta movement");
    }

    private static void duplicateTickDoesNotInventMotion() {
        TargetMotionTracker tracker = new TargetMotionTracker();
        tracker.sample(Vec3.ZERO, 10, false);
        tracker.sample(new Vec3(1, 0, 0), 11, false);
        tracker.sample(new Vec3(50, 0, 0), 11, false);
        vector(new Vec3(1, 0, 0), tracker.velocity(), "duplicate tick is ignored");
        tracker.sample(new Vec3(2, 0, 0), 12, false);
        vector(new Vec3(1, 0, 0), tracker.velocity(), "duplicate tick did not replace baseline");
    }

    private static void landingAndTakeoffResetHistory() {
        TargetMotionTracker tracker = movingTracker();
        tracker.sample(new Vec3(3, 0, 0), 4, true);
        check(!tracker.hasTwoVelocities(), "landing discards flight classification history");
        vector(Vec3.ZERO, tracker.velocity(), "landing starts a fresh baseline");
        tracker.sample(new Vec3(4, 0, 0), 5, true);
        vector(new Vec3(1, 0, 0), tracker.velocity(), "ground motion remains measurable");
        tracker.sample(new Vec3(5, 1, 0), 6, false);
        check(!tracker.hasTwoVelocities(), "takeoff does not reuse grounded velocity");
        vector(Vec3.ZERO, tracker.velocity(), "takeoff starts a fresh baseline");
    }

    private static void teleportAndSessionGapResetHistory() {
        TargetMotionTracker tracker = movingTracker();
        tracker.sample(new Vec3(200, 0, 0), 4, false);
        vector(Vec3.ZERO, tracker.velocity(), "teleport is not extrapolated");
        check(!tracker.hasTwoVelocities(), "teleport resets directional history");
        tracker.sample(new Vec3(201, 0, 0), 5, false);
        vector(new Vec3(1, 0, 0), tracker.velocity(), "tracking resumes from teleported position");
        tracker.sample(new Vec3(203, 0, 0), 20, false);
        vector(Vec3.ZERO, tracker.velocity(), "long sample gap resets history");
        tracker.sample(new Vec3(204, 0, 0), 1, false);
        vector(Vec3.ZERO, tracker.velocity(), "tick counter reset starts a fresh session");
        tracker.clear();
        check(!tracker.hasTwoVelocities(), "explicit clear removes sample count");
        vector(Vec3.ZERO, tracker.previousVelocity(), "explicit clear removes previous velocity");
    }

    private static void invalidInputAndPredictionFallback() {
        TargetMotionTracker tracker = new TargetMotionTracker();
        Vec3 fallback = new Vec3(0.1, 0.2, 0.3);
        vector(fallback, tracker.predictionVelocity(fallback), "initial finite fallback");
        vector(Vec3.ZERO, tracker.predictionVelocity(new Vec3(50, 0, 0)), "implausible fallback rejected");
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            Vec3 bad = new Vec3(invalid, 0, 0);
            tracker.sample(bad, 1, false);
            vector(Vec3.ZERO, tracker.velocity(), "invalid sample clears velocity");
            vector(Vec3.ZERO, tracker.predictionVelocity(bad), "invalid fallback rejected");
        }
        tracker.sample(null, 2, false);
        vector(Vec3.ZERO, tracker.velocity(), "missing sample clears velocity");
        tracker.sample(Vec3.ZERO, Integer.MAX_VALUE, false);
        tracker.sample(new Vec3(1, 0, 0), Integer.MIN_VALUE, false);
        vector(Vec3.ZERO, tracker.velocity(), "integer tick wrap is not a positive interval");
    }

    private static TargetMotionTracker movingTracker() {
        TargetMotionTracker tracker = new TargetMotionTracker();
        tracker.sample(Vec3.ZERO, 1, false);
        tracker.sample(new Vec3(1, 0, 0), 2, false);
        tracker.sample(new Vec3(2, 0, 0), 3, false);
        check(tracker.hasTwoVelocities(), "moving fixture");
        return tracker;
    }

    private static void vector(Vec3 expected, Vec3 actual, String message) {
        check(Double.isFinite(actual.x) && Double.isFinite(actual.y) && Double.isFinite(actual.z)
                && expected.distanceToSqr(actual) < 1.0E-16, message + ": " + actual);
    }

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
