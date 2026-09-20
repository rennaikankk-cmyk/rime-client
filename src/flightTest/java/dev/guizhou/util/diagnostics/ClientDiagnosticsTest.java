package dev.guizhou.util.diagnostics;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public final class ClientDiagnosticsTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        try {
            disabledAndBounds();
            distribution();
            concurrentCapture();
            staleTimerAndBoundedKeys();
            deadlineAndLocalExport();
            submissionRate();
            System.out.println("ClientDiagnosticsTest passed: " + checks + " checks");
        } finally {
            ClientDiagnostics.clear();
        }
    }

    private static void disabledAndBounds() {
        ClientDiagnostics.clear();
        check(!ClientDiagnostics.isCollecting() && ClientDiagnostics.startTimer() == 0, "Default is fully inactive");
        ClientDiagnostics.count("ignored", 1);
        ClientDiagnostics.recordFrame();
        ClientDiagnostics.endTimer("ignored", 0);
        check(ClientDiagnostics.snapshot().counters().isEmpty(), "Off-state calls create no counters");
        for (int invalid : new int[]{0, -1, 61, Integer.MAX_VALUE}) {
            boolean rejected = false;
            try { ClientDiagnostics.start(invalid); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Capture duration is bounded: " + invalid);
        }
    }

    private static void distribution() {
        ClientDiagnostics.Timing timing = new ClientDiagnostics.Timing();
        for (int i = 0; i < 95; i++) timing.add(100);
        for (int i = 0; i < 5; i++) timing.add(10_000);
        var snapshot = timing.snapshot();
        check(snapshot.count() == 100 && snapshot.totalNanos() == 59_500, "Distribution retains totals and event count");
        check(snapshot.meanNanos() == 595 && snapshot.maxNanos() == 10_000, "Mean and peak are distinct from P95");
        check(snapshot.p95Nanos() >= 100 && snapshot.p95Nanos() <= 125, "P95 upper bound excludes top five percent outliers");
        ClientDiagnostics.Timing empty = new ClientDiagnostics.Timing();
        empty.add(-1);
        check(empty.snapshot().count() == 0, "Negative durations are rejected");
    }

    private static void concurrentCapture() throws Exception {
        ClientDiagnostics.start(60);
        int workers = 6, iterations = 5_000;
        CountDownLatch ready = new CountDownLatch(workers), start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            Thread thread = new Thread(() -> {
                ready.countDown();
                try { start.await(); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                for (int j = 0; j < iterations; j++) {
                    long timer = ClientDiagnostics.startTimer();
                    ClientDiagnostics.count("combat.requests", 1);
                    ClientDiagnostics.endTimer("combat.worker", timer);
                }
            });
            threads.add(thread);
            thread.start();
        }
        ready.await();
        start.countDown();
        for (Thread thread : threads) thread.join();
        ClientDiagnostics.recordFrame();
        ClientDiagnostics.recordFrame();
        var live = ClientDiagnostics.snapshot();
        check(live.active(), "Sampling remains active within its window");
        check(live.counters().get("combat.requests") == (long) workers * iterations, "Concurrent counters lose no updates");
        check(live.timings().get("combat.worker").count() == (long) workers * iterations, "Concurrent timing histogram loses no updates");
        check(live.frameTiming().count() == 1, "First rendered frame establishes baseline, not a bogus interval");
        boolean immutable = false;
        try { live.counters().put("mutated", 1L); }
        catch (UnsupportedOperationException expected) { immutable = true; }
        check(immutable, "Snapshots are immutable");
        long pending = ClientDiagnostics.startTimer();
        ClientDiagnostics.stop();
        var frozen = ClientDiagnostics.snapshot();
        ClientDiagnostics.count("combat.requests", 20);
        ClientDiagnostics.endTimer("combat.worker", pending);
        ClientDiagnostics.recordFrame();
        check(!ClientDiagnostics.isCollecting() && ClientDiagnostics.startTimer() == 0, "Stopped window has inactive fast path");
        check(frozen.equals(ClientDiagnostics.snapshot()), "Late callbacks cannot change retained results");
        check(frozen.gcCollections() >= 0 && frozen.gcMillis() >= 0 && frozen.heapUsedBytes() >= 0, "Local JMX observations stay nonnegative");
    }

    private static void staleTimerAndBoundedKeys() {
        ClientDiagnostics.start(60);
        long stale = ClientDiagnostics.startTimer();
        long oldIdentity = ClientDiagnostics.recordingId();
        ClientDiagnostics.start(60);
        ClientDiagnostics.endTimer("previous.window", stale);
        check(ClientDiagnostics.recordingId() != oldIdentity && ClientDiagnostics.snapshot().timings().isEmpty(), "Restart fences pending work from older windows");
        for (int i = 0; i < ClientDiagnostics.MAX_TIMINGS + 10; i++) {
            long timer = ClientDiagnostics.startTimer();
            ClientDiagnostics.endTimer("timing." + i, timer);
        }
        for (int i = 0; i < ClientDiagnostics.MAX_COUNTERS + 10; i++) ClientDiagnostics.count("count." + i, 1);
        ClientDiagnostics.count("x".repeat(129), 1);
        var snapshot = ClientDiagnostics.snapshot();
        check(snapshot.timings().size() == ClientDiagnostics.MAX_TIMINGS, "Distinct timing keys are bounded");
        check(snapshot.counters().size() == ClientDiagnostics.MAX_COUNTERS, "Distinct counter keys are bounded");
        check(snapshot.droppedKeys() == 21, "Rejected new-key samples are observable");
        ClientDiagnostics.count("count.0", 1);
        check(ClientDiagnostics.snapshot().counters().get("count.0") == 2, "Existing counters still work at capacity");
    }

    private static void deadlineAndLocalExport() throws Exception {
        ClientDiagnostics.start(1);
        ClientDiagnostics.count("quoted.\"key\\line\n", 2);
        Thread.sleep(1150);
        check(!ClientDiagnostics.isCollecting(), "Automatic deadline stops even without rendering");
        var frozen = ClientDiagnostics.snapshot();
        check(!frozen.active() && frozen.durationMillis() == 1000 && frozen.remainingMillis() == 0, "Window cannot exceed requested duration");
        ClientDiagnostics.count("late", 1);
        check(ClientDiagnostics.snapshot().equals(frozen), "Expired data stays frozen");
        var directory = Files.createTempDirectory("gzw-local-diagnostics-test-");
        var file = directory.resolve("capture.json");
        try {
            ClientDiagnostics.export(file);
            String json = Files.readString(file);
            check(json.contains("\"scope\": \"local_only\"") && json.contains("do_not_sum_nested_scopes"), "Export identifies local inclusive semantics");
            check(json.contains("quoted.\\\"key\\\\line\\u000a"), "JSON escapes dynamic keys correctly");
            check(!json.contains("server_hash") && !json.contains("install_id") && !json.contains("token"), "Export has no telemetry identity/capability fields");
            boolean protectedFile = false;
            try { ClientDiagnostics.export(file); }
            catch (FileAlreadyExistsException expected) { protectedFile = true; }
            check(protectedFile && Files.readString(file).equals(json), "Export never overwrites another capture");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    private static void submissionRate() throws Exception {
        SubmissionRate rate = new SubmissionRate();
        rate.reset(0);
        for (int i = 0; i < 20; i++) rate.submitted(i * 10_000_000L);
        check(rate.perSecond(1_000_000_000L) == 20, "Local submitted packets counted once per sampled second");
        for (int i = 0; i < 40; i++) rate.submitted(1_100_000_000L);
        check(rate.perSecond(3_000_000_000L) == 20, "Delayed render normalizes count over actual elapsed time");
        check(rate.perSecond(4_000_000_000L) == 0, "Idle window clears old packet rate");
        rate.reset(10_000_000_000L);
        check(rate.perSecond(10_100_000_000L) == 0, "Toggle/reconnect-style reset drops stale count");
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
