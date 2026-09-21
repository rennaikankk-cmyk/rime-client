package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;

public final class TargetMotionTrackerTest {
    private static int assertions;

    public static void main(String[] args) {
        check(!TargetMotionTracker.grounded(true,false,0,true,false), "Unsupported onGround flag is airborne");
        check(TargetMotionTracker.grounded(false,false,0,true,true), "Stationary supported target does not need a movement packet");
        check(!TargetMotionTracker.grounded(true,true,0,true,true), "Glide observation takes priority over ground flags");
        check(TargetMotionTracker.grounded(true,false,0,false,false), "Unknown terrain retains the received ground flag");
        check(!TargetMotionTracker.grounded(false,false,-1,true,true), "A falling target is not already grounded");
        check(!TargetMotionTracker.grounded(true,false,Double.NaN,true,true), "Invalid motion cannot establish grounding");
        measuredVelocityUsesElapsedTicks();
        duplicateTickDoesNotInventMotion();
        landingAndTakeoffResetHistory();
        teleportAndSessionGapResetHistory();
        invalidInputAndPredictionFallback();
        ordinaryGlideAndRocketAcceleration();
        armoredAndUnknownAirUseObservedVelocity();
        turnHistoryAndAbruptReversal();
        modeDebounceAndEquipmentInvalidation();
        discontinuitiesNeverRestoreStaleFallback();
        repeatedEquipmentCyclingKeepsContinuousMotion();
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

    private static void ordinaryGlideAndRocketAcceleration() {
        TargetMotionTracker glide = new TargetMotionTracker();
        for (int tick = 0; tick <= 4; tick++) {
            glide.sample(new Vec3(tick * 0.8, 20 - tick * 0.12, 0), tick, false, true,
                    TargetMotionTracker.Equipment.ELYTRA, 1);
        }
        var motion = glide.snapshot(Vec3.ZERO);
        check(motion.mode() == TargetMotionTracker.Mode.GLIDE, "normal wings use ordinary gliding model");
        vector(new Vec3(0.8, -0.12, 0), motion.velocity(), "ordinary speed is measured rather than armor-flight speed");
        vector(new Vec3(2.4, -0.36, 0), motion.displacement(3), "stable normal glide preserves its measured path");
        check(motion.maxLeadTicks() == 4 && motion.confidence() == 1, "stable gliding earns longer prediction");

        TargetMotionTracker rocket = new TargetMotionTracker();
        double x = 0;
        rocket.sample(new Vec3(x, 20, 0), 0, false, true, TargetMotionTracker.Equipment.ELYTRA, 1);
        for (int tick = 1; tick <= 5; tick++) {
            x += 0.5 + tick * 0.2;
            rocket.sample(new Vec3(x, 20, 0), tick, false, true, TargetMotionTracker.Equipment.ELYTRA, 1);
        }
        var boosted = rocket.snapshot(Vec3.ZERO);
        check(boosted.mode() == TargetMotionTracker.Mode.GLIDE_ACCELERATING,
                "sustained rocket-like acceleration is observed separately from constant glide");
        check(boosted.acceleration().x > 0.15 && boosted.displacement(2).x > boosted.velocity().x * 2,
                "confirmed acceleration leads beyond stale constant-speed extrapolation");
        check(boosted.maxLeadTicks() <= 3, "accelerating gliding keeps a bounded horizon");
    }

    private static void armoredAndUnknownAirUseObservedVelocity() {
        for (var equipment : new TargetMotionTracker.Equipment[]{TargetMotionTracker.Equipment.ARMOR,
                TargetMotionTracker.Equipment.OTHER}) {
            TargetMotionTracker tracker = new TargetMotionTracker();
            for (int tick = 0; tick <= 4; tick++)
                tracker.sample(new Vec3(tick * 0.45, 30, -tick * 0.2), tick, false, false, equipment, 2);
            var motion = tracker.snapshot(new Vec3(8, -4, 3));
            check(motion.mode() == (equipment == TargetMotionTracker.Equipment.ARMOR
                    ? TargetMotionTracker.Mode.ARMORED_AIR : TargetMotionTracker.Mode.AIRBORNE),
                    "chest equipment describes an observation, not a specific flight client");
            vector(new Vec3(0.45, 0, -0.2), motion.velocity(), "plugin/non-gliding flight follows measured motion");
            vector(new Vec3(0.9, 0, -0.4), motion.displacement(2), "non-gliding flight is not forced onto gravity trajectory");
        }
    }

    private static void turnHistoryAndAbruptReversal() {
        TargetMotionTracker tracker = new TargetMotionTracker();
        Vec3 position = new Vec3(0, 40, 0);
        tracker.sample(position, 0, false, true, TargetMotionTracker.Equipment.ELYTRA, 1);
        double angle = 0;
        for (int tick = 1; tick <= 6; tick++) {
            angle = tick * 0.12;
            position = position.add(Math.cos(angle), 0, Math.sin(angle));
            tracker.sample(position, tick, false, true, TargetMotionTracker.Equipment.ELYTRA, 1);
        }
        var arc = tracker.snapshot(Vec3.ZERO);
        check(arc.turnRate() > 0.1 && arc.turnRate() < 0.14, "repeated turn samples establish curvature");
        check(arc.displacement(2).z > arc.velocity().z * 2, "arc prediction follows the observed turning side");
        long oldRevision = tracker.revision();
        position = position.subtract(tracker.velocity());
        tracker.sample(position, 7, false, true, TargetMotionTracker.Equipment.ELYTRA, 1);
        var reversal = tracker.snapshot(Vec3.ZERO);
        check(reversal.revision() != oldRevision && reversal.maxLeadTicks() <= 1,
                "abrupt reversal invalidates outstanding plans and shortens prediction");
        check(reversal.turnRate() == 0 && reversal.acceleration().equals(Vec3.ZERO),
                "old turn/acceleration must not smear across a reversal");
        check(reversal.velocity().dot(arc.velocity()) < 0, "latest reversed heading wins immediately");
    }

    private static void modeDebounceAndEquipmentInvalidation() {
        TargetMotionTracker tracker = new TargetMotionTracker();
        for (int tick = 0; tick <= 4; tick++)
            tracker.sample(new Vec3(tick, 30, 0), tick, false, true, TargetMotionTracker.Equipment.ELYTRA, 1);
        long stable = tracker.revision();
        tracker.sample(new Vec3(5, 30, 0), 5, false, false, TargetMotionTracker.Equipment.ELYTRA, 1);
        check(tracker.mode() == TargetMotionTracker.Mode.GLIDE, "single gliding flag flicker does not relabel mode");
        check(tracker.revision() != stable && tracker.snapshot(Vec3.ZERO).maxLeadTicks() <= 1,
                "raw transition still rejects stale asynchronous work during debounce");
        tracker.sample(new Vec3(6, 30, 0), 6, false, true, TargetMotionTracker.Equipment.ELYTRA, 1);
        check(tracker.mode() == TargetMotionTracker.Mode.GLIDE, "one-tick flag recovery keeps label stable");
        long beforeGear = tracker.revision();
        tracker.sample(new Vec3(7, 30, 0), 7, false, false, TargetMotionTracker.Equipment.ARMOR, 2);
        check(tracker.revision() != beforeGear && tracker.hasTwoVelocities()
                        && tracker.snapshot(Vec3.ZERO).maxLeadTicks() <= 1,
                "equipment switch invalidates plans but retains continuously observed velocity");
        tracker.sample(new Vec3(8, 30, 0), 8, false, false, TargetMotionTracker.Equipment.ARMOR, 2);
        check(tracker.mode() == TargetMotionTracker.Mode.ARMORED_AIR, "consistent new equipment completes debounce");
        beforeGear = tracker.revision();
        tracker.sample(new Vec3(9, 30, 0), 9, false, false, TargetMotionTracker.Equipment.ARMOR, 3);
        check(tracker.revision() != beforeGear, "different chest item invalidates even within the same equipment class");
        tracker.sample(new Vec3(10, 0, 0), 10, true, false, TargetMotionTracker.Equipment.ARMOR, 3);
        check(tracker.mode() == TargetMotionTracker.Mode.GROUND && tracker.snapshot(new Vec3(4, -3, 0)).maxLeadTicks() == 0,
                "landing is immediate and never extrapolates old flight velocity");
    }

    private static void discontinuitiesNeverRestoreStaleFallback() {
        TargetMotionTracker tracker = movingTracker();
        long before = tracker.revision();
        tracker.sample(new Vec3(11, 0, 0), 4, false);
        check(tracker.revision() != before && !tracker.hasTwoVelocities(),
                "short teleport below absolute speed cap is rejected by residual");
        vector(Vec3.ZERO, tracker.predictionVelocity(new Vec3(1, 0, 0)), "teleport cannot restore stale entity fallback");
        check(tracker.snapshot(new Vec3(1, 0, 0)).maxLeadTicks() == 0, "discontinuity suppresses lead until measured again");
        tracker.clear();
        vector(Vec3.ZERO, tracker.predictionVelocity(new Vec3(1, 0, 0)), "packet teleport/session reset suppresses stale fallback");
    }

    private static void repeatedEquipmentCyclingKeepsContinuousMotion() {
        TargetMotionTracker tracker = new TargetMotionTracker();
        for (int tick = 0; tick < 12; tick++) {
            boolean wings = tick % 2 == 0;
            var equipment = wings ? TargetMotionTracker.Equipment.ELYTRA : TargetMotionTracker.Equipment.ARMOR;
            tracker.sample(new Vec3(tick * 1.5, 40, tick * 0.2), tick, false, wings, equipment, wings ? 1 : 2);
            if (tick >= 2) {
                check(tracker.hasTwoVelocities(), "chest/wing cycle must not erase continuous position history");
                var motion = tracker.snapshot(Vec3.ZERO);
                vector(new Vec3(1.5, 0, 0.2), motion.velocity(), "gear does not replace measured speed");
                check(motion.displacement(1).lengthSqr() > 1, "continuous gear cycling retains useful short prediction");
                check(motion.maxLeadTicks() <= 1, "cycling conservatively bounds forecast while modes are unstable");
            }
        }
        long revision = tracker.revision();
        tracker.sample(new Vec3(100, 40, 0), 11, false, true, TargetMotionTracker.Equipment.ELYTRA, 1);
        check(tracker.revision() != revision && tracker.hasTwoVelocities(), "same-tick metadata invalidates work without dropping velocity");
        tracker.sample(new Vec3(18, 40, 2.4), 12, false, true, TargetMotionTracker.Equipment.ELYTRA, 1);
        vector(new Vec3(1.5, 0, 0.2), tracker.velocity(), "same-tick metadata cannot replace the movement baseline");
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
