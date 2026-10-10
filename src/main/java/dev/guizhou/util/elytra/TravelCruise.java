package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;

/** Bounded, fuel-aware travel steering; yields completely to combat and manual input. */
public final class TravelCruise {
    public enum Mode { Off, FireworkGlide, Pitch40, Pitch40Optimized }
    private Mode mode = Mode.Off;
    private double anchorY;
    private float yaw;
    private boolean climbing, rocketRequested;
    private int phaseTicks, lastTick = Integer.MIN_VALUE;
    private Vec3 lastLook = Vec3.ZERO;

    public void reset() {
        mode = Mode.Off; lastTick = Integer.MIN_VALUE; lastLook = Vec3.ZERO;
        climbing = false; rocketRequested = false; phaseTicks = 0;
    }

    public boolean rocketRequested() { return rocketRequested; }

    public Vec3 select(Mode next, int tick, double y, float cameraYaw, Vec3 velocity,
                       double window, boolean allowed, boolean lockHeading) {
        return select(next, tick, y, cameraYaw, velocity, window, allowed, lockHeading, true);
    }

    public Vec3 select(Mode next, int tick, double y, float cameraYaw, Vec3 velocity,
                       double window, boolean allowed, boolean lockHeading, boolean fuel) {
        if (!allowed || next == null || next == Mode.Off || !Double.isFinite(y)
                || !Float.isFinite(cameraYaw) || velocity == null || !Double.isFinite(velocity.lengthSqr())) {
            reset(); return Vec3.ZERO;
        }
        if (mode != next || tick < lastTick || (long) tick - lastTick > 5) {
            mode = next; anchorY = y; yaw = cameraYaw;
            // A newly opened wing has no altitude/speed reserve for an immediate dive.
            climbing = next == Mode.FireworkGlide || velocity.horizontalDistance() < .8;
            phaseTicks = 0;
        }
        if (tick == lastTick) return lastLook;
        lastTick = tick;
        if (!lockHeading) yaw = cameraYaw;
        double span = Double.isFinite(window) ? Math.clamp(window, 24, 400) : 80;
        double floor = anchorY - span * .5, ceiling = anchorY + span * .5;
        boolean low = y + Math.min(0, velocity.y) * 8 <= floor;
        boolean high = y + Math.max(0, velocity.y) * 4 >= ceiling;
        boolean nextClimb = climbing;
        if (low) nextClimb = true;
        else if (high) nextClimb = false;
        else if (mode == Mode.Pitch40Optimized && phaseTicks >= Pitch40Waveform.length(climbing))
            nextClimb = !climbing;
        if (nextClimb != climbing) { climbing = nextClimb; phaseTicks = 0; }
        float pitch;
        if (!fuel) {
            // Keep a shallow glide while the existing terrain/landing controller chooses a safe route.
            // Never dive following the table when there is no rocket for the next pull-up.
            pitch = -3;
            phaseTicks = 0;
        } else if (mode == Mode.Pitch40Optimized) {
            pitch = Pitch40Waveform.pitch(climbing, phaseTicks++);
            if (low) pitch = Math.min(pitch, -45);
            if (high) pitch = Math.max(pitch, 5);
        } else if (mode == Mode.Pitch40) {
            pitch = climbing ? (float) Math.clamp(-65 + phaseTicks++ * .55, -65, -20) : 40;
        } else {
            pitch = climbing ? -55 : 5;
        }
        rocketRequested = fuel && climbing && pitch < -5;
        lastLook = Vec3.directionFromRotation(pitch, yaw);
        return lastLook;
    }

    /** Preserve the travel pitch when using the existing GZBoost envelope and protocol gate. */
    public CoupledCruise.Selection selection(boolean dualView) {
        if (mode == Mode.Off || lastLook.lengthSqr() < .5) return null;
        int vertical = lastLook.y > .05 ? 1 : lastLook.y < -.05 ? -1 : 0;
        Vec3 box = dualView ? Vec3.directionFromRotation(0, yaw) : null;
        return new CoupledCruise.Selection(lastLook, vertical > 0 ? CoupledCruise.Phase.CLIMB
                : vertical < 0 ? CoupledCruise.Phase.DESCEND : CoupledCruise.Phase.LEVEL,
                vertical, false, true, dualView, box);
    }
}
