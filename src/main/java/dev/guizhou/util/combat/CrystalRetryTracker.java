package dev.guizhou.util.combat;

import java.util.LinkedHashMap;
import java.util.Map;

/** Client-thread attempt tracking. Only actual local submissions consume an attempt. */
public final class CrystalRetryTracker {
    private static final int MAX_TRACKED = 512;
    private final Map<Integer, Attempt> attempts = new LinkedHashMap<>();

    public boolean canAttempt(int id, long now, long interval, int limit, long resetDelay) {
        Attempt attempt = attempts.get(id);
        if (attempt == null) return true;
        long age = now - attempt.lastSent;
        if (age < 0) return true; // a replaced session/clock must never permanently lock a target
        if (age >= Math.max(interval, resetDelay)) return true;
        return attempt.count < Math.max(1, limit) && age >= interval;
    }

    public void markSent(int id, long now, long resetDelay) {
        Attempt old = attempts.remove(id);
        int count = old == null || now < old.lastSent || now - old.lastSent >= resetDelay ? 1 : old.count + 1;
        if (attempts.size() >= MAX_TRACKED) attempts.remove(attempts.keySet().iterator().next());
        attempts.put(id, new Attempt(now, count));
    }

    public void removed(int id) { attempts.remove(id); }
    public void clear() { attempts.clear(); }
    public int size() { return attempts.size(); }

    public void expire(long now, long lifetime) {
        attempts.values().removeIf(a -> now < a.lastSent || now - a.lastSent >= lifetime);
    }

    private record Attempt(long lastSent, int count) {}
}
