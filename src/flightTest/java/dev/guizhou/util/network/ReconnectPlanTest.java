package dev.guizhou.util.network;

public final class ReconnectPlanTest {
    private static int checks;

    public static void main(String[] args) {
        ReconnectPlan plan = new ReconnectPlan();
        plan.onDisconnected(1_000, false, true, 5.0, 0, true);
        check(plan.state() == ReconnectPlan.State.WAITING, "Kick arms a countdown");
        check(plan.remainingMs(1_000) == 5_000, "First delay is the configured one");
        check(!plan.shouldFire(5_999), "Does not fire early");
        check(plan.shouldFire(6_000), "Fires when the countdown ends");
        check(!plan.shouldFire(6_050), "Fires exactly once per disconnect");
        check(plan.attempts() == 1, "Attempt counted on fire");

        plan.onDisconnected(10_000, false, true, 5.0, 0, true);
        check(plan.remainingMs(10_000) == 10_000, "Backoff doubles after a failed attempt");
        plan.shouldFire(20_000);
        plan.onDisconnected(30_000, false, true, 5.0, 0, true);
        check(plan.remainingMs(30_000) == 20_000, "Backoff doubles again");
        check(ReconnectPlan.delayMs(5.0, 10, true) == 40_000, "Backoff factor caps at 8x");
        check(ReconnectPlan.delayMs(600.0, 3, true) == ReconnectPlan.MAX_DELAY_MS, "Delay caps at five minutes");
        check(ReconnectPlan.delayMs(5.0, 10, false) == 5_000, "No backoff keeps the base delay");

        ReconnectPlan intentional = new ReconnectPlan();
        intentional.onDisconnected(0, true, true, 5.0, 0, true);
        check(intentional.state() == ReconnectPlan.State.CANCELLED && "intentional".equals(intentional.reason()),
                "AutoLog disconnects never reconnect");
        check(!intentional.shouldFire(1_000_000), "Cancelled plans never fire");

        ReconnectPlan noServer = new ReconnectPlan();
        noServer.onDisconnected(0, false, false, 5.0, 0, true);
        check(noServer.state() == ReconnectPlan.State.CANCELLED, "Nothing to reconnect to");

        ReconnectPlan limited = new ReconnectPlan();
        limited.onDisconnected(0, false, true, 1.0, 2, false);
        limited.shouldFire(1_000);
        limited.onDisconnected(2_000, false, true, 1.0, 2, false);
        limited.shouldFire(3_000);
        limited.onDisconnected(4_000, false, true, 1.0, 2, false);
        check(limited.state() == ReconnectPlan.State.EXHAUSTED, "MaxAttempts stops retrying");
        limited.onStableSession();
        check(limited.attempts() == 0 && limited.state() == ReconnectPlan.State.IDLE, "A stable session resets the count");

        ReconnectPlan user = new ReconnectPlan();
        user.onDisconnected(0, false, true, 5.0, 0, true);
        user.cancel("user");
        check(!user.shouldFire(10_000), "Cancel button stops the countdown");
        user.fireNow();
        check(user.attempts() == 1 && user.state() == ReconnectPlan.State.IDLE, "Manual reconnect counts as an attempt");

        ReconnectPlan left = new ReconnectPlan();
        left.onDisconnected(0, false, true, 5.0, 0, true);
        left.abandon();
        check(left.state() == ReconnectPlan.State.CANCELLED && !left.shouldFire(10_000), "Leaving the screen abandons the countdown");
        System.out.println("ReconnectPlanTest passed: " + checks + " checks");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
