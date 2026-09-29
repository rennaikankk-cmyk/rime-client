package dev.guizhou.util.combat;

/** Headless checks of the bed candidate watchdog: bounded local patience plus spot avoidance. */
public final class BedCandidateWatchTest {
    private static int checks;

    public static void main(String[] args) {
        patienceWindow();
        avoidance();
        reset();
        System.out.println("BedCandidateWatchTest passed: " + checks + " checks");
    }

    private static void patienceWindow() {
        BedCandidateWatch watch = new BedCandidateWatch();
        check(!watch.expired(0), "no candidate is not expired");
        watch.start(100, 40);
        check(!watch.expired(139), "inside the patience window");
        check(watch.expired(140), "patience runs out at the window edge");
        watch.start(200, 40);
        watch.progress(225);
        check(!watch.expired(264), "progress restarts the window");
        check(watch.expired(265), "expiry counts from the last progress");
    }

    private static void avoidance() {
        BedCandidateWatch watch = new BedCandidateWatch();
        watch.start(0, 40);
        watch.giveUp(42L, 100, 60);
        check(watch.avoided(42L, 100), "a given-up spot is avoided");
        check(watch.avoided(42L, 159), "avoidance holds to the edge");
        check(!watch.avoided(42L, 160), "avoidance expires");
        check(!watch.avoided(43L, 100), "other spots are untouched");
        watch.purge(160);
        check(!watch.avoided(42L, 100) || watch.avoided(42L, 200), "purge drops expired entries");
    }

    private static void reset() {
        BedCandidateWatch watch = new BedCandidateWatch();
        watch.start(0, 10);
        watch.giveUp(7L, 0, 500);
        watch.reset();
        check(!watch.expired(1000), "reset clears the expiry state");
        check(!watch.avoided(7L, 10), "reset clears avoidance");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
