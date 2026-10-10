package dev.guizhou.util.combat;

/** Bounded stage latency estimates, learned only from observed state transitions. */
public final class AnchorTiming {
    private final double[] latency = {1, 1, 1};

    public void reset() { java.util.Arrays.fill(latency, 1); }

    public void observed(AnchorSequence.Action action, int ticks) {
        int index = index(action);
        if (index >= 0 && ticks >= 0) latency[index] = latency[index] * .75 + Math.clamp(ticks, 1, 20) * .25;
    }

    public int horizon(boolean anchor, int charges, int maximum, int placeDelay, int chargeDelay, int detonateDelay) {
        double ticks = latency[2] + Math.max(0, detonateDelay);
        if (!anchor || charges == 0) ticks += latency[1] + Math.max(0, chargeDelay);
        if (!anchor) ticks += latency[0] + Math.max(0, placeDelay);
        return Math.clamp((int)Math.ceil(ticks), 0, Math.clamp(maximum, 0, 20));
    }

    public static boolean ready(int tick, int previousActionTick, int delay) {
        return previousActionTick == Integer.MIN_VALUE
                || tick >= previousActionTick && (long)tick - previousActionTick >= Math.max(0, delay);
    }

    private static int index(AnchorSequence.Action action) {
        if (action == null) return -1;
        return switch (action) { case PLACE -> 0; case CHARGE -> 1; case DETONATE -> 2; default -> -1; };
    }
}
