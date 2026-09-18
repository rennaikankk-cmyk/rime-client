package dev.guizhou.util.telemetry;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/** Bounded, lossy memory queue: failures never create a disk backlog. */
public final class TelemetryBuffer {
    public static final int MAX_EVENT_BYTES = 24 * 1024;
    public static final int MAX_QUEUE_BYTES = 512 * 1024;
    public static final int MAX_BATCH_BYTES = 64 * 1024;
    private record Entry(JsonObject value, int bytes, boolean priority) {}
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private int bytes;

    public synchronized boolean offer(JsonObject event) {
        int length = size(event);
        if (length > MAX_EVENT_BYTES) return false;
        while (!entries.isEmpty() && (entries.size() >= 256 || bytes + length > MAX_QUEUE_BYTES)) {
            Entry expendable = entries.stream().filter(entry -> !entry.priority()).findFirst().orElse(entries.peekFirst());
            entries.remove(expendable);
            bytes -= expendable.bytes();
        }
        String type = event.has("type") ? event.get("type").getAsString() : "";
        boolean priority = switch (type) {
            case "correction", "session_start", "session_end", "probe_result", "totem", "death" -> true;
            default -> false;
        };
        entries.addLast(new Entry(event.deepCopy(), length, priority));
        bytes += length;
        return true;
    }

    public synchronized JsonObject batch(JsonObject envelope) {
        JsonObject body = envelope.deepCopy();
        JsonArray events = new JsonArray();
        body.add("events", events);
        int size = size(body);
        // Diagnostic windows and the session close are retained during sustained combat bursts.
        for (boolean priority : new boolean[]{true, false}) {
            var iterator = entries.iterator();
            while (iterator.hasNext() && events.size() < 128) {
                Entry entry = iterator.next();
                if (entry.priority() != priority || size + entry.bytes() + 1 > MAX_BATCH_BYTES) continue;
                iterator.remove();
                bytes -= entry.bytes();
                size += entry.bytes() + 1;
                events.add(entry.value());
            }
        }
        return body;
    }

    public synchronized void clear() { entries.clear(); bytes = 0; }
    public synchronized boolean isEmpty() { return entries.isEmpty(); }
    public synchronized int bytes() { return bytes; }
    public synchronized int count() { return entries.size(); }
    public static int size(JsonObject object) {
        return object.toString().getBytes(StandardCharsets.UTF_8).length;
    }
}
