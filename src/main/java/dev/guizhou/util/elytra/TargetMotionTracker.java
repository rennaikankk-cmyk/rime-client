package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;

/** Two consecutive measured velocities without retaining per-tick arrays. */
public final class TargetMotionTracker {
    private static final int MAX_SAMPLE_GAP = 5;
    private static final double MAX_SAMPLE_SPEED = 20.0;

    private Vec3 position;
    private Vec3 velocity = Vec3.ZERO;
    private Vec3 previousVelocity = Vec3.ZERO;
    private int tick;
    private int velocityCount;
    private boolean grounded;

    public void clear() {
        position = null;
        velocity = Vec3.ZERO;
        previousVelocity = Vec3.ZERO;
        velocityCount = 0;
    }

    public void sample(Vec3 nextPosition, int nextTick, boolean onGround) {
        if (!isFinite(nextPosition)) {
            clear();
            return;
        }
        long elapsed = (long) nextTick - tick;
        if (position != null && elapsed == 0 && grounded == onGround) return;
        if (position == null || elapsed <= 0 || elapsed > MAX_SAMPLE_GAP || grounded != onGround) {
            clear();
        } else {
            Vec3 nextVelocity = nextPosition.subtract(position).scale(1.0 / elapsed);
            if (!isFinite(nextVelocity) || nextVelocity.lengthSqr() > MAX_SAMPLE_SPEED * MAX_SAMPLE_SPEED) {
                clear();
            } else {
                previousVelocity = velocity;
                velocity = nextVelocity;
                velocityCount = Math.min(2, velocityCount + 1);
            }
        }
        position = nextPosition;
        tick = nextTick;
        grounded = onGround;
    }

    public boolean hasTwoVelocities() {
        return velocityCount >= 2;
    }

    public Vec3 previousVelocity() {
        return previousVelocity;
    }

    public Vec3 velocity() {
        return velocity;
    }

    public Vec3 predictionVelocity(Vec3 fallback) {
        if (velocityCount > 0) return velocity;
        if (!isFinite(fallback) || fallback.lengthSqr() > MAX_SAMPLE_SPEED * MAX_SAMPLE_SPEED) return Vec3.ZERO;
        return fallback;
    }

    private static boolean isFinite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
