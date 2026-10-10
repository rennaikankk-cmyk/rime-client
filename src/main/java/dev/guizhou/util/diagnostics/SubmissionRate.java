package dev.guizhou.util.diagnostics;

/** Thread-safe one-second observation buckets, normalized after delayed renders.
 * Inputs must be final local submissions; there is no server acceptance/anti-cheat inference. */
public final class SubmissionRate {
    private static final long SECOND = 1_000_000_000L;
    private long startedNanos;
    private long count;
    private long lastRate;

    public synchronized void reset(long now) {
        startedNanos = now;
        count = lastRate = 0;
    }

    public synchronized void submitted(long now) {
        roll(now);
        count++;
    }

    public synchronized long perSecond(long now) {
        roll(now);
        return lastRate;
    }

    private void roll(long now) {
        long elapsed = now - startedNanos;
        if (elapsed < SECOND) return;
        lastRate = Math.round(count * (double) SECOND / elapsed);
        count = 0;
        startedNanos = now;
    }
}
