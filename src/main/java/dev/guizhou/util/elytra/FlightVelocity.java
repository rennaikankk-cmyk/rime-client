package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;

/**
 * Mutable flight-intent payload broadcast by GuangxiElytra every movement tick
 * (port of rennaikanksmp FlightVelocity). Behaviours (GuangxiElytraChase) may
 * overwrite the vector; the flight module sets whatever survives as the tick
 * velocity. Semantics: the vector's LENGTH is the desired speed — behaviours
 * encode "slow down" by returning a shorter vector, not by changing direction.
 */
public final class FlightVelocity {
    private double x, y, z;
    private final double maxVelocity;
    private boolean braking;
    private boolean avoiding;
    private boolean precision;
    private boolean directional;

    public FlightVelocity(Vec3 vec, double maxVelocity) {
        this(vec.x, vec.y, vec.z, maxVelocity);
    }

    public FlightVelocity(double x, double y, double z, double maxVelocity) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.maxVelocity = maxVelocity;
    }

    public Vec3 value() {
        return new Vec3(x, y, z);
    }

    public double maxVelocity() {
        return maxVelocity;
    }

    /** Preserve direction and requested speed at a narrow combat intercept. */
    public void requestPrecision() {
        precision = true;
        directional = true;
    }

    public boolean isPrecise() {
        return precision;
    }

    public void preserveDirection() {
        directional = true;
    }

    public boolean isDirectional() {
        return directional;
    }

    public void set(Vec3 vec) {
        this.x = vec.x;
        this.y = vec.y;
        this.z = vec.z;
        braking = false;
        avoiding = false;
    }

    public void requestAvoidance(Vec3 vec) {
        set(vec);
        avoiding = true;
    }

    public boolean isAvoiding() {
        return avoiding;
    }

    public void requestBraking() {
        set(Vec3.ZERO);
        braking = true;
        avoiding = true;
    }

    public boolean isBraking() {
        return braking;
    }
}
