package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;

public final class SpearApproachTest {
    private static int checks;
    public static void main(String[] args) {
        Vec3 fast = new Vec3(6, 0, 0), offset = new Vec3(6, 0, 0);
        Vec3 slow = step(fast, offset, Vec3.ZERO, 6);
        check(slow.x >= 0.65 && slow.x < fast.x, "Stationary close target leaves a charged pass");
        check(6 - slow.x > 2.35, "One stationary step must leave room outside the inner range");
        check(step(fast, offset, new Vec3(5, 0, 0), 6).equals(fast), "Do not brake away a fleeing target's lead");
        check(step(fast, offset, new Vec3(-2, 0, 0), 6).x < slow.x, "Incoming target requires less self closure");
        check(step(fast, offset, new Vec3(0, 3, 0), 6).equals(slow), "Transverse speed is not radial closure");
        check(step(fast, offset, Vec3.ZERO, 30).equals(fast), "Cruise is unchanged");
        check(step(fast.scale(-1), offset, Vec3.ZERO, 4).equals(fast.scale(-1)), "Escape remains owned by defense");
        Vec3 lateral = new Vec3(0, 0, 6);
        check(step(lateral, offset, Vec3.ZERO, 4).equals(lateral), "Do not brake a sidestep");
        check(step(fast, offset, Vec3.ZERO, 1).equals(fast), "Inner dead zone belongs to escape");
        check(step(fast, offset, new Vec3(Double.NaN, 0, 0), 4).equals(fast), "Invalid prediction is not a braking command");
        check(step(fast, offset, new Vec3(21, 0, 0), 4).equals(fast), "Teleport velocity is rejected");
        check(step(fast, offset, Vec3.ZERO, Double.NaN).equals(fast), "Invalid distance does not steer");
        for (int i = 0; i < 360; i += 15) {
            double a = Math.toRadians(i);
            Vec3 axis = new Vec3(Math.cos(a), 0, Math.sin(a));
            Vec3 result = step(axis.scale(6), axis.scale(6), Vec3.ZERO, 6);
            check(Math.abs(result.length() - slow.length()) < 1e-9, "Scene rotation preserves speed");
            check(result.normalize().distanceTo(axis) < 1e-9, "Direction is preserved");
        }
        var window = new SpearApproach.CommitWindow(); Object first = new Object(), second = new Object();
        check(window.admit(10, first, true), "First charged tick");
        check(window.admit(10, first, true), "Repeated query does not consume a tick");
        check(window.admit(11, first, true), "Second tick");
        check(!window.admit(12, first, true) && !window.admit(40, first, true), "No renewal while continuously eligible");
        check(!window.admit(41, first, false) && window.admit(42, first, true), "New opportunity may reopen");
        check(window.admit(43, second, true) && window.admit(44, second, true), "Different target starts fresh");
        check(window.admit(1, second, true), "Tick rollback resets session");
        window.reset();
        check(window.admit(50, second, true), "Explicit lifecycle reset");
        System.out.println("SpearApproachTest passed: " + checks + " checks");
    }
    private static Vec3 step(Vec3 request, Vec3 offset, Vec3 targetVelocity, double distance) {
        return SpearApproach.limitStep(request, offset, targetVelocity, distance, 2, 4.5);
    }
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
