package dev.guizhou.util.telemetry;

import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public final class TelemetryTest {
    private enum Mode { NORMAL }

    public static void main(String[] args) {
        privacy();
        capabilities();
        queueBounds();
        priorityRetention();
        rejectedTypePurgedSelectively();
        receiptStatus();
        asynchronousReceiptOrder();
        System.out.println("Telemetry checks passed: privacy, capabilities, queue bounds, priority retention, receiver status and async ordering.");
    }

    private static void privacy() {
        JsonObject input = new JsonObject();
        input.addProperty("server", "private.example.net:25565");
        input.addProperty("player", "private_player");
        input.addProperty("chat", "private message");
        input.addProperty("dig", 4.5);
        input.addProperty("attack", -1);
        input.addProperty("move", Double.NaN);
        JsonObject result = TelemetryPrivacy.probe(input);
        check(result.size() == 2 && result.get("dig").getAsDouble() == 4.5, "Legacy input leaked or lost numeric results");
        JsonObject settings = new JsonObject();
        TelemetryPrivacy.setting(settings, "DisplayName", "private_player");
        TelemetryPrivacy.setting(settings, "Items", new Object());
        TelemetryPrivacy.setting(settings, "Range", 4.5);
        TelemetryPrivacy.setting(settings, "Enabled", true);
        TelemetryPrivacy.setting(settings, "Mode", Mode.NORMAL);
        TelemetryPrivacy.setting(settings, "NaN", Double.NaN);
        TelemetryPrivacy.setting(settings, "Infinity", Double.POSITIVE_INFINITY);
        TelemetryPrivacy.setting(settings, "Huge", 60_000_001);
        TelemetryPrivacy.setting(settings, "bad name", true);
        check(settings.size() == 3, "Settings allowlist must exclude strings, objects and out-of-contract numbers");
        String hash = TelemetryPrivacy.serverHash(" Server.Example:25565 ");
        check(hash.matches("[0-9a-f]{64}"), "Server hash must match collector contract");
        check(hash.equals(TelemetryPrivacy.serverHash("server.example:25565")), "Canonical server addresses must correlate");
        check(!hash.equals(TelemetryPrivacy.serverHash("other.example:25565")), "Different servers must not collide");
    }

    private static void capabilities() {
        String token = "t1." + "a".repeat(32) + ".2000000000." + "b".repeat(64);
        TelemetryCapability valid = capability("{\"token\":\"" + token + "\"}");
        check(valid != null && valid.token().equals(token), "Root-issued four-part token must load");
        check(valid.validAt(1_999_999_999L) && !valid.validAt(2_000_000_000L), "Expiry boundary must disable upload");
        check(TelemetryCapability.read(null) == null, "Developer jar without resource must not upload");
        check(capability("{\"token\":\"t1.payload.signature\"}") == null, "Three-part token is invalid");
        check(capability("{\"token\":\"" + token.toUpperCase() + "\"}") == null, "Invalid token character case accepted");
        check(capability("{\"token\":\"" + token + "\\r\\nInjected: true\"}") == null, "Token must never permit header injection");
        check(capability("{\"token\":\"" + token + "\",\"padding\":\"" + "a".repeat(4096) + "\"}") == null,
                "Oversize capability resource must be refused");
        check(capability("not json") == null, "Malformed capability must fail closed");
    }

    private static TelemetryCapability capability(String json) {
        return TelemetryCapability.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static void queueBounds() {
        TelemetryBuffer queue = new TelemetryBuffer();
        check(!queue.offer(event("attack_intent", "a".repeat(TelemetryBuffer.MAX_EVENT_BYTES))), "Oversize event accepted");
        for (int i = 0; i < 1000; i++) {
            check(queue.offer(event("attack_intent", "a".repeat(4096))), "Valid event rejected");
            check(queue.bytes() <= TelemetryBuffer.MAX_QUEUE_BYTES && queue.count() <= 256, "Unbounded queue");
        }
        JsonObject envelope = new JsonObject();
        envelope.addProperty("build", "a".repeat(100));
        while (!queue.isEmpty()) {
            JsonObject batch = queue.batch(envelope);
            check(TelemetryBuffer.size(batch) <= TelemetryBuffer.MAX_BATCH_BYTES, "Batch exceeds exact UTF-8 limit");
            check(batch.getAsJsonArray("events").size() <= 128 && !batch.getAsJsonArray("events").isEmpty(), "Batch made no progress");
        }
        check(queue.bytes() == 0, "Byte accounting failed after draining");
        JsonObject mutable = event("attack_intent", "before");
        queue.offer(mutable);
        mutable.addProperty("secret", "must_not_appear");
        check(!queue.batch(envelope).toString().contains("must_not_appear"), "Producer mutation changed queued data");
        queue.offer(event("attack_intent", "test"));
        queue.clear();
        check(queue.count() == 0 && queue.bytes() == 0, "Session reset must clear the entire queue");
    }

    private static void priorityRetention() {
        TelemetryBuffer queue = new TelemetryBuffer();
        queue.offer(event("correction", "important"));
        for (int i = 0; i < 1000; i++) queue.offer(event("attack_intent", "a".repeat(4096)));
        queue.offer(event("session_end", "close"));
        queue.offer(event("flight_summary", "window"));
        JsonObject batch = queue.batch(new JsonObject());
        String json = batch.toString();
        check(json.contains("important") && json.contains("session_end") && json.contains("window"),
                "Combat burst discarded correction, session close or flight summary");
    }

    /** A rejected event type is purged selectively; older events survive untouched. */
    private static void rejectedTypePurgedSelectively() {
        TelemetryBuffer queue = new TelemetryBuffer();
        queue.offer(event("flight_summary", "first"));
        queue.offer(event("movement_sample", "kept"));
        queue.offer(event("flight_summary", "second"));
        queue.offer(event("correction", "kept-priority"));
        int removed = queue.removeType("flight_summary");
        check(removed == 2 && queue.count() == 2, "Only the rejected type leaves the queue");
        String json = queue.batch(new JsonObject()).toString();
        check(json.contains("kept") && json.contains("kept-priority") && !json.contains("first") && !json.contains("second"),
                "Purged summaries must not reach a later batch while old events do");
        check(queue.removeType("flight_summary") == 0, "A second purge is a no-op");
    }

    private static void receiptStatus() {
        TelemetryUploadStatus status = new TelemetryUploadStatus();
        var initial = status.snapshot();
        check(initial.outcome() == TelemetryUploadStatus.Outcome.NEVER_SENT && initial.attempts() == 0
                && initial.lastConfirmedAt() == 0, "A fresh client must not claim a historical confirmation");
        long attempt = status.started(7, 1000);
        check(status.snapshot().outcome() == TelemetryUploadStatus.Outcome.SENDING
                && status.snapshot().lastBatchEvents() == 7, "Sending status records the request event count");
        status.completed(attempt, 202, null, 1200);
        var confirmed = status.snapshot();
        check(confirmed.outcome() == TelemetryUploadStatus.Outcome.RECEIVER_CONFIRMED
                && confirmed.confirmedBatches() == 1 && confirmed.lastConfirmedAt() == 1200
                && confirmed.lastConfirmedHttp() == 202 && confirmed.lastConfirmedBatchEvents() == 7,
                "HTTP 202 is exposed as a receiver confirmation, retaining its time and batch size");

        attempt = status.started(3, 2000);
        status.completed(attempt, 401, null, 2200);
        var rejected = status.snapshot();
        check(rejected.outcome() == TelemetryUploadStatus.Outcome.FAILED
                && rejected.lastFailure() == TelemetryUploadStatus.Failure.HTTP && rejected.lastFailureHttp() == 401
                && rejected.failedBatches() == 1 && rejected.consecutiveFailures() == 1,
                "An HTTP rejection is a visible failure and does not count as received");
        check(rejected.lastConfirmedAt() == 1200 && rejected.lastConfirmedBatchEvents() == 7,
                "A later rejection must not erase the earlier receiver confirmation");
        check(initial.attempts() == 0 && confirmed.failedBatches() == 0,
                "Published snapshots must remain immutable across asynchronous completions");

        attempt = status.started(9, 3000);
        status.completed(attempt, 0, new java.util.concurrent.CompletionException(
                new java.net.http.HttpTimeoutException("secret credential must not be retained")), 3500);
        check(status.snapshot().lastFailure() == TelemetryUploadStatus.Failure.TIMEOUT
                && status.snapshot().consecutiveFailures() == 2 && !status.snapshot().toString().contains("secret"),
                "Wrapped timeouts use a fixed safe category without leaking exception text");
        attempt = status.started(4, 4000);
        status.completed(attempt, 0, new java.io.IOException("private endpoint"), 4500);
        check(status.snapshot().lastFailure() == TelemetryUploadStatus.Failure.NETWORK
                && !status.snapshot().toString().contains("private"), "Network error details stay out of diagnostics");
        attempt = status.started(1, 5000);
        status.completed(attempt, 204, null, 5500);
        check(status.snapshot().consecutiveFailures() == 0 && status.snapshot().confirmedBatches() == 2
                && status.snapshot().lastFailureAt() == 4500,
                "Recovery resets consecutive failures but preserves the last observed failure");
        attempt = status.started(1, 6000);
        status.completed(attempt, 0, null, 6100);
        check(status.snapshot().lastFailure() == TelemetryUploadStatus.Failure.INVALID_RESPONSE,
                "No response is never reported as receiver acceptance");
    }

    private static void asynchronousReceiptOrder() {
        TelemetryUploadStatus status = new TelemetryUploadStatus();
        long old = status.started(2, 1000);
        long current = status.started(4, 2000);
        status.completed(old, 200, null, 2100);
        check(status.snapshot().outcome() == TelemetryUploadStatus.Outcome.SENDING
                && status.snapshot().confirmedBatches() == 0, "A stale completion cannot overwrite an active attempt");
        status.completed(current, 429, null, 2200);
        var once = status.snapshot();
        status.completed(current, 200, null, 2300);
        check(once.equals(status.snapshot()), "A duplicate completion cannot change outcome or double-count a batch");
        current = status.started(256, -1);
        status.completed(current, 999, null, -1);
        check(status.snapshot().lastBatchEvents() == 128 && status.snapshot().lastAttemptAt() == 0
                && status.snapshot().lastFailureHttp() == 0, "Display metadata is bounded and invalid HTTP codes fail closed");
    }

    private static JsonObject event(String type, String text) {
        JsonObject object = new JsonObject();
        object.addProperty("type", type);
        object.addProperty("data", text);
        return object;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
