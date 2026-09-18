package dev.guizhou.util.elytra;

/**
 * One pending local attack confirmation, used only on the client thread.
 * The caller validates damage type and source before consuming, and clears
 * the window on session changes or intervening attacks from another weapon.
 */
public final class CombatHitWindow {
    private boolean armed;
    private int targetId;
    private int sentTick;
    private int windowTicks;

    /** Replace the pending attack. The inclusive window starts at the send tick. */
    public void arm(int targetId, int tick, int windowTicks) {
        clear();
        if (targetId < 0 || windowTicks < 0) return;
        this.targetId = targetId;
        this.sentTick = tick;
        this.windowTicks = windowTicks;
        armed = true;
    }

    /** Consume at most one matching confirmation without consuming unrelated feedback. */
    public boolean consume(int targetId, int tick) {
        if (!pending(targetId, tick)) return false;
        clear();
        return true;
    }

    public boolean pending(int targetId, int tick) {
        long elapsed = (long) tick - sentTick;
        return armed && this.targetId == targetId && elapsed >= 0 && elapsed <= windowTicks;
    }

    public void clear() {
        armed = false;
        targetId = -1;
        sentTick = 0;
        windowTicks = 0;
    }
}
