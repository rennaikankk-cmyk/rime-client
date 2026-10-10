package dev.guizhou.features.modules.combat;

import java.util.HashMap;
import java.util.Map;

/** Client-thread-only block destruction records, keyed by breaker rather than position. */
public final class MiningBreakTracker {
    private record Entry(long position, long expiresAt, boolean friend) {}
    private final Map<Integer, Entry> entries = new HashMap<>();

    public void update(int breaker, long position, int progress, boolean friend, long now, long ttl) {
        if (progress < 0 || progress > 9) {
            Entry current = entries.get(breaker);
            if (current != null && current.position == position) entries.remove(breaker);
        }
        else entries.put(breaker, new Entry(position, now + ttl, friend));
    }

    public boolean isBreaking(long position, boolean friendsOnly, long now) {
        return entries.values().stream().anyMatch(e -> e.position == position && now < e.expiresAt
                && (!friendsOnly || e.friend));
    }

    public void prune(long now) { entries.values().removeIf(e -> now >= e.expiresAt); }
    public void clear() { entries.clear(); }
}
