package dev.guizhou.util.combat;

public final class AttackCooldownTest {
    public static void main(String[] args) {
        AttackCooldown timer = new AttackCooldown();
        timer.advance(10);
        timer.start(13, 20, 1);
        for (int tick = 11; tick < 23; tick++) {
            timer.advance(tick);
            timer.advance(tick); // AutoSword and KillAura share a service and may both run.
            check(timer.active(), "two controllers must not advance cooldown twice");
        }
        timer.advance(23);
        check(!timer.active(), "20 TPS sword cooldown completes at thirteen client ticks");
        timer.start(13, 10, 1);
        timer.advance(48);
        check(timer.active(), "10 TPS requires twenty-six client ticks, not fifty-two");
        timer.advance(49);
        check(!timer.active(), "TPS duration has exactly one scale factor");
        timer.start(34, 1, 0);
        check(!timer.active(), "zero configured delay remains immediate");
        timer.start(13, 20, 1);
        timer.reset();
        timer.advance(1);
        check(!timer.active(), "new session never inherits the old cooldown");
        System.out.println("AttackCooldownTest passed: shared controllers, low TPS, delay and session cases");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
