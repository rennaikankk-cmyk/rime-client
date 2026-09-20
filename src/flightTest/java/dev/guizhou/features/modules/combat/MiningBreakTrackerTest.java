package dev.guizhou.features.modules.combat;

public final class MiningBreakTrackerTest {
    public static void main(String[] args) {
        MiningBreakTracker tracker = new MiningBreakTracker();
        tracker.update(1, 100, 3, true, 1000, 1000);
        tracker.update(2, 100, 5, false, 1000, 1000);
        check(tracker.isBreaking(100, true, 1001), "friend mining not recognized");
        tracker.update(2, 100, -1, false, 1001, 1000);
        check(tracker.isBreaking(100, false, 1002), "other breaker's STOP erased friend progress");
        tracker.update(1, 200, 0, true, 1100, 1000);
        check(!tracker.isBreaking(100, false, 1101), "old target survived a breaker move");
        tracker.update(1, 100, -1, true, 1101, 1000);
        check(tracker.isBreaking(200, true, 1102), "late cancellation erased newer target");
        tracker.update(3, 300, 0, false, 1100, 1000);
        check(!tracker.isBreaking(300, true, 1102), "enemy progress classified as a friend");
        check(!tracker.isBreaking(200, false, 2100), "expiry boundary retained stale progress");
        tracker.prune(2100);
        tracker.update(3, 400, 2, false, 2200, 1000);
        tracker.clear();
        check(!tracker.isBreaking(400, false, 2201), "session reset retained mining progress");
        System.out.println("MiningBreakTrackerTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
