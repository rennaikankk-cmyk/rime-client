package dev.guizhou.util.elytra;

/** Remembers only whether an existing flight needs restarting after foreign ownership ends. */
public final class ArmorFlightHandoff {
    private boolean airborne, resume;
    public void observe(boolean gliding, boolean grounded, boolean foreign) {
        if (grounded) { reset(); return; }
        if (gliding) airborne = true;
        if (foreign && airborne) resume = true;
        if (!foreign && gliding) resume = false;
    }
    public boolean wasAirborne() { return airborne; }
    public boolean resumeRequested() { return resume; }
    public void reset() { airborne = false; resume = false; }
}
