package dev.guizhou.manager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class PlacementGeometryTest {
    public static void main(String[] args) {
        BlockPos[] targets = {
                BlockPos.ZERO, new BlockPos(-17, -64, -33),
                new BlockPos(29_999_980, 319, -29_999_980)
        };
        int cases = 0;
        for (BlockPos target : targets) {
            for (Direction side : Direction.values()) {
                BlockHitResult hit = PlacementGeometry.neighbourHit(target, side);
                check(hit.getBlockPos().equals(target.relative(side)), "wrong support block");
                check(hit.getBlockPos().relative(hit.getDirection()).equals(target),
                        "clicked face does not place into the target cell");
                check(!hit.isInside(), "external placement marked inside");
                Vec3 local = hit.getLocation().subtract(Vec3.atLowerCornerOf(hit.getBlockPos()));
                check(local.x > 0 && local.x < 1 && local.y > 0 && local.y < 1
                        && local.z > 0 && local.z < 1, "cursor outside clicked block: " + local);
                double normalCoordinate = hit.getDirection().getAxis().choose(local.x, local.y, local.z);
                check(Math.abs(normalCoordinate - (hit.getDirection().getAxisDirection()
                        == Direction.AxisDirection.POSITIVE ? 0.95 : 0.05)) < 1e-7,
                        "cursor inset belongs to the wrong face");
                // The old target-relative construction must fail this same bounds invariant.
                Vec3 old = PlacementGeometry.faceCenter(target, side)
                        .subtract(Vec3.atLowerCornerOf(hit.getBlockPos()));
                check(old.x < 0 || old.x > 1 || old.y < 0 || old.y > 1 || old.z < 0 || old.z > 1,
                        "regression fixture no longer reproduces the original bug");
                cases++;
            }
        }
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(10, 20, 30);
        BlockHitResult stored = PlacementGeometry.neighbourHit(mutable, Direction.EAST);
        mutable.set(100, 200, 300);
        check(stored.getBlockPos().equals(new BlockPos(11, 20, 30)), "hit changed with scan cursor");
        System.out.println("Placement geometry checks passed: " + cases
                + " direction/coordinate cases, original-bug fixtures and mutable-position isolation.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
