package dev.guizhou.util.render;

import java.util.HashMap;
import java.util.Map;

/** Frame-rate independent easing toward per-key targets. Render thread only. */
public final class Anim {
    private static final class Entry {
        float value;
        long time;
        Entry(float value, long time) { this.value = value; this.time = time; }
    }

    private final Map<Object, Entry> values = new HashMap<>();

    /** Exponentially approaches target; larger speed settles faster (about 1/speed seconds). */
    public float to(Object key, float target, float speed) {
        long now = System.nanoTime();
        Entry e = values.get(key);
        if (e == null) {
            values.put(key, new Entry(target, now));
            return target;
        }
        float dt = Math.min(0.1f, (now - e.time) / 1e9f);
        e.time = now;
        e.value += (target - e.value) * (1f - (float) Math.exp(-speed * dt));
        if (Math.abs(target - e.value) < 1e-3f) e.value = target;
        return e.value;
    }

    /** Starts a key at a value, e.g. zero before an entrance animation. */
    public void set(Object key, float value) {
        values.put(key, new Entry(value, System.nanoTime()));
    }

    public void clear() {
        values.clear();
    }
}
