package dev.guizhou.util.movement;

/** Bounded slot-resend window; packet bursts cannot consume more than one entry per tick. */
public final class NoSlowSpoofSession {
    private int slot = -1;
    private int startTick;
    private int lastSendTick;
    private int remaining;
    private int delay;
    private int interval;

    public void begin(int tick, int selectedSlot, int delayTicks, int packetCount, int intervalTicks) {
        slot = selectedSlot;
        startTick = tick;
        delay = Math.max(0, delayTicks);
        interval = Math.max(1, intervalTicks);
        remaining = Math.max(0, packetCount);
        lastSendTick = tick - interval;
    }

    public boolean poll(int tick, int selectedSlot, boolean consuming) {
        if (slot < 0 || selectedSlot != slot || tick < startTick) {
            reset();
            return false;
        }
        if (!consuming || remaining <= 0 || tick - startTick < delay
                || tick - lastSendTick < interval) return false;
        lastSendTick = tick;
        remaining--;
        return true;
    }

    public boolean active() { return slot >= 0; }

    public void reset() { slot = -1; remaining = 0; }
}
