package dev.guizhou.util.inventory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Bounded local submissions awaiting a cumulative protocol ACK, not proof of action success. */
public final class PendingSequences<T> {
    private record Entry<T>(T value, long tick) {}
    private final Map<Integer, Entry<T>> pending = new LinkedHashMap<>();
    private int acknowledged = -1;
    public void submitted(int sequence, T value, long tick) {
        if (sequence < 0 || sequence <= acknowledged) return;
        expire(tick);
        pending.put(sequence, new Entry<>(value, tick));
        while (pending.size() > 64) pending.remove(pending.keySet().iterator().next());
    }
    public void acknowledge(int sequence) {
        if (sequence < acknowledged) return;
        acknowledged = sequence;
        pending.keySet().removeIf(s -> s <= sequence);
    }
    public void expire(long tick) { pending.values().removeIf(e -> tick < e.tick || tick - e.tick >= 40); }
    public boolean any(Predicate<T> predicate) { return pending.values().stream().anyMatch(e -> predicate.test(e.value)); }
    public T get(int sequence) { var e = pending.get(sequence); return e == null ? null : e.value; }
    public int size() { return pending.size(); }
    public void clear() { pending.clear(); acknowledged = -1; }
}
