package dev.guizhou.manager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Placement coordinates are always relative to the block reported as clicked. */
final class PlacementGeometry {
    private PlacementGeometry() {}

    /** Retains the existing 0.05 inset for compatibility; vanilla permits face boundaries. */
    static Vec3 faceCenter(BlockPos clicked, Direction face) {
        return Vec3.atCenterOf(clicked).add(
                face.getStepX() * 0.45, face.getStepY() * 0.45, face.getStepZ() * 0.45);
    }

    static BlockHitResult neighbourHit(BlockPos target, Direction neighbourSide) {
        BlockPos clicked = target.relative(neighbourSide).immutable();
        Direction face = neighbourSide.getOpposite();
        return new BlockHitResult(faceCenter(clicked, face), face, clicked, false);
    }
}
