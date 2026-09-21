package dev.guizhou.util.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Keeps existing power in place; a powered piston must be installed after the crystal. */
public final class PistonPowerSequence {
    public enum Action { BREAK, WAIT, READY, ABORT }
    public enum Plan { PLACE_POWER, KEEP_POWER, RECYCLE_PISTON, REJECT }
    private PistonPowerSequence() {}

    public static Plan plan(boolean existingPower, boolean reuse, boolean existingPiston, int gap, boolean canRecycle) {
        if (!existingPower) return gap == 1 ? Plan.PLACE_POWER : Plan.REJECT;
        // A crystal spans two blocks horizontally. An adjacent new piston would collide with it.
        if (!reuse || gap != 2) return Plan.REJECT;
        return !existingPiston ? Plan.KEEP_POWER : canRecycle ? Plan.RECYCLE_PISTON : Plan.REJECT;
    }

    public static Action resetPiston(boolean matchingPiston, boolean air, boolean headAir, boolean powerPresent) {
        if (!powerPresent) return Action.ABORT;
        if (matchingPiston) return Action.BREAK;
        if (!air) return Action.ABORT;
        return headAir ? Action.READY : Action.WAIT;
    }

    public static boolean clearForPiston(BlockPos crystal, BlockPos piston) {
        Vec3 center = Vec3.atBottomCenterOf(crystal);
        return !new AABB(center.x - 1, center.y, center.z - 1,
                center.x + 1, center.y + 2, center.z + 1).intersects(new AABB(piston));
    }

    /** Gap-two head sweep overlaps the two-wide crystal by half a block, plus vanilla's 0.01 nudge. */
    public static Vec3 predictedPush(BlockPos crystal, Direction outward) {
        return Vec3.atBottomCenterOf(crystal).subtract(Vec3.atLowerCornerOf(outward.getUnitVec3i()).scale(0.51));
    }

    public static boolean pushed(Vec3 start, Vec3 current, Direction outward) {
        double displacement = (start.x - current.x) * outward.getStepX()
                + (start.z - current.z) * outward.getStepZ();
        return Double.isFinite(displacement) && displacement >= 0.25;
    }
}
