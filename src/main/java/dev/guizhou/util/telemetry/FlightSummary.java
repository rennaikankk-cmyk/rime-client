package dev.guizhou.util.telemetry;

import com.google.gson.JsonObject;

/**
 * Pure per-window flight diagnostics accumulator. The caller feeds one sample per active
 * game tick with client-observed displacement deltas; this class owns interval acceptance
 * (monotonic time only), gap and jump invalidation, and the bounded aggregates that the
 * server's strict flight_summary schema expects. No Minecraft types, no game state writes.
 *
 * Speed semantics: displacement-derived values are blocks per second over the OBSERVED
 * (accepted-interval) time only and are client-side observations, not server-authoritative
 * measurements; nominal_bps is the average of deltaMovement*20 samples and is nominal.
 */
public final class FlightSummary {
    /** Rocket gate outcomes written by the flight module; 0 means no gate result this tick. */
    public static final int BLOCK_NONE = 0, BLOCK_NOT_ENABLED = 1, BLOCK_USING_ITEM = 2,
            BLOCK_MACE_DESCENT = 3, BLOCK_SPEED_GATE = 4, BLOCK_REFIRE = 5, BLOCK_USEFUL_BOOST = 6,
            BLOCK_NO_ROCKET = 7, BLOCK_TRAJECTORY = 8;
    public static final int BLOCK_REASONS = 8;

    /** Window flush target; hard cap keeps a stuck session bounded. */
    public static final long WINDOW_NANOS = 10_000_000_000L;
    public static final long MAX_WINDOW_NANOS = 60_000_000_000L;
    /** Two samples further apart start a new segment; the interval between them is dropped. */
    public static final long GAP_NANOS = 1_500_000_000L;
    /** A single accepted interval faster than this is a teleport-like jump, not flight. */
    public static final double MAX_INTERVAL_BPS = 120.0;
    /** Windows without enough observed time or ticks are discarded, never uploaded. */
    public static final long MIN_OBSERVED_NANOS = 500_000_000L;
    public static final int MIN_TICKS = 10;

    private long firstTickNanos = Long.MIN_VALUE;
    private long lastSampleNanos = Long.MIN_VALUE;
    private long observedNanos;
    private int ticks;
    private int gaps;
    private long glidingNanos, armorflyNanos, chaseNanos, diveNanos;
    private long boostAttachedNanos, boostDetachedNanos;
    private long ascentNanos;
    private final int[] rocketBlocks = new int[BLOCK_REASONS + 1];
    private double horizontalBlocks, ascentBlocks, descentBlocks;
    private double nominalSum;
    private int nominalSamples;
    private double requestedSum, appliedSum;
    private int appliedSamples;
    private int corrections;

