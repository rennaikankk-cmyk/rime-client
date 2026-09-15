package dev.guizhou.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import static dev.guizhou.util.traits.Util.mc;

/**
 * Kinematic position prediction, ported from AlienV4's PredictUtil:
 * velocity from last-tick position delta (works for remote players whose
 * deltaMovement is not synced), then collision-checked tick simulation.
 */
public final class PredictUtil {

    private static final int SIMULATION = 5;
    private static final double STOP_EPSILON = 0.05;

    private PredictUtil() {}

    public static Vec3 predict(LivingEntity entity, int ticks, double maxMotionY) {
        if (ticks <= 0) return entity.position();

        // A player currently clipping into blocks (burrow/suffocate) cannot be extrapolated.
        if (!mc.level.noCollision(entity, entity.getBoundingBox())) return entity.position();

        double vx = entity.getX() - entity.xOld;
        double vy = entity.getY() - entity.yOld;
        double vz = entity.getZ() - entity.zOld;
        if (vy > maxMotionY) vy = maxMotionY;

        double mx = vx, my = vy, mz = vz;
        double x = entity.getX(), y = entity.getY(), z = entity.getZ();
        if (mx == 0.0 && my == 0.0 && mz == 0.0) return new Vec3(x, y, z);

        boolean onGround = entity.onGround();

        for (int t = 0; t < ticks; t++) {
            Vec3 last = new Vec3(x, y, z);
            boolean moved = false;
            boolean fell = false;
            boolean stopped = false;

            sim:
            for (int yt = SIMULATION; yt >= 0; yt--) {
                for (int xt = SIMULATION; xt >= 0; xt--) {
                    double fx = (double) xt / SIMULATION;
                    double fy = (double) yt / SIMULATION;
                    if (!canMove(entity, x + mx * fx, y + my * fy, z + mz * fx)) continue;

                    if (Math.abs(mx * fx) + Math.abs(mz * fx) + Math.abs(my * fy) <= STOP_EPSILON) {
                        stopped = true;
                        break sim;
                    }
                    moved = true;
                    x += mx * fx;
                    z += mz * fx;
                    if (yt > 0) {
                        y += my * fy;
                        fell = true;
                    }
                    break sim;
                }
            }

            if (stopped || !moved) return last;

            if (!onGround) {
                mx *= 0.99;
                mz *= 0.99;
                my = my * 0.99 - 0.05;
            }
            if (fell) continue;

            if (onGround) {
                // Walking input assumed to continue at the observed speed.
                mx = vx;
                mz = vz;
                my = 0.0;
            } else {
                my = 0.0;
            }
        }
        return new Vec3(x, y, z);
    }

    private static boolean canMove(LivingEntity entity, double x, double y, double z) {
        Vec3 pos = new Vec3(x, y, z);
        AABB box = entity.getDimensions(Pose.STANDING).makeBoundingBox(pos);
        if (mc.level.noCollision(entity, box)) return true;
        // AlienV4 tolerance: a target block overlapping the current box counts as passable.
        return new AABB(BlockPos.containing(pos)).intersects(entity.getBoundingBox());
    }
}
