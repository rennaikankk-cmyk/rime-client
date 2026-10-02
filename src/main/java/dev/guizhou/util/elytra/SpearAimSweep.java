package dev.guizhou.util.elytra;

/**
 * Bounded zero-mean yaw sweep for charged spear aiming.
 *
 * The spear's kinetic damage is a per-tick server ray query along the sent look
 * direction while the weapon is in use. Residual target-position prediction error
 * (measured median 2.37 blocks with the packet-offset predictor) is larger than the
 * target box itself at spear engagement range, so a single-point aim points at a
 * ghost point beside the box. Sweeping the yaw while the spear is ready lets the
 * continuous ray pass across the uncertainty cone instead of parking beside it.
 */
public final class SpearAimSweep {
    public static final float AMPLITUDE_DEG = 22.0f;
    public static final int PERIOD_TICKS = 6;

    private SpearAimSweep() {
    }

    /** Sweep offset in degrees for the given monotonic tick counter; zero-mean over one period. */
    public static float sweepOffsetDeg(int tick) {
        return AMPLITUDE_DEG * (float) Math.sin(2.0 * Math.PI * tick / PERIOD_TICKS);
    }

    /** Applies the sweep to a base yaw in degrees. */
    public static float applySweep(float baseYawDeg, int tick) {
        return baseYawDeg + sweepOffsetDeg(tick);
    }
}
