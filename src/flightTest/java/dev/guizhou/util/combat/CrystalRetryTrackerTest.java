package dev.guizhou.util.combat;

public final class CrystalRetryTrackerTest {
    public static void main(String[] args) {
        CrystalRetryTracker tracker = new CrystalRetryTracker();
        check(tracker.canAttempt(7, 0, 100, 2, 500), "first attack stays immediate");
        // A cancelled send does not call markSent and therefore consumes no attempt.
        check(tracker.canAttempt(7, 1, 100, 2, 500), "unsubmitted attack remains eligible");
        tracker.markSent(7, 10, 500);
        check(!tracker.canAttempt(7, 109, 100, 2, 500), "wait across ticks for confirmation");
        check(tracker.canAttempt(7, 110, 100, 2, 500), "bounded retry opens");
        tracker.markSent(7, 110, 500);
        check(!tracker.canAttempt(7, 210, 100, 2, 500), "attempt limit applies after interval");
        check(tracker.canAttempt(8, 210, 100, 2, 500), "other crystals are independent");
        check(tracker.canAttempt(7, 610, 100, 2, 500), "stalled crystal eventually retries");
        tracker.markSent(7, 610, 500);
        check(tracker.canAttempt(7, 710, 100, 2, 500), "new cycle resets count");
        tracker.removed(7);
        check(tracker.canAttempt(7, 611, 100, 2, 500), "removal clears reused ID");
        for (int i = 0; i < 2000; i++) tracker.markSent(i, i, 500);
        check(tracker.size() == 512, "tracker is bounded");
        tracker.expire(5000, 1000);
        check(tracker.size() == 0, "expiry evicts old attempts");
        tracker.markSent(1, 100, 500);
        tracker.clear();
        check(tracker.canAttempt(1, 101, 100, 1, 500), "new session resets attempts");
        System.out.println("CrystalRetryTrackerTest passed");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
