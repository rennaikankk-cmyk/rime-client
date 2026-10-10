package dev.guizhou.util.elytra;

/** Bounded equipment and movement barriers for one natural mace fall. */
public final class MaceDiveState {
    public enum Phase { IDLE, WAIT_GLIDE_END, DIVING, RECOVERING }

    private static final long GLIDE_WAIT_TICKS = 6;
    private static final long FALL_TICKS = 12;
    private static final long GLIDE_WAIT_MAX = 20;
    private static final long FALL_MAX = 40;
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
        observe(tick, gliding, mustRecover, Double.POSITIVE_INFINITY, 0.0);
    }

    /**
     * The short glide and fall caps stay when fall distance is already enough.
     * A dive that has not yet reached {@code requiredFall} may continue, but not forever.
     */
    public void observe(long tick, boolean gliding, boolean mustRecover, double fallDistance, double requiredFall) {
        if (phase == Phase.IDLE || phase == Phase.RECOVERING) return;
        boolean needFall = requiredFall > 0.0 && Double.isFinite(fallDistance) && fallDistance < requiredFall;
        long glideCap = needFall ? GLIDE_WAIT_MAX : GLIDE_WAIT_TICKS;
        long fallCap = needFall ? FALL_MAX : FALL_TICKS;
        if (mustRecover || tick < startedTick || tick - startedTick >= glideCap + fallCap) {
            requestRecovery();
        } else if (phase == Phase.WAIT_GLIDE_END) {
            if (!gliding && tick > startedTick) {
                phase = Phase.DIVING;
                fallingTick = tick;
            } else if (tick - startedTick >= glideCap) {
                requestRecovery();
            }
        } else if (gliding || tick < fallingTick || tick - fallingTick >= fallCap) {
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
