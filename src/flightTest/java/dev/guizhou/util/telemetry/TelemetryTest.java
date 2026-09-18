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
        System.out.println("Telemetry checks passed: privacy allowlist, capability parsing/expiry, byte bounds, priority retention.");
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
        check(queue.count() == 0 && queue.bytes() == 0, "Opt-out must clear entire queue");
    }

    private static void priorityRetention() {
        TelemetryBuffer queue = new TelemetryBuffer();
        queue.offer(event("correction", "important"));
        for (int i = 0; i < 1000; i++) queue.offer(event("attack_intent", "a".repeat(4096)));
        queue.offer(event("session_end", "close"));
        JsonObject batch = queue.batch(new JsonObject());
        String json = batch.toString();
        check(json.contains("important") && json.contains("session_end"), "Combat burst discarded correction or session close");
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
