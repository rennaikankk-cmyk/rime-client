package dev.guizhou.util.combat;

/** Shared by aura controllers. TPS scales the duration once, and each client tick advances once. */
public final class AttackCooldown {
    private int lastTick = Integer.MIN_VALUE;
    private double remaining;

    public void advance(int tick) {
        if (lastTick != Integer.MIN_VALUE && tick > lastTick) remaining = Math.max(0, remaining - (tick - lastTick));
        lastTick = tick;
    }

    public void start(double weaponTicks, double tps, double delay) {
        if (!Double.isFinite(tps)) tps = 1;
        if (!Double.isFinite(weaponTicks) || weaponTicks <= 0 || !Double.isFinite(delay)) {
            remaining = 0;
            return;
        }
        remaining = weaponTicks * 20 / Math.clamp(tps, 1, 20) * Math.clamp(delay, 0, 1);
    }

    public boolean active() { return remaining > 0; }
    public void reset() { lastTick = Integer.MIN_VALUE; remaining = 0; }
}
