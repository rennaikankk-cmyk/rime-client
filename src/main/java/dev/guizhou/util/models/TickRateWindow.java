package dev.guizhou.util.models;

/** Client-thread sampler; immutable snapshots can be published to other threads. */
public final class TickRateWindow {
    public static final Snapshot DEFAULT = new Snapshot(20.0F, 20.0F, 20.0F);

    private final float[] samples;
    private int next;
    private int count;
    private double total;
    private long previousTick;
    private long previousNanos;
    private boolean initialized;
    private Snapshot snapshot = DEFAULT;

    public TickRateWindow(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity <= 0");
        samples = new float[capacity];
    }

    public Snapshot add(long serverTick, long arrivalNanos) {
        if (!initialized) {
            initialized = true;
            previousTick = serverTick;
            previousNanos = arrivalNanos;
            return snapshot;
        }
        long elapsed = arrivalNanos - previousNanos;
        long ticks;
        try {
            ticks = Math.subtractExact(serverTick, previousTick);
        } catch (ArithmeticException invalidDelta) {
            return snapshot;
        }
        if (elapsed <= 0 || ticks <= 0) return snapshot;

        float rate = (float) Math.min(20.0, ticks * 1_000_000_000.0 / elapsed);
        if (count == samples.length) total -= samples[next];
        else count++;
        samples[next] = rate;
        total += rate;
        next = (next + 1) % samples.length;
        previousTick = serverTick;
        previousNanos = arrivalNanos;

        float minimum = 20.0F;
        for (int i = 0; i < count; i++) minimum = Math.min(minimum, samples[i]);
        snapshot = new Snapshot((float) Math.clamp(total / count, 0.0, 20.0), rate, minimum);
        return snapshot;
    }

    public Snapshot reset() {
        next = 0;
        count = 0;
        total = 0.0;
        initialized = false;
        snapshot = DEFAULT;
        return snapshot;
    }

    public record Snapshot(float average, float latest, float minimum) {
    }
}
