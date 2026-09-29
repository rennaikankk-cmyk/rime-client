package dev.guizhou.util.hud;

/** Headless checks of the warning hysteresis used by the chunk lag notice. */
public final class SignalHysteresisTest {
    private static int checks;

    public static void main(String[] args) {
        arming();
        clearing();
        flapping();
        try {
            new SignalHysteresis(0, 5);
            check(false, "zero threshold must be rejected");
        } catch (IllegalArgumentException expected) {
            check(true, "zero threshold must be rejected");
        }
        System.out.println("SignalHysteresisTest passed: " + checks + " checks");
    }

    private static void arming() {
        SignalHysteresis hysteresis = new SignalHysteresis(3, 2);
        check(!hysteresis.update(true), "one sample does not arm");
        check(!hysteresis.update(true), "two samples do not arm");
        check(hysteresis.update(true), "third sample arms");
        check(hysteresis.on(), "armed state holds");
    }

    private static void clearing() {
        SignalHysteresis hysteresis = new SignalHysteresis(2, 4);
        hysteresis.update(true);
        hysteresis.update(true);
        check(hysteresis.on(), "armed");
        hysteresis.update(false);
        hysteresis.update(false);
        hysteresis.update(false);
        check(hysteresis.on(), "clearing waits out the off threshold");
        check(!hysteresis.update(false), "fourth clean sample clears");
        check(!hysteresis.update(false), "stays cleared");
    }

    private static void flapping() {
        SignalHysteresis hysteresis = new SignalHysteresis(3, 3);
        for (int i = 0; i < 20; i++) {
            // alternating every other sample must never arm the warning
            check(!hysteresis.update(i % 2 == 0), "flapping boundary never arms: sample " + i);
        }
        hysteresis.reset();
        check(!hysteresis.on(), "reset clears everything");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
