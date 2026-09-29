package dev.guizhou.util.hud;

/**
 * Two-threshold state machine for warnings that must not flap at a boundary: the signal has to
 * hold for {@code onAfter} samples to arm and stay clean for {@code offAfter} samples to clear.
 */
public final class SignalHysteresis {

    private final int onAfter;
    private final int offAfter;
    private int activeStreak;
    private int inactiveStreak;
    private boolean on;

    public SignalHysteresis(int onAfter, int offAfter) {
        if (onAfter < 1 || offAfter < 1) throw new IllegalArgumentException("thresholds must be positive");
        this.onAfter = onAfter;
        this.offAfter = offAfter;
    }

    /** Feeds one sample; returns whether the warning is armed after it. */
    public boolean update(boolean active) {
        if (active) {
            activeStreak++;
            inactiveStreak = 0;
            if (!on && activeStreak >= onAfter) on = true;
        } else {
            inactiveStreak++;
            activeStreak = 0;
            if (on && inactiveStreak >= offAfter) on = false;
        }
        return on;
    }

    public boolean on() {
        return on;
    }

    public void reset() {
        activeStreak = 0;
        inactiveStreak = 0;
        on = false;
    }
}
