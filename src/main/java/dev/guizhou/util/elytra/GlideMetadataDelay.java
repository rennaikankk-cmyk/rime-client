package dev.guizhou.util.elytra;

/** Main-thread, bounded last-value buffer for only the fall-flying bit.
 * Newer server true supersedes a buffered false; unrelated flag bits always apply immediately. */
public final class GlideMetadataDelay {
    public static final int GLIDING = 0x80;
    private long deadline = Long.MIN_VALUE;

    public int accept(int current, int incoming, long tick, int sinceStart, int delay, boolean eligible) {
        if (!eligible || delay <= 0 || sinceStart < 0 || sinceStart >= delay
                || (incoming & GLIDING) != 0 || (current & GLIDING) == 0) {
            reset();
            return incoming;
        }
        // Repeated start commands must never extend an already pending server update indefinitely.
        long due = tick + delay - sinceStart;
        deadline = deadline == Long.MIN_VALUE ? due : Math.min(deadline, due);
        if (tick >= deadline) { reset(); return incoming; }
        return incoming | GLIDING;
    }

    public boolean pending() { return deadline != Long.MIN_VALUE; }
    public boolean due(long tick) { return pending() && tick >= deadline; }
    public void reset() { deadline = Long.MIN_VALUE; }
}
