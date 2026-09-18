package dev.guizhou.util.elytra;

import java.util.Random;
import net.minecraft.world.phys.Vec3;

/** Deterministic regression checks; no test framework or running client required. */
public final class ElytraPhysicsTest {
    private static int assertions;

    public static void main(String[] args) {
        yawRoundTrip();
        glideAndPullup();
        overspeedFromRest();
        modernVerticalClimb();
        projectionCases();
        preciseIntercept();
        cornerAndVerticalCaps();
        invalidInputs();
        randomizedBounds();
        System.out.println("ElytraPhysicsTest passed: " + assertions + " assertions");
    }

    private static void yawRoundTrip() {
        near(-1.0, ElytraPhysics.pitchYawToRotation(0.0F, 90.0F).x, 1.0E-4, "positive yaw faces west");
        for (float yaw : new float[]{-179.0F, -90.0F, -37.0F, 0.0F, 64.0F, 90.0F, 179.0F}) {
            for (float pitch : new float[]{-80.0F, -45.0F, 0.0F, 30.0F, 80.0F}) {
                Vec3 look = ElytraPhysics.pitchYawToRotation(pitch, yaw);
                near(yaw, ElytraPhysics.rotationToYaw(look), 0.02, "yaw round-trip");
                near(pitch, ElytraPhysics.rotationToPitch(look), 0.02, "pitch round-trip");
            }
        }
        Vec3 right = ElytraPhysics.movementInputToVelocity(new Vec3(-1, 0, 0), 1.0F, 0.0F);
        near(-1, right.x, 1.0E-6, "vanilla right input faces west at yaw zero");
    }

    private static void glideAndPullup() {
        Vec3 glide = ElytraPhysics.simulateGlide(Vec3.ZERO, new Vec3(0, 0, 1), 0.08);
        near(-0.01764, glide.y, 1.0E-8, "level glide gravity");
        near(0.001782, glide.z, 1.0E-8, "level glide lift");
        Vec3 vertical = new Vec3(0, 2, 0);
        vectorNear(vertical, ElytraPhysics.bestPullupSpeed(vertical), "pure pullup has no invented heading");
        vectorNear(Vec3.ZERO, ElytraPhysics.bestPullupSpeed(Vec3.ZERO), "stationary pullup stays stationary");
    }

    private static void overspeedFromRest() {
        Vec3 look = new Vec3(0, 0, 1);
        Vec3 simulated = ElytraPhysics.simulateGlide(Vec3.ZERO, look, 0.08);
        for (boolean corner : new boolean[]{false, true}) {
            Vec3 actual = ElytraPhysics.clampToGrimEnvelope(new Vec3(0, 0, 6), Vec3.ZERO,
                    simulated, look, look, 1.0, corner, 8.0, 0.3);
            near(1.001782, actual.z, 1.0E-8, "Speed 6 must not bypass envelope from rest");
            near(0, actual.x, 1.0E-8, "straight request keeps X zero");
            finite(actual, "rest projection");
        }
    }

    private static void projectionCases() {
        Vec3 min = new Vec3(-2, -3, -4);
        Vec3 max = new Vec3(2, 3, 4);
        vectorNear(new Vec3(2, 2, 2), ElytraPhysics.projectToEnvelope(new Vec3(1, 1, 1), min, max, false, 0),
                "ray expands to nearest box face");
        vectorNear(new Vec3(-2, -2, -2), ElytraPhysics.projectToEnvelope(new Vec3(-6, -6, -6), min, max, false, 0),
                "negative ray shrinks to box face");
        vectorNear(new Vec3(1, 0, 0), ElytraPhysics.projectToEnvelope(new Vec3(-3, 0, 0),
                new Vec3(1, 0, 0), new Vec3(2, 0, 0), false, 0), "opposite-only bounds use projection");
        vectorNear(new Vec3(0, 1, 2), ElytraPhysics.projectToEnvelope(new Vec3(0, 0, 3),
                new Vec3(-1, 1, 1), new Vec3(1, 2, 2), false, 0), "zero ray axis outside box uses projection");
        vectorNear(Vec3.ZERO, ElytraPhysics.projectToEnvelope(new Vec3(1, 1, 1),
                Vec3.ZERO, Vec3.ZERO, true, 0), "collapsed bounds remain zero");
        Vec3 tiny = ElytraPhysics.projectToEnvelope(new Vec3(1, 1, 1),
                new Vec3(-1.0E-12, -1.0E-12, -1.0E-12), new Vec3(1.0E-12, 1.0E-12, 1.0E-12), true, 0);
        within(tiny.x, -1.0E-12, 1.0E-12, "tiny X bounds");
        within(tiny.y, -1.0E-12, 1.0E-12, "tiny Y bounds");
        within(tiny.z, -1.0E-12, 1.0E-12, "tiny Z bounds");
    }

