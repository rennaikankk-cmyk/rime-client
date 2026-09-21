package dev.guizhou.manager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

/** Placement coordinates are always relative to the block reported as clicked. */
public final class PlacementGeometry {
    private PlacementGeometry() {}

    /** Retains the existing 0.05 inset for compatibility; vanilla permits face boundaries. */
    public static Vec3 faceCenter(BlockPos clicked, Direction face) {
        return Vec3.atCenterOf(clicked).add(
                face.getStepX() * 0.45, face.getStepY() * 0.45, face.getStepZ() * 0.45);
    }

    public static BlockHitResult neighbourHit(BlockPos target, Direction neighbourSide) {
        BlockPos clicked = target.relative(neighbourSide).immutable();
        Direction face = neighbourSide.getOpposite();
        return new BlockHitResult(faceCenter(clicked, face), face, clicked, false);
    }

    /** Nearest inset point on a face of the actual outline (beds/slabs are not full cubes). */
    public static Vec3 facePoint(AABB bounds, Direction face, Vec3 eye) {
        double ix = Math.min(0.05, bounds.getXsize() * 0.25);
        double iy = Math.min(0.05, bounds.getYsize() * 0.25);
        double iz = Math.min(0.05, bounds.getZsize() * 0.25);
        double x = Math.clamp(eye.x, bounds.minX + ix, bounds.maxX - ix);
        double y = Math.clamp(eye.y, bounds.minY + iy, bounds.maxY - iy);
        double z = Math.clamp(eye.z, bounds.minZ + iz, bounds.maxZ - iz);
        return switch (face) {
            case UP -> new Vec3(x, bounds.maxY - iy, z);
            case DOWN -> new Vec3(x, bounds.minY + iy, z);
            case EAST -> new Vec3(bounds.maxX - ix, y, z);
            case WEST -> new Vec3(bounds.minX + ix, y, z);
            case SOUTH -> new Vec3(x, y, bounds.maxZ - iz);
            case NORTH -> new Vec3(x, y, bounds.minZ + iz);
        };
    }

    public static boolean validCursor(BlockHitResult hit) {
        if (hit == null) return false;
        Vec3 p = hit.getLocation().subtract(Vec3.atLowerCornerOf(hit.getBlockPos()));
        return Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z)
                && p.x >= 0 && p.x <= 1 && p.y >= 0 && p.y <= 1 && p.z >= 0 && p.z <= 1;
    }

    public static boolean withinRange(Vec3 eye, Vec3 hit, double range) {
        return eye != null && hit != null && Double.isFinite(range) && range >= 0
                && eye.distanceToSqr(hit) <= range * range;
    }
}
