package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;

/**
 * Close-range pass-through direction retention, ported from SlimefunHelper SpearAura's crossing
 * branch in a bounded form: once the target has fallen behind the player's real horizontal
 * movement, the near-follow request keeps flying the crossing direction instead of flipping
 * 180° back at the target. Only the horizontal component is redirected — the request's vertical
 * component (terrain lanes, dive limits) is untouched, unlike the upstream full-vector negate.
 *
 * <p>The retention is scoped to one target and one pass: it ends when the target is ahead of
 * the movement again (dot above the dead zone), after {@code maxHoldTicks}, or when the target
 * changes. A dot dead zone keeps perpendicular grazes from flipping the choice every tick.
 * Experimental candidate behind a toggle; no hit-rate claim is made without live A/B testing.
 */
public final class SpearPassThroughHeading {

    /** Below this horizontal speed there is no momentum worth keeping (0.02 blocks/tick). */
    private static final double MIN_MOVEMENT_SQR = 4.0E-4;

    private int targetEntityId = -1;
    private Vec3 retainedXZ;
    private int retainedSince;

    /**
     * @param targetEntityId identity scope of the retention
     * @param request        the near-follow request toward the target (magnitude preserved)
     * @param realMovement   the player's real motion this tick (only its horizontal part is used)
     * @param deadzone       normalized dot dead zone around zero (e.g. 0.2)
     */
    public Vec3 apply(int targetEntityId, Vec3 request, Vec3 realMovement, int tick,
                      int maxHoldTicks, double deadzone) {
        if (targetEntityId != this.targetEntityId) {
            this.targetEntityId = targetEntityId;
            retainedXZ = null;
        }
        double mx = realMovement.x, mz = realMovement.z;
        double moveLenSq = mx * mx + mz * mz;
        if (moveLenSq < MIN_MOVEMENT_SQR) return request;
        double lx = request.x, lz = request.z;
        double lookLenSq = lx * lx + lz * lz;
        if (lookLenSq < 1.0E-9) return request; // vertical request: the horizontal choice is undefined
        double dot = (lx * mx + lz * mz) / Math.sqrt(lookLenSq * moveLenSq);

        if (retainedXZ != null) {
            if (dot > deadzone || tick - retainedSince >= maxHoldTicks) {
                retainedXZ = null; // the pass completed: target ahead again, or held long enough
                return request;
            }
            return withHorizontal(request, retainedXZ);
        }
        if (dot < -deadzone) {
            double moveLen = Math.sqrt(moveLenSq);
            retainedXZ = new Vec3(mx / moveLen, 0.0, mz / moveLen);
            retainedSince = tick;
            return withHorizontal(request, retainedXZ);
        }
        return request; // dead zone: no lateral commitment either way
    }

    private static Vec3 withHorizontal(Vec3 request, Vec3 unitXZ) {
        double lookLen = Math.sqrt(request.x * request.x + request.z * request.z);
        return new Vec3(unitXZ.x * lookLen, request.y, unitXZ.z * lookLen);
    }

    public boolean retaining() {
        return retainedXZ != null;
    }

    public void reset() {
        targetEntityId = -1;
        retainedXZ = null;
        retainedSince = 0;
    }
}