    private static void modernVerticalClimb() {
        Vec3 look = new Vec3(0, 1, 0);
        Vec3 simulated = ElytraPhysics.simulateGlide(Vec3.ZERO, look, 0.08);
        Vec3 applied = ElytraPhysics.clampToGrimEnvelope(new Vec3(0, 6, 0), Vec3.ZERO,
                simulated, look, look, 1.0, true, 8.0, 0.0);
        near(0.9216, applied.y, 1.0E-8, "disabled legacy expansion uses only actual tick headroom");
    }

    private static void preciseIntercept() {
        Vec3 request = new Vec3(0.4, -0.6, 0.02);
        Vec3 min = new Vec3(-2, -3, -4), max = new Vec3(2, 3, 4);
        vectorNear(request, ElytraPhysics.projectToEnvelope(request, min, max, true, 8, true),
                "terminal intent must retain speed and heading instead of filling all box corners");
        vectorNear(new Vec3(1, 0, 0), ElytraPhysics.projectToEnvelope(new Vec3(0.2, 0, 0),
                new Vec3(1, -1, -1), new Vec3(2, 1, 1), true, 8, true),
                "precision cannot brake below the momentum envelope");
        vectorNear(new Vec3(2, 0, 0), ElytraPhysics.projectToEnvelope(new Vec3(8, 0, 0),
                min, max, true, 8, true), "precision still clips excessive requested speed");
        FlightVelocity intent = new FlightVelocity(request, 2);
        intent.requestPrecision();
        intent.set(request.scale(0.5));
        if (!intent.isPrecise()) throw new AssertionError("Route rewrites must not erase terminal precision");
    }

    private static void cornerAndVerticalCaps() {
        Vec3 min = new Vec3(-20, -20, -20);
        Vec3 max = new Vec3(20, 20, 20);
        near(8, ElytraPhysics.cornerFill(new Vec3(0, 12, 0), new Vec3(0, 1, 0), min, max, 8).y,
                1.0E-8, "cap applies to already oversized positive Y");
        near(-8, ElytraPhysics.cornerFill(new Vec3(0, -12, 0), new Vec3(0, -1, 0), min, max, 8).y,
                1.0E-8, "cap applies to already oversized negative Y");
        Vec3 look = new Vec3(0, 1, 0);
        Vec3 simulated = ElytraPhysics.simulateGlide(Vec3.ZERO, look, 0.08);
        for (boolean corner : new boolean[]{false, true}) {
            Vec3 vertical = ElytraPhysics.clampToGrimEnvelope(new Vec3(0, 6, 0), Vec3.ZERO,
                    simulated, look, look, 1, corner, 2, 0.3);
            near(2, vertical.y, 1.0E-8, "pure vertical cap");
            near(0, vertical.horizontalDistance(), 1.0E-8, "pure vertical has no lateral drift");
        }
        Vec3 small = ElytraPhysics.clampToGrimEnvelope(new Vec3(0, 0.0005, 0), Vec3.ZERO,
                Vec3.ZERO, look, look, 1, false, 0.0001, 0.3);
        near(0.0001, small.y, 1.0E-10, "small-request early return honors cap");
    }

