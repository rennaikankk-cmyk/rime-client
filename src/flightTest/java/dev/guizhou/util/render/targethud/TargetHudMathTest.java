package dev.guizhou.util.render.targethud;

/**
 * Pins the HUD math: MC yaw convention, shortest-path angular easing, radar
 * projection bounds, closure/intercept/energy semantics and easing behaviour.
 */
public final class TargetHudMathTest {
    private static int checks;

    private TargetHudMathTest() {}

    public static void main(String[] args) {
        verifyYawConvention();
        verifyWrapAndAngleEasing();
        verifyRadarProjection();
        verifyClosureAndIntercept();
        verifyEnergy();
        verifyEasing();
        System.out.println("TargetHudMathTest passed: " + checks + " checks");
    }

    private static void verifyYawConvention() {
        check(Math.abs(TargetHudMath.yawTo(0, 10)) < 1e-9, "+Z must be yaw 0 (south)");
        check(Math.abs(TargetHudMath.yawTo(-10, 0) - 90) < 1e-9, "-X must be yaw 90 (west)");
        check(Math.abs(TargetHudMath.yawTo(10, 0) - 270) < 1e-9, "+X must be yaw 270 (east, [0,360))");
        check(Math.abs(TargetHudMath.yawTo(0, -10) - 180) < 1e-9, "-Z must be yaw 180 (north)");
    }

    private static void verifyWrapAndAngleEasing() {
        check(Math.abs(TargetHudMath.wrapDelta(350, 10) - 20) < 1e-9, "wrap must cross 0 the short way");
        check(Math.abs(TargetHudMath.wrapDelta(10, 350) + 20) < 1e-9, "wrap must be signed");
        check(Math.abs(TargetHudMath.wrapDelta(0, 180)) - 180 < 1e-9, "opposite is ±180");
        // easing never wanders the long way: 350 -> 10 must approach through 0
        double v = 350;
        for (int i = 0; i < 200; i++) v = TargetHudMath.easeAngle(v, 10, 16, 80);
        check(Math.abs(TargetHudMath.wrapDelta(v, 10)) < 0.001, "angle easing converges");
        double mid = TargetHudMath.easeAngle(350, 10, 16, 80);
        double delta = TargetHudMath.wrapDelta(350, mid);
        check(delta > 0 && delta < 20, "angle easing steps the short way, got " + delta);
        check(TargetHudMath.normalizeAngle(-10) == 350.0, "normalize keeps [0,360)");
    }

    private static void verifyRadarProjection() {
        double[] ahead = TargetHudMath.radarProject(0, 15, 40, 20);
        check(Math.abs(ahead[0]) < 1e-9 && ahead[1] < 0, "bearing 0 points up");
        double[] right = TargetHudMath.radarProject(90, 15, 40, 20);
        check(right[0] > 0 && Math.abs(right[1]) < 1e-9, "bearing 90 points right (clockwise)");
        double[] edge = TargetHudMath.radarProject(45, 999, 40, 20);
        check(Math.hypot(edge[0], edge[1]) <= 40.0 + 1e-9, "far targets clamp to the rim");
        double[] near = TargetHudMath.radarProject(45, 5, 40, 20);
        double[] mid = TargetHudMath.radarProject(45, 20, 40, 20);
        check(Math.hypot(near[0], near[1]) < Math.hypot(mid[0], mid[1]), "sqrt scale keeps near < far");
        // sqrt compression: 1/4 distance is 1/2 radius
        double[] quarter = TargetHudMath.radarProject(0, 5, 40, 20);
        check(Math.abs(Math.abs(quarter[1]) - 20.0) < 1e-9, "quarter distance = half radius");
    }

    private static void verifyClosureAndIntercept() {
        check(Math.abs(TargetHudMath.closure(12, 10) - 2) < 1e-9, "closure positive when closing");
        check(TargetHudMath.interceptTicks(10, 2, 0.05) == 5.0, "intercept = distance / closure");
        check(TargetHudMath.interceptTicks(10, 0, 0.05) < 0, "no closure = no solution");
        check(TargetHudMath.interceptTicks(10, -1, 0.05) < 0, "escaping = no solution");
    }

    private static void verifyEnergy() {
        check(Math.abs(TargetHudMath.energyAdvantage(3, 1, 0) - 20) < 1e-9, "speed edge counts +10 per b/t");
        check(Math.abs(TargetHudMath.energyAdvantage(2, 2, 10) + 22) < 1e-9, "altitude debt counts -2.2 per block");
        check(TargetHudMath.energyAdvantage(10, 0, -100) == 100.0, "clamped high");
        check(TargetHudMath.energyAdvantage(0, 10, 100) == -100.0, "clamped low");
    }

    private static void verifyEasing() {
        double v = 0;
        for (int i = 0; i < 100; i++) v = TargetHudMath.ease(v, 10, 16, 80);
        check(Math.abs(v - 10) < 0.001, "value easing converges");
        check(TargetHudMath.ease(0, 10, 0, 80) == 0.0, "zero dt = no motion");
        double huge = TargetHudMath.ease(0, 10, 10_000, 80);
        check(huge > 4.0 && huge < 5.0, "dt is clamped to one 64ms step, got " + huge);
        check(TargetHudMath.ramp(0.2f, 0.1f) == 0.3f, "ramp steps");
        check(TargetHudMath.ramp(0.95f, 0.2f) == 1.0f, "ramp clamps at 1");
        check(TargetHudMath.ramp(0.1f, -0.5f) == 0.0f, "ramp clamps at 0");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
