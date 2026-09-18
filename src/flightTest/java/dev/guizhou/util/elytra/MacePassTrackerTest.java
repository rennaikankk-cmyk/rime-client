package dev.guizhou.util.elytra;

public final class MacePassTrackerTest {
    private static int checks;

    public static void main(String[] args) {
        MacePassTracker pass = new MacePassTracker();
        for (int tick = 0; tick < 60; tick++) {
            check(!pass.shouldPullUp(tick, 32.0 - tick * 0.4, 3.0, 10.0, 0.0, false),
                    "A distant but steadily closing pass must continue");
        }
        pass.reset();
        check(!pass.shouldPullUp(0, 6, 3, 5, 0, false), "Approaching the attack band");
        check(!pass.shouldPullUp(1, 2.8, 3, 2, 0, true), "First real smash opportunity takes priority");
        check(pass.shouldPullUp(2, 4.2, 3, 2, 0, false), "A grounded target dodging outside reach starts the next climb");

        pass.reset();
        for (int tick = 0; tick < 12; tick++) {
            check(!pass.shouldPullUp(tick, 2.2, 3, 0.2, 0, true),
                    "Low altitude alone cannot discard a still-valid real smash");
        }
        check(pass.shouldPullUp(12, 3.4, 3, 0.2, 0, false), "An exhausted below-target pass must recover");

        pass.reset();
        for (int tick = 0; tick < 20; tick++) {
            double distance = 3.2 + (tick % 3) * 0.04;
            check(!pass.shouldPullUp(tick, distance, 3, 2, 0, false),
                    "Sub-block distance noise is not evidence of a flyby");
        }
        check(pass.shouldPullUp(31, 3.2, 3, 2, 0, false), "Staying over a grounded target cannot trap FOLLOW indefinitely");

        pass.reset();
        for (int tick = 0; tick < 80; tick++) {
            check(!pass.shouldPullUp(tick, 80.0 - tick * 0.4, 3, 12, 0, false),
                    "Progress may continue until the bounded pass deadline");
        }
        check(pass.shouldPullUp(80, 48, 3, 12, 0, false), "A continuously unreachable target still has a bounded pass");
        check(!pass.shouldPullUp(81, 2.0, 3, 1, 0, true), "An immediate valid smash wins over the timeout");
        pass.reset();
        check(!pass.shouldPullUp(500, 8, 3, 10, 0, false), "A new target starts with fresh pass memory");
        check(!pass.shouldPullUp(1, 8, 3, 10, 0, false), "World tick reset cannot inherit an old timeout");
        check(pass.shouldPullUp(2, Double.NaN, 3, 10, 0, false), "Invalid movement facts cannot sustain a pass");
        System.out.println("MacePassTrackerTest passed: " + checks + " checks");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
