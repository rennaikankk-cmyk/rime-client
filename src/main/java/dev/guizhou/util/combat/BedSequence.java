package dev.guizhou.util.combat;

/** A bed pair must be observed before detonation; a missing foot alone is not explosion confirmation. */
public final class BedSequence {
    public enum Action { PLACE, DETONATE, WAIT, COMPLETE, ABORT }
    private Action awaiting;
    private int sentTick;
    private int attempts;

    public Action next(boolean matchingPair, boolean headStillBed, boolean canPlace,
                       int tick, int timeoutTicks, int maxAttempts) {
        if (awaiting == Action.DETONATE && !headStillBed) {
            reset();
            return Action.COMPLETE;
        }
        if (awaiting == Action.PLACE && matchingPair) reset();
        if (awaiting != null) {
            if (tick >= sentTick && tick - sentTick < Math.max(1, timeoutTicks)) return Action.WAIT;
            if (attempts >= Math.max(1, maxAttempts)) return Action.ABORT;
        }
        if (matchingPair) return Action.DETONATE;
        if (headStillBed || !canPlace || awaiting == Action.DETONATE) return Action.ABORT;
        return Action.PLACE;
    }

    public void sent(Action action, int tick) {
        if (action != Action.PLACE && action != Action.DETONATE)
            throw new IllegalArgumentException("Not a bed action: " + action);
        attempts = awaiting == action ? attempts + 1 : 1;
        awaiting = action;
        sentTick = tick;
    }

    public void reset() { awaiting = null; sentTick = 0; attempts = 0; }
}
