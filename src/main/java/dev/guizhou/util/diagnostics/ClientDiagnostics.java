package dev.guizhou.util.diagnostics;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Opt-in, process-local elapsed-time sampling. No game objects, identifiers, or network I/O.
 * Timers are inclusive wall time: listener, module, and inner operation totals overlap and
 * must NOT be added together. Frame samples are intervals between in-game HUD render events,
 * not GPU execution time. Live snapshots are observational; stop freezes the final snapshot. */
public final class ClientDiagnostics {
    public static final int DEFAULT_SECONDS = 60;
    public static final int MAX_SECONDS = 60;
    static final int MAX_TIMINGS = 512;
    static final int MAX_COUNTERS = 256;
    private static final long[] BUCKET_LIMITS = bucketLimits();
    private static final TimingSnapshot EMPTY_TIMING = new TimingSnapshot(0, 0, 0, 0, 0);
    private static final Snapshot EMPTY = new Snapshot(false, 0, 0, 0,
            Map.of(), Map.of(), EMPTY_TIMING, 0, 0, 0, 0);
    private static volatile Window window;

    private ClientDiagnostics() {}

    public record TimingSnapshot(long count, long totalNanos, double meanNanos,
                                 long maxNanos, long p95Nanos) {}

    public record Snapshot(boolean active, long startedAtEpochMillis, long durationMillis,
                           long remainingMillis, Map<String, TimingSnapshot> timings,
                           Map<String, Long> counters, TimingSnapshot frameTiming,
                           long gcCollections, long gcMillis, long heapUsedBytes, long droppedKeys) {
        public Snapshot {
            timings = Collections.unmodifiableMap(new LinkedHashMap<>(timings));
            counters = Collections.unmodifiableMap(new LinkedHashMap<>(counters));
        }
    }

    /** Starts a fresh bounded window; it never enables remote telemetry. */
    public static synchronized void start(int seconds) {
        if (seconds < 1 || seconds > MAX_SECONDS) {
            throw new IllegalArgumentException("Diagnostic duration must be 1.." + MAX_SECONDS + " seconds");
        }
        Window previous = window;
        if (previous != null) previous.cancelExpiry();
        Window next = new Window(seconds);
        window = next;
        next.expiry = ExpiryExecutor.INSTANCE.schedule(() -> next.finish(System.nanoTime()), seconds, TimeUnit.SECONDS);
    }

    public static void stop() {
        Window current = window;
        if (current != null) current.finish(System.nanoTime());
    }

    /** Clears retained results without starting a recording. */
    public static synchronized void clear() {
        Window previous = window;
        window = null;
        if (previous != null) previous.cancelExpiry();
    }

    public static boolean isCollecting() { return activeWindow() != null; }

    /** Numeric window identity for bounded asynchronous observations; never an account identifier. */
    public static long recordingId() {
        Window current = activeWindow();
        return current == null ? 0 : current.startNanos;
    }

    /** The zero sentinel avoids both clock reads and allocations when recording is off. */
    public static long startTimer() {
        Window current = window;
        if (current == null || current.finished != null || current.stopping) return 0;
        long now = System.nanoTime();
        if (now - current.deadlineNanos >= 0) return 0;
        return now;
    }

    public static void endTimer(String stableKey, long startedNanos) {
        if (startedNanos == 0) return;
        Window current = window;
        if (current == null || current.finished != null || current.stopping || startedNanos - current.startNanos < 0) return;
        long now = System.nanoTime();
        if (now - current.deadlineNanos >= 0 || now - startedNanos < 0) return;
        Timing timing = current.timing(stableKey);
        if (timing != null) timing.add(now - startedNanos);
    }

    public static void count(String stableKey, long delta) {
        Window current = activeWindow();
        if (current == null || delta <= 0) return;
        LongAdder counter = current.counter(stableKey);
        if (counter != null) counter.add(delta);
    }

    public static void recordFrame() {
        Window current = activeWindow();
        if (current == null) return;
        long now = System.nanoTime();
        long previous = current.lastFrame.getAndSet(now);
        if (previous != 0 && now - previous >= 0) current.frames.add(now - previous);
    }

    public static Snapshot snapshot() {
        Window current = window;
        if (current == null) return EMPTY;
        Snapshot finished = current.finished;
        if (finished != null) return finished;
        long now = System.nanoTime();
        if (now - current.deadlineNanos >= 0 || current.stopping) return current.finish(now);
        return current.capture(now, true);
    }