    private static void invalidInputs() {
        Vec3 look = new Vec3(0, 0, 1);
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            Vec3 bad = new Vec3(invalid, 1, 1);
            vectorNear(Vec3.ZERO, ElytraPhysics.simulateGlide(bad, look, 0.08), "invalid glide vector");
            vectorNear(Vec3.ZERO, ElytraPhysics.bestPullupSpeed(bad), "invalid pullup vector");
            vectorNear(Vec3.ZERO, ElytraPhysics.clampToGrimEnvelope(bad, Vec3.ZERO, Vec3.ZERO,
                    look, look, 1, true, 8, 0.3), "invalid request vector");
            vectorNear(Vec3.ZERO, ElytraPhysics.projectToEnvelope(look, bad, look, true, 8), "invalid bounds");
            finite(ElytraPhysics.clampToGrimEnvelope(look, Vec3.ZERO, Vec3.ZERO,
                    look, look, invalid, true, invalid, invalid), "invalid settings");
        }
        Vec3 huge = new Vec3(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);
        finite(ElytraPhysics.simulateGlide(huge, huge, 0.08), "overflow glide");
        finite(ElytraPhysics.clampToGrimEnvelope(huge, huge, huge, huge, huge, 1, true, 8, 0.3),
                "overflow envelope");
    }

    private static void randomizedBounds() {
        Random random = new Random(0xE17A2026L);
        for (int i = 0; i < 5000; i++) {
            Vec3 min = new Vec3(random.nextDouble() * 8 - 4, random.nextDouble() * 8 - 4,
                    random.nextDouble() * 8 - 4);
            Vec3 max = min.add(random.nextDouble() * 4, random.nextDouble() * 4, random.nextDouble() * 4);
            Vec3 desired = new Vec3(i % 7 == 0 ? 0 : random.nextDouble() * 20 - 10,
                    i % 11 == 0 ? 0 : random.nextDouble() * 20 - 10,
                    i % 13 == 0 ? 0 : random.nextDouble() * 20 - 10);
            double cap = i % 3 == 0 ? 0 : 0.1 + random.nextDouble() * 3;
            for (boolean corner : new boolean[]{false, true}) {
                Vec3 value = ElytraPhysics.projectToEnvelope(desired, min, max, corner, cap);
                assertBounds(value, min, max, cap);
            }
            Vec3 filled = ElytraPhysics.cornerFill(desired.scale(10), desired, min, max, cap);
            assertBounds(filled, min, max, cap);
        }
    }

    private static void assertBounds(Vec3 value, Vec3 min, Vec3 max, double cap) {
        finite(value, "random projection");
        within(value.x, min.x, max.x, "random X bounds");
        within(value.y, cap > 0 ? Math.max(-cap, Math.min(cap, min.y)) : min.y,
                cap > 0 ? Math.max(-cap, Math.min(cap, max.y)) : max.y, "random capped Y bounds");
        within(value.z, min.z, max.z, "random Z bounds");
    }

    private static void vectorNear(Vec3 expected, Vec3 actual, String label) {
        near(expected.x, actual.x, 1.0E-8, label + " X");
        near(expected.y, actual.y, 1.0E-8, label + " Y");
        near(expected.z, actual.z, 1.0E-8, label + " Z");
    }

    private static void near(double expected, double actual, double tolerance, String label) {
        assertions++;
        if (!Double.isFinite(actual) || Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void within(double value, double min, double max, String label) {
        assertions++;
        if (!Double.isFinite(value) || value < min - 1.0E-12 || value > max + 1.0E-12) {
            throw new AssertionError(label + ": " + value + " outside [" + min + ", " + max + "]");
        }
    }

    private static void finite(Vec3 value, String label) {
        assertions++;
        if (!Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z)) {
            throw new AssertionError(label + ": nonfinite vector " + value);
        }
    }
}
