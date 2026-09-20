package dev.guizhou.util.combat;

/** Advances from observed anchor states, never from a successful packet submission alone. */
public final class AnchorSequence {
    public enum Action { PLACE, CHARGE, DETONATE, WAIT, COMPLETE, ABORT }
    private Action awaiting;
    private int sentTick;
    private int attempts;

    public Action next(boolean anchor, int charges, boolean replaceable, int tick,
                       int timeoutTicks, int maxAttempts) {
        if (awaiting == Action.DETONATE && !anchor) {
            reset();
            return Action.COMPLETE;
        }
        boolean progressed = awaiting == Action.PLACE && anchor
                || awaiting == Action.CHARGE && anchor && charges > 0;
        if (progressed) reset();
        if (!anchor && !replaceable || awaiting == Action.CHARGE && !anchor) return Action.ABORT;
        if (awaiting != null) {
            if (tick >= sentTick && tick - sentTick < Math.max(1, timeoutTicks)) return Action.WAIT;
            if (attempts >= Math.max(1, maxAttempts)) return Action.ABORT;
        }
        return !anchor ? Action.PLACE : charges == 0 ? Action.CHARGE : Action.DETONATE;
    }

    public void sent(Action action, int tick) {
        if (action != Action.PLACE && action != Action.CHARGE && action != Action.DETONATE)
            throw new IllegalArgumentException("Not an anchor action: " + action);
        attempts = awaiting == action ? attempts + 1 : 1;
        awaiting = action;
        sentTick = tick;
    }

    public void reset() { awaiting = null; attempts = 0; sentTick = 0; }
}
