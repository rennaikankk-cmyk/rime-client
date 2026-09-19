package dev.guizhou.util.elytra;

/**
 * Pins the drop-lane lookahead clamp: the collision scan before/during a mace
 * dive only needs a clear lane down to the attack plane (target box top),
 * never the ground below it. Full-velocity lookahead treats the floor under
 * the target as an obstacle and aborts high-speed passes just above the smash
 * window — the "stops halfway, then falls again" stutter.
 */
public final class MaceDropStepClampTest {
    private static int checks;

    private MaceDropStepClampTest() {}

    public static void main(String[] args) {
        // High-speed pass with a deep lane: feet 5 above the plane, step -4.6.
        double clamped = MaceApproach.dropStepClamp(-4.6, 5.0);
        check(Math.abs(clamped + 4.6) < 1e-12, "5 blocks of lane stays fully scanned, got " + clamped);

        // Feet 4 above the plane, step -4.6: scan must stop at the plane.
        clamped = MaceApproach.dropStepClamp(-4.6, 4.0);
        check(clamped >= -4.0 && clamped < -3.9, "lane clamps to the attack plane, got " + clamped);

        // Right at the plane: nothing left to scan.
        clamped = MaceApproach.dropStepClamp(-4.6, 0.2);
        check(clamped <= 0.0, "at the plane the scan collapses, got " + clamped);

        // Slow pass is never clamped.
        clamped = MaceApproach.dropStepClamp(-0.6, 3.0);
        check(Math.abs(clamped + 0.6) < 1e-12, "slow steps pass through untouched, got " + clamped);

        // Already below the plane (edge): non-positive scan only.
        clamped = MaceApproach.dropStepClamp(-2.0, -1.0);
        check(clamped <= 0.0, "below the plane never scans further, got " + clamped);

        // Upward drift must not create an upward scan.
        clamped = MaceApproach.dropStepClamp(0.3, 5.0);
        check(clamped <= 0.0, "upward drift never scans up, got " + clamped);

        System.out.println("MaceDropStepClampTest passed: " + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