    /** One active-tick sample. Displacement deltas may be dropped by gap/jump invalidation. */
    public void tick(long nanos, double dx, double dy, double dz,
                     boolean gliding, boolean armorfly, boolean chase, boolean dive,
                     boolean boostAttached, double nominalBps,
                     double requestedBpt, double appliedBpt, int rocketBlock) {
        if (nanos < 0 || !Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)) return;
        ticks++;
        if (firstTickNanos == Long.MIN_VALUE) firstTickNanos = nanos;
        long interval = lastSampleNanos == Long.MIN_VALUE ? 0 : nanos - lastSampleNanos;
        boolean accepted = false;
        if (interval > 0) {
            double seconds = interval / 1_000_000_000.0;
            double horizontal = Math.hypot(dx, dz);
            if (interval > GAP_NANOS || horizontal / seconds > MAX_INTERVAL_BPS
                    || Math.abs(dy) / seconds > MAX_INTERVAL_BPS) {
                gaps++;
            } else {
                accepted = true;
                observedNanos += interval;
                horizontalBlocks += horizontal;
                if (dy > 0) {
                    ascentBlocks += dy;
                    ascentNanos += interval;
                } else if (dy < 0) {
                    descentBlocks += -dy;
                }
                if (gliding) {
                    glidingNanos += interval;
                    if (boostAttached) boostAttachedNanos += interval;
                    else boostDetachedNanos += interval;
                }
                if (armorfly) armorflyNanos += interval;
                if (chase) chaseNanos += interval;
                if (dive) diveNanos += interval;
            }
        }
        lastSampleNanos = nanos;
        if (Double.isFinite(nominalBps)) {
            nominalSum += nominalBps;
            nominalSamples++;
        }
        if (Double.isFinite(requestedBpt) && Double.isFinite(appliedBpt)) {
            requestedSum += requestedBpt;
            appliedSum += appliedBpt;
            appliedSamples++;
        }
        if (rocketBlock >= BLOCK_NOT_ENABLED && rocketBlock <= BLOCK_REASONS) {
            rocketBlocks[rocketBlock]++;
        }
    }

    /** A server position correction splits the segment; the crossing interval is dropped. */
    public void correction() {
        corrections++;
        lastSampleNanos = Long.MIN_VALUE;
    }

    public boolean shouldFlush(long nanos) {
        return firstTickNanos != Long.MIN_VALUE && nanos - firstTickNanos >= WINDOW_NANOS;
    }

    /** Null when the window has too little observed data; such windows are discarded. */
    public Window close(long nanos) {
        if (firstTickNanos == Long.MIN_VALUE || observedNanos < MIN_OBSERVED_NANOS || ticks < MIN_TICKS) {
            return null;
        }
        double observedSeconds = observedNanos / 1_000_000_000.0;
        double ascentSeconds = ascentNanos / 1_000_000_000.0;
        return new Window(
                Math.max(0, (nanos - firstTickNanos) / 1_000_000L),
                observedNanos / 1_000_000L,
                ticks, gaps,
                glidingNanos / 1_000_000L, armorflyNanos / 1_000_000L,
                chaseNanos / 1_000_000L, diveNanos / 1_000_000L,
                boostAttachedNanos / 1_000_000L, boostDetachedNanos / 1_000_000L,
                horizontalBlocks, ascentBlocks, descentBlocks,
                horizontalBlocks / observedSeconds,
                (ascentBlocks - descentBlocks) / observedSeconds,
                ascentSeconds > 0 ? ascentBlocks / ascentSeconds : 0.0,
                nominalSamples > 0 ? nominalSum / nominalSamples : 0.0,
                appliedSamples > 0 ? requestedSum / appliedSamples : 0.0,
                appliedSamples > 0 ? appliedSum / appliedSamples : 0.0,
                appliedSamples,
                corrections,
                rocketBlocks.clone());
    }

    /** Aggregates for one window; milliseconds and blocks, speeds in blocks per second/tick. */
    public record Window(long wallMs, long observedMs, int ticks, int gaps,
                         long glidingMs, long armorflyMs, long chaseMs, long diveMs,
                         long boostAttachedMs, long boostDetachedMs,
                         double horizontalBlocks, double ascentBlocks, double descentBlocks,
                         double horizontalBps, double verticalBps, double ascentBps,
                         double nominalBps, double requestedBpt, double appliedBpt,
                         int appliedSamples, int corrections, int[] rocketBlocks) {}

    /** Renders exactly the server flight_summary data fields; bounded, no extra keys. */
    public static JsonObject render(Window window, String chaseMode, JsonObject config) {
        JsonObject data = new JsonObject();
        data.addProperty("wall_ms", window.wallMs());
        data.addProperty("observed_ms", window.observedMs());
        data.addProperty("ticks", window.ticks());
        data.addProperty("gaps", window.gaps());
        data.addProperty("gliding_ms", window.glidingMs());
        data.addProperty("armorfly_ms", window.armorflyMs());
        data.addProperty("chase_ms", window.chaseMs());
        data.addProperty("mace_dive_ms", window.diveMs());
        data.addProperty("chase_mode", chaseMode);
        data.addProperty("horizontal_blocks", TelemetryPrivacy.bounded(window.horizontalBlocks(), 0, 100_000));
        data.addProperty("ascent_blocks", TelemetryPrivacy.bounded(window.ascentBlocks(), 0, 100_000));
        data.addProperty("descent_blocks", TelemetryPrivacy.bounded(window.descentBlocks(), 0, 100_000));
        data.addProperty("horizontal_bps", TelemetryPrivacy.bounded(window.horizontalBps(), 0, 1000));
        data.addProperty("vertical_bps", TelemetryPrivacy.bounded(window.verticalBps(), -1000, 1000));
        data.addProperty("ascent_bps", TelemetryPrivacy.bounded(window.ascentBps(), 0, 1000));
        data.addProperty("nominal_bps", TelemetryPrivacy.bounded(window.nominalBps(), 0, 1000));
        data.addProperty("requested_bpt", TelemetryPrivacy.bounded(window.requestedBpt(), 0, 100));
        data.addProperty("applied_bpt", TelemetryPrivacy.bounded(window.appliedBpt(), 0, 100));
        data.addProperty("applied_samples", window.appliedSamples());
        data.addProperty("boost_attached_ms", window.boostAttachedMs());
        data.addProperty("boost_detached_ms", window.boostDetachedMs());
        JsonObject blocks = new JsonObject();
        String[] names = {"not_enabled", "using_item", "mace_descent", "speed_gate",
                "refire", "useful_boost", "no_rocket", "trajectory"};
        for (int index = 0; index < names.length; index++) {
            blocks.addProperty(names[index], window.rocketBlocks()[index + 1]);
        }
        data.add("rocket_blocks", blocks);
        data.addProperty("corrections", window.corrections());
        data.add("config", config);
        return data;
    }
}
