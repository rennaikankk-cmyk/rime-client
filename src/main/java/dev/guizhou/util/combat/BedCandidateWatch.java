package dev.guizhou.util.combat;

import java.util.HashMap;
import java.util.Map;

/**
 * Local-progress watchdog for one bed candidate. BedSequence bounds actions that were actually
 * submitted; this bounds candidates whose placement or detonation keeps failing locally (never
 * sent), which used to wait forever. Giving up also avoids the spot briefly so the scan does not
 * re-pick the same dead candidate immediately.
 */
public final class BedCandidateWatch {

    private int lastProgressTick = Integer.MIN_VALUE;
    private int timeoutTicks;
    private final Map<Long, Integer> avoidedUntil = new HashMap<>();

    /** Starts watching a freshly picked candidate with this patience window. */
    public void start(int tick, int timeoutTicks) {
        this.lastProgressTick = tick;
        this.timeoutTicks = Math.max(1, timeoutTicks);
    }

    /** Any real progress resets the patience window. */
    public void progress(int tick) {
        if (lastProgressTick != Integer.MIN_VALUE) lastProgressTick = tick;
    }

    /** True when the candidate has made no local progress for the whole window. */
    public boolean expired(int tick) {
        return lastProgressTick != Integer.MIN_VALUE && tick - lastProgressTick >= timeoutTicks;
    }

    /** Drops the candidate and keeps the spot out of the scan for {@code avoidTicks}. */
    public void giveUp(long footKey, int tick, int avoidTicks) {
        avoidedUntil.put(footKey, tick + Math.max(1, avoidTicks));
        lastProgressTick = Integer.MIN_VALUE;
    }

    public boolean avoided(long footKey, int tick) {
        return avoidedUntil.getOrDefault(footKey, Integer.MIN_VALUE) > tick;
    }

    public void purge(int tick) {
        avoidedUntil.values().removeIf(until -> until <= tick);
    }

    public void reset() {
        lastProgressTick = Integer.MIN_VALUE;
        avoidedUntil.clear();
    }
}
