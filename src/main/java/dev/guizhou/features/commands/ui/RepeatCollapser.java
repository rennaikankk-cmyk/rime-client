package dev.guizhou.features.commands.ui;

/**
 * Decides which chat line a new message replaces.
 * <ul>
 *   <li>Keyed status lines always replace their previous line in place and never show a counter.</li>
 *   <li>Unkeyed lines that repeat the immediately preceding unkeyed line within the window
 *       replace it and carry a "×n" counter; anything else starts a new line.</li>
 * </ul>
 */
public final class RepeatCollapser {
    public record Decision(String key, int count, boolean replace) {}

    private final long windowMs;
    private String lastFingerprint;
    private String lastKey;
    private long lastAt;
    private int lastCount;
    private long serial;

    public RepeatCollapser(long windowMs) {
        this.windowMs = windowMs;
    }

    public synchronized Decision offer(String key, String fingerprint, long nowMs) {
        if (key != null) {
            // A keyed line lands at the bottom of chat, so the previous unkeyed line is no longer adjacent.
            lastFingerprint = null;
            return new Decision(key, 1, true);
        }
        if (fingerprint.equals(lastFingerprint) && nowMs - lastAt <= windowMs && nowMs >= lastAt) {
            lastAt = nowMs;
            lastCount++;
            return new Decision(lastKey, lastCount, true);
        }
        lastFingerprint = fingerprint;
        lastAt = nowMs;
        lastCount = 1;
        lastKey = "repeat#" + (++serial);
        return new Decision(lastKey, 1, false);
    }

    public synchronized void reset() {
        lastFingerprint = null;
        lastKey = null;
        lastCount = 0;
    }
}
