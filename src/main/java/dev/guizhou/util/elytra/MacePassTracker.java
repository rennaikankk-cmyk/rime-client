package dev.guizhou.util.elytra;

/** Recognizes an exhausted attack pass without discarding an open smash opportunity. */
public final class MacePassTracker {
    private static final long MAX_PASS_TICKS = 80;
    private static final long STALLED_TICKS = 30;
    private boolean active;
    private long startedTick;
    private long progressTick;
    private double closest;
    private double previous;

    public void reset() {
        active = false;
    }

    public boolean shouldPullUp(long tick, double distance, double reach, double playerY, double targetY,
                                boolean smashWindowOpen) {
        if (!Double.isFinite(distance) || distance < 0.0 || !Double.isFinite(reach) || reach <= 0.0
                || !Double.isFinite(playerY) || !Double.isFinite(targetY)) {
            reset();
            return true;
        }
        if (!active || tick < startedTick || tick < progressTick) {
            active = true;
            startedTick = progressTick = tick;
            closest = previous = distance;
        }
        double lastDistance = previous;
        previous = distance;
        if (distance < closest - 0.25) {
            closest = distance;
            progressTick = tick;
        }
        if (smashWindowOpen) return false;
        boolean flewPast = closest <= reach + 1.0 && distance > reach + 0.35
                && distance >= closest + 0.75 && distance > lastDistance + 0.1;
        return playerY < targetY + 0.5 || flewPast
                || tick - progressTick >= STALLED_TICKS || tick - startedTick >= MAX_PASS_TICKS;
    }
}
