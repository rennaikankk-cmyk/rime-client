package dev.guizhou.util.elytra;

public final class CombatHitWindowTest {
    private static int assertions;

    public static void main(String[] args) {
        consumeOnlyOnce();
        rejectUnrelatedAndOutOfWindowFeedback();
        clearAndReplacementInvalidateOldAttack();
        tickBoundariesAndInvalidWindows();
        System.out.println("CombatHitWindowTest passed: " + assertions + " assertions");
    }

    private static void consumeOnlyOnce() {
        CombatHitWindow window = new CombatHitWindow();
        check(!window.pending(7, 100), "new window has no pending attack");
        check(!window.consume(7, 100), "unarmed feedback is rejected");
        window.arm(7, 100, 6);
        check(window.pending(7, 100), "send tick belongs to window");
        check(window.pending(7, 105), "query does not consume feedback");
        check(window.consume(7, 106), "inclusive final tick can confirm");
        check(!window.consume(7, 106), "duplicate feedback cannot confirm twice");
        check(!window.pending(7, 106), "consumed attack is no longer pending");
    }

    private static void rejectUnrelatedAndOutOfWindowFeedback() {
        CombatHitWindow window = new CombatHitWindow();
        window.arm(7, 100, 6);
        check(!window.consume(8, 103), "other target cannot consume attack");
        check(window.pending(7, 103), "other target leaves expected confirmation pending");
        check(!window.consume(7, 99), "feedback before send tick is rejected");
        check(window.consume(7, 104), "rejected earlier feedback does not consume attack");
        window.arm(7, 100, 6);
        check(!window.pending(7, 107), "expired attack is not pending");
        check(!window.consume(7, 107), "late feedback is rejected");
    }

    private static void clearAndReplacementInvalidateOldAttack() {
        CombatHitWindow window = new CombatHitWindow();
        window.arm(7, 100, 6);
        window.clear();
        check(!window.consume(7, 101), "session change or intervening attack clears confirmation");
        window.arm(7, 100, 6);
        window.arm(8, 102, 3);
        check(!window.consume(7, 103), "replacement rejects previous target");
        check(window.consume(8, 103), "replacement target can confirm");
        window.arm(7, 110, 6);
        window.arm(7, 120, 2);
        check(!window.consume(7, 115), "same-target replacement uses new send time");
        check(window.consume(7, 121), "same-target replacement has an independent window");
    }

    private static void tickBoundariesAndInvalidWindows() {
        CombatHitWindow window = new CombatHitWindow();
        window.arm(0, 10, 0);
        check(window.consume(0, 10), "zero-duration window permits same-tick confirmation");
        window.arm(0, 10, 0);
        check(!window.consume(0, 11), "zero-duration window rejects following tick");
        window.arm(7, 100, 6);
        window.arm(-1, 101, 6);
        check(!window.pending(7, 102), "invalid target clears earlier pending attack");
        check(!window.consume(-1, 102), "negative entity ID is rejected");
        window.arm(7, 100, -1);
        check(!window.consume(7, 100), "negative window is rejected");
        window.arm(7, Integer.MAX_VALUE - 2, 10);
        check(window.consume(7, Integer.MAX_VALUE), "expiry arithmetic does not overflow near maximum tick");
        window.arm(7, Integer.MAX_VALUE, 10);
        check(!window.consume(7, Integer.MIN_VALUE), "wrapped tick cannot confirm old-session attack");
        window.arm(7, Integer.MIN_VALUE, Integer.MAX_VALUE);
        check(window.consume(7, -1), "large valid interval uses long elapsed arithmetic");
        window.arm(7, Integer.MIN_VALUE, Integer.MAX_VALUE);
        check(!window.consume(7, 0), "large expired interval cannot wrap back into window");
    }

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
