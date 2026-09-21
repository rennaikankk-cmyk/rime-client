package dev.guizhou.util.render.placement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
public final class PlacementShapes {
    private PlacementShapes() {}
    public static AABB bed(BlockPos foot,BlockPos head) {
        return new AABB(Math.min(foot.getX(),head.getX()),foot.getY(),Math.min(foot.getZ(),head.getZ()),
                Math.max(foot.getX(),head.getX())+1,foot.getY()+9.0/16,Math.max(foot.getZ(),head.getZ())+1);
    }
}
