package dev.guizhou.util.network;

/**
 * AutoReconnect timing, free of Minecraft types. One disconnect arms at most one attempt;
 * an intentional disconnect (AutoLog) or a missing server cancels instead of arming.
 */
public final class ReconnectPlan {
    public enum State { IDLE, WAITING, CANCELLED, EXHAUSTED }

    /** Upper bound for the backed-off delay, so a long outage still retries every few minutes. */
    public static final long MAX_DELAY_MS = 5 * 60 * 1000L;
    /** Staying connected this long counts as a successful reconnect and resets the backoff. */
    public static final long STABLE_SESSION_MS = 30_000L;

    private State state = State.IDLE;
    private long deadline;
    private int attempts;
    private String reason = "";

    public void onDisconnected(long now, boolean intentional, boolean hasServer,
                               double delaySeconds, int maxAttempts, boolean backoff) {
        if (intentional) {
            cancel("intentional");
            return;
        }
        if (!hasServer) {
            cancel("no server");
            return;
        }
        if (maxAttempts > 0 && attempts >= maxAttempts) {
            state = State.EXHAUSTED;
            reason = "attempts";
            return;
        }
        deadline = now + delayMs(delaySeconds, attempts, backoff);
        state = State.WAITING;
        reason = "";
    }

    /** Doubles per failed attempt (1x, 2x, 4x, 8x) when backoff is on, capped at {@link #MAX_DELAY_MS}. */
    public static long delayMs(double delaySeconds, int attempts, boolean backoff) {
        double seconds = Double.isFinite(delaySeconds) ? Math.max(0.0, delaySeconds) : 0.0;
        double factor = backoff ? Math.pow(2, Math.min(3, Math.max(0, attempts))) : 1.0;
        return (long) Math.min(MAX_DELAY_MS, seconds * 1000.0 * factor);
    }

    /** True exactly once when the countdown ends; the attempt is counted immediately. */
    public boolean shouldFire(long now) {
        if (state != State.WAITING || now < deadline) return false;
        state = State.IDLE;
        attempts++;
        return true;
    }

    /** Manual "reconnect now": counts as an attempt and ends any countdown. */
    public void fireNow() {
        state = State.IDLE;
        attempts++;
    }

    public void cancel(String why) {
        state = State.CANCELLED;
        reason = why == null ? "" : why;
    }

    /** Leaving the disconnect screen by hand stops a pending countdown but keeps the attempt count. */
    public void abandon() {
        if (state == State.WAITING) cancel("left screen");
    }

    public void onStableSession() {
        attempts = 0;
        if (state != State.WAITING) state = State.IDLE;
        reason = "";
    }

    public long remainingMs(long now) {
        return state == State.WAITING ? Math.max(0L, deadline - now) : 0L;
    }

    public State state() { return state; }
    public int attempts() { return attempts; }
    public String reason() { return reason; }
}
