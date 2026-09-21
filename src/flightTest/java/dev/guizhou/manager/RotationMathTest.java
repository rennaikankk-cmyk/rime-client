package dev.guizhou.manager;

public final class RotationMathTest {
    private static int checks;
    public static void main(String[] args) {
        check(RotationMath.matches(359, 10, -1, 10, 0), "equivalent wrapped yaw");
        check(!RotationMath.matches(Float.NaN, 0, 0, 0, 1), "reject NaN");
        check(!RotationMath.finite(0, Float.POSITIVE_INFINITY), "reject infinite pitch");
        for (float start : new float[]{-179, 0, 179, 359, 1081}) {
            for (float end : new float[]{-178, 0, 178, 361, -721}) {
                for (float limit : new float[]{5, 30, 90, 180}) {
                    float sent = start;
                    float previousDistance = Math.abs(RotationMath.difference(end, sent));
                    for (int tick = 0; tick < 50; tick++) {
                        float next = RotationMath.step(sent, end, limit);
                        check(Math.abs(RotationMath.difference(next, sent)) <= limit + 0.001, "yaw limit");
                        float distance = Math.abs(RotationMath.difference(end, next));
                        check(distance <= previousDistance + 0.001, "shortest-path progress");
                        // A cancelled packet leaves the submitted baseline unchanged.
                        if (tick % 7 != 0) { sent = next; previousDistance = distance; }
                    }
                    check(RotationMath.matches(sent, 0, end, 0, 0.001f), "converges despite cancellations");
                }
            }
        }
        System.out.println("RotationMathTest passed: " + checks + " checks");
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
