package dev.guizhou.util.world;

import dev.guizhou.util.traits.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/** Client-thread adapter from the loaded level to {@link HoleClassifier}. */
public final class HoleScanner implements Util {
    /** Vanilla obsidian is 1200; ender chests (600) still survive crystals. */
    private static final float RESISTANT = 600f;

    private HoleScanner() {}

    public static HoleClassifier.Kind kind(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.is(Blocks.BEDROCK)) return HoleClassifier.Kind.BEDROCK;
        if (state.getCollisionShape(mc.level, pos).isEmpty()) {
            return state.getFluidState().isEmpty() ? HoleClassifier.Kind.PASSABLE : HoleClassifier.Kind.OTHER;
        }
        return state.getBlock().getExplosionResistance() >= RESISTANT ? HoleClassifier.Kind.RESISTANT
                : HoleClassifier.Kind.OTHER;
    }

    public static List<HoleClassifier.Hole> scan(BlockPos center, int range, int verticalRange, boolean doubles) {
        List<HoleClassifier.Hole> holes = new ArrayList<>();
        if (mc.level == null) return holes;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        HoleClassifier.Grid grid = (x, y, z) -> kind(cursor.set(x, y, z));
        int minY = mc.level.getMinY() + 1;
        for (int x = center.getX() - range; x <= center.getX() + range; x++) {
            for (int z = center.getZ() - range; z <= center.getZ() + range; z++) {
                for (int y = Math.max(minY, center.getY() - verticalRange); y <= center.getY() + verticalRange; y++) {
                    HoleClassifier.Hole hole = HoleClassifier.single(grid, x, y, z);
                    if (hole == null && doubles) hole = HoleClassifier.doubleHole(grid, x, y, z);
                    if (hole != null) holes.add(hole);
                }
            }
        }
        return holes;
    }

    public static AABB box(HoleClassifier.Hole hole, double height) {
        double maxX = hole.x() + 1 + Math.max(0, hole.dx());
        double maxZ = hole.z() + 1 + Math.max(0, hole.dz());
        return new AABB(hole.x(), hole.y(), hole.z(), maxX, hole.y() + height, maxZ);
    }

    public static List<BlockPos> cells(HoleClassifier.Hole hole) {
        BlockPos first = new BlockPos(hole.x(), hole.y(), hole.z());
        if (!hole.isDouble()) return List.of(first);
        return List.of(first, first.offset(hole.dx(), 0, hole.dz()));
    }
}
