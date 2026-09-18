package dev.guizhou.util.elytra;

/** Bounded equipment and movement barriers for one natural mace fall. */
public final class MaceDiveState {
    public enum Phase { IDLE, WAIT_GLIDE_END, DIVING, RECOVERING }

    private static final long GLIDE_WAIT_TICKS = 6;
    private static final long FALL_TICKS = 12;
    private Phase phase = Phase.IDLE;
    private long startedTick;
    private long fallingTick;

    public Phase phase() {
        return phase;
    }

    public boolean begin(long tick) {
        if (phase != Phase.IDLE) return false;
        phase = Phase.WAIT_GLIDE_END;
        startedTick = tick;
        fallingTick = tick;
        return true;
    }

    public void observe(long tick, boolean gliding, boolean mustRecover) {
        if (phase == Phase.IDLE || phase == Phase.RECOVERING) return;
        if (mustRecover || tick < startedTick || tick - startedTick >= GLIDE_WAIT_TICKS + FALL_TICKS) {
            requestRecovery();
        } else if (phase == Phase.WAIT_GLIDE_END) {
            if (!gliding && tick > startedTick) {
                phase = Phase.DIVING;
                fallingTick = tick;
            } else if (tick - startedTick >= GLIDE_WAIT_TICKS) {
                requestRecovery();
            }
        } else if (gliding || tick < fallingTick || tick - fallingTick >= FALL_TICKS) {
            requestRecovery();
        }
    }

    public boolean canAttack(boolean gliding, boolean descending, boolean vanillaSmashReady) {
        return phase == Phase.DIVING && !gliding && descending && vanillaSmashReady;
    }

    public void requestRecovery() {
        if (phase != Phase.IDLE) phase = Phase.RECOVERING;
    }

    public void reset() {
        phase = Phase.IDLE;
        startedTick = 0;
        fallingTick = 0;
    }
}