    /** Creates one local JSON file. Existing files are never overwritten; nothing is uploaded. */
    public static Path export(Path file) throws IOException {
        Path output = file.toAbsolutePath().normalize();
        Path parent = output.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(output, toJson(snapshot()), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return output;
    }

    public static String toJson(Snapshot snapshot) {
        StringBuilder out = new StringBuilder(4096);
        out.append("{\n  \"schema_version\": 1,\n  \"scope\": \"local_only\",\n")
                .append("  \"timing_semantics\": \"inclusive_wall_time_do_not_sum_nested_scopes\",\n")
                .append("  \"p95_semantics\": \"histogram_upper_bound_approx_25_percent_resolution\",\n")
                .append("  \"frame_semantics\": \"in_game_hud_render_interval_not_gpu_time\",\n")
                .append("  \"packet_semantics\": \"local_dispatch_submission_not_server_acknowledgement\",\n")
                .append("  \"active\": ").append(snapshot.active()).append(",\n")
                .append("  \"started_at_epoch_ms\": ").append(snapshot.startedAtEpochMillis()).append(",\n")
                .append("  \"duration_ms\": ").append(snapshot.durationMillis()).append(",\n")
                .append("  \"remaining_ms\": ").append(snapshot.remainingMillis()).append(",\n")
                .append("  \"gc_collections\": ").append(snapshot.gcCollections()).append(",\n")
                .append("  \"gc_ms\": ").append(snapshot.gcMillis()).append(",\n")
                .append("  \"heap_used_bytes\": ").append(snapshot.heapUsedBytes()).append(",\n")
                .append("  \"dropped_key_samples\": ").append(snapshot.droppedKeys()).append(",\n")
                .append("  \"frame_timing\": ");
        appendTiming(out, snapshot.frameTiming());
        out.append(",\n  \"timings\": {");
        boolean first = true;
        for (var entry : snapshot.timings().entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append("\n    ");
            appendString(out, entry.getKey());
            out.append(": ");
            appendTiming(out, entry.getValue());
        }
        out.append("\n  },\n  \"counters\": {");
        first = true;
        for (var entry : snapshot.counters().entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append("\n    ");
            appendString(out, entry.getKey());
            out.append(": ").append(entry.getValue());
        }
        return out.append("\n  }\n}\n").toString();
    }

    private static void appendTiming(StringBuilder out, TimingSnapshot timing) {
        out.append("{\"count\": ").append(timing.count())
                .append(", \"total_ns\": ").append(timing.totalNanos())
                .append(", \"mean_ns\": ").append(String.format(Locale.ROOT, "%.3f", timing.meanNanos()))
                .append(", \"max_ns\": ").append(timing.maxNanos())
                .append(", \"p95_ns\": ").append(timing.p95Nanos()).append('}');
    }

    private static void appendString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\');
            if (c < 32) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
        }
        out.append('"');
    }

    private static Window activeWindow() {
        Window current = window;
        if (current == null || current.finished != null || current.stopping) return null;
        return System.nanoTime() - current.deadlineNanos < 0 ? current : null;
    }

    private static boolean validKey(String key) { return key != null && !key.isBlank() && key.length() <= 128; }

    /** Created only after explicit start; the idle daemon retires after a completed window.
     * Its only job is freezing local numeric/JMX data at the deadline, with no Minecraft reads. */
    private static final class ExpiryExecutor {
        static final ScheduledThreadPoolExecutor INSTANCE = create();
        private static ScheduledThreadPoolExecutor create() {
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
                Thread thread = new Thread(task, "GuizhouWare-LocalDiagnostics");
                thread.setDaemon(true);
                return thread;
            });
            executor.setRemoveOnCancelPolicy(true);
            executor.setKeepAliveTime(1, TimeUnit.SECONDS);
            executor.allowCoreThreadTimeOut(true);
            return executor;
        }
    }

    private static long[] bucketLimits() {
        long[] limits = new long[128];
        long bound = 1;
        for (int i = 0; i < limits.length - 1; i++) {
            limits[i] = bound;
            bound += Math.max(1, bound / 4);
        }
        limits[limits.length - 1] = Long.MAX_VALUE;
        return limits;
    }

    /** Per-key locking keeps each distribution internally consistent across network/render threads. */
    static final class Timing {
        private long count;
        private long total;
        private long maximum;
        private final long[] histogram = new long[BUCKET_LIMITS.length];

        synchronized void add(long nanos) {
            if (nanos < 0) return;
            count++;
            total += nanos;
            maximum = Math.max(maximum, nanos);
            int index = java.util.Arrays.binarySearch(BUCKET_LIMITS, nanos);
            if (index < 0) index = -index - 1;
            histogram[index]++;
        }

        synchronized TimingSnapshot snapshot() {
            if (count == 0) return EMPTY_TIMING;
            long rank = count - count / 20;
            long cumulative = 0;
            long p95 = maximum;
            for (int i = 0; i < histogram.length; i++) {
                cumulative += histogram[i];
                if (cumulative >= rank) { p95 = Math.min(maximum, BUCKET_LIMITS[i]); break; }
            }
            return new TimingSnapshot(count, total, total / (double) count, maximum, p95);
        }
    }

    private record RuntimeSample(long atNanos, long gcCount, long gcMillis, long heapBytes) {
        static RuntimeSample read(long now) {
            long count = 0, millis = 0;
            for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                count += Math.max(0, gc.getCollectionCount());
                millis += Math.max(0, gc.getCollectionTime());
            }
            return new RuntimeSample(now, count, millis,
                    ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        }
    }

    private static final class Window {
        final long startedAtEpochMillis = System.currentTimeMillis();
        final RuntimeSample baseline = RuntimeSample.read(System.nanoTime());
        final long startNanos = System.nanoTime();
        final long deadlineNanos;
        final ConcurrentHashMap<String, Timing> timings = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();
        final Timing frames = new Timing();
        final AtomicLong lastFrame = new AtomicLong();
        final LongAdder dropped = new LongAdder();
        volatile RuntimeSample runtime = baseline;
        volatile boolean stopping;
        volatile Snapshot finished;
        volatile ScheduledFuture<?> expiry;

        Window(int seconds) { deadlineNanos = startNanos + seconds * 1_000_000_000L; }

        void cancelExpiry() {
            ScheduledFuture<?> pending = expiry;
            if (pending != null) pending.cancel(false);
        }

        Timing timing(String key) {
            if (!validKey(key)) { dropped.increment(); return null; }
            Timing found = timings.get(key);
            if (found != null) return found;
            synchronized (timings) {
                found = timings.get(key);
                if (found != null) return found;
                if (timings.size() >= MAX_TIMINGS) { dropped.increment(); return null; }
                found = new Timing();
                timings.put(key, found);
                return found;
            }
        }

        LongAdder counter(String key) {
            if (!validKey(key)) { dropped.increment(); return null; }
            LongAdder found = counters.get(key);
            if (found != null) return found;
            synchronized (counters) {
                found = counters.get(key);
                if (found != null) return found;
                if (counters.size() >= MAX_COUNTERS) { dropped.increment(); return null; }
                found = new LongAdder();
                counters.put(key, found);
                return found;
            }
        }

        synchronized Snapshot finish(long now) {
            if (finished != null) return finished;
            stopping = true;
            cancelExpiry();
            runtime = RuntimeSample.read(now);
            finished = capture(now, false);
            return finished;
        }

        Snapshot capture(long now, boolean active) {
            RuntimeSample sample = runtime;
            // HUD consumers may poll frequently; management beans are sampled at most once a second.
            if (now - sample.atNanos() >= 1_000_000_000L) {
                synchronized (this) {
                    sample = runtime;
                    if (now - sample.atNanos() >= 1_000_000_000L) runtime = sample = RuntimeSample.read(now);
                }
            }
            Map<String, TimingSnapshot> timingValues = new TreeMap<>();
            timings.forEach((key, value) -> timingValues.put(key, value.snapshot()));
            Map<String, Long> counterValues = new TreeMap<>();
            counters.forEach((key, value) -> counterValues.put(key, value.sum()));
            long elapsed = Math.max(0, Math.min(now - startNanos, deadlineNanos - startNanos));
            return new Snapshot(active, startedAtEpochMillis, elapsed / 1_000_000L,
                    active ? Math.max(0, deadlineNanos - now) / 1_000_000L : 0,
                    timingValues, counterValues, frames.snapshot(),
                    Math.max(0, sample.gcCount() - baseline.gcCount()),
                    Math.max(0, sample.gcMillis() - baseline.gcMillis()), sample.heapBytes(), dropped.sum());
        }
    }
}
