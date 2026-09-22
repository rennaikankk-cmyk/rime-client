package dev.guizhou.util.elytra;

/** Local session feedback; correction packets are a caution signal, not proof of cheating. */
public final class GrimFlightAdaptation {
    private double budget = 1.0;
    private int stableTicks, corrections;
    private long lastTick = Long.MIN_VALUE, lastCorrection = Long.MIN_VALUE;

    public void reset() {
        budget = 1.0;
        stableTicks = corrections = 0;
        lastTick = lastCorrection = Long.MIN_VALUE;
    }

    public double budget() { return budget; }

    /** Only the main thread calls this, after associating a correction with recent controlled flight. */
    public int corrected(long tick) {
        if (tick < lastTick) reset();
        if (tick == lastCorrection) return cooldown();
        corrections = lastCorrection != Long.MIN_VALUE && tick - lastCorrection <= 200
                ? Math.min(4, corrections + 1) : 1;
        budget = Math.max(0.25, budget * 0.7);
        stableTicks = 0;
        lastTick = lastCorrection = tick;
        return cooldown();
    }

    /** Restore headroom only during consecutive valid controlled movement, never by idling within the same connection. */
    public void applied(long tick, boolean stable) {
        if (tick < lastTick) reset();
        if (tick == lastTick) return;
        if (!stable || lastTick != Long.MIN_VALUE && tick - lastTick != 1) stableTicks = 0;
        lastTick = tick;
        if (!stable) return;
        stableTicks++;
        if (stableTicks >= 100 && (stableTicks - 100) % 20 == 0) {
            budget = Math.min(1.0, budget + 0.05);
            if (budget == 1.0) corrections = 0;
        }
    }

    private int cooldown() { return Math.min(60, 10 << Math.max(0, corrections - 1)); }
}
