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

    /** Search only after a sustained, damage-capable opportunity without feedback.
     * The old periodic sweep remains a math utility; live aiming uses this tracker. */
    public static final class Tracker {
        private long started = Long.MIN_VALUE, lastTick = Long.MIN_VALUE;
        public void reset() { started = lastTick = Long.MIN_VALUE; }
        public float apply(float baseYaw, long tick, int delay, double radius, boolean eligible) {
            if (!eligible || !Float.isFinite(baseYaw) || !Double.isFinite(radius) || radius <= 0) {
                reset(); return baseYaw;
            }
            if (started == Long.MIN_VALUE || tick < lastTick || tick - lastTick > 1) started = tick;
            lastTick = tick;
            long search = tick - started - Math.clamp(delay, 4, 10);
            if (search <= 0) return baseYaw;
            double amplitude = Math.min(12, radius) * Math.min(1, search / 6.0);
            return baseYaw + (float)(amplitude * Math.sin(Math.PI * (search % 12) / 6.0));
        }
    }

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
