package dev.guizhou.features.modules.world;

/** Tick-based retry accounting. Rejected sends never consume an attempt. */
public final class MiningRetryWindow {
    private int attempts;
    private int age;

    public void submitted() { attempts++; age = 0; }
    public void tick() { if (attempts > 0 && age < Integer.MAX_VALUE) age++; }
    public boolean ready(int delay) { return attempts == 0 || age >= Math.max(1, delay); }
    public boolean exhausted(int limit) { return attempts >= Math.max(1, limit); }
    public int age() { return age; }
    public void reset() { attempts = 0; age = 0; }
}
