package dev.guizhou.util;

import dev.guizhou.util.traits.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle;
import net.minecraft.world.phys.AABB;

public final class PlaceUtil implements Util {

    private PlaceUtil() {
        throw new AssertionError();
    }

    public static int minCell(double boxMin) {
        return net.minecraft.util.Mth.floor(boxMin);
    }

    public static int maxCell(double boxMax) {
        return net.minecraft.util.Mth.ceil(boxMax) - 1;
    }

    public static boolean canPlace(BlockPos pos) {
        return canPlace(pos, 5.154);
    }

    public static boolean canPlace(BlockPos pos, double range) {
        if (pos == null || mc.player == null || mc.level == null) return false;
        if (mc.level.isOutsideBuildHeight(pos)) return false;
        if (!mc.level.getWorldBorder().isWithinBounds(pos)) return false;
        if (!mc.level.getBlockState(pos).canBeReplaced()) return false;
        // Broad-phase only. The manager checks the actual clicked face before submission.
        var eye = mc.player.getEyePosition();
        var closest = new net.minecraft.world.phys.Vec3(Math.clamp(eye.x, pos.getX(), pos.getX() + 1.0),
                Math.clamp(eye.y, pos.getY(), pos.getY() + 1.0), Math.clamp(eye.z, pos.getZ(), pos.getZ() + 1.0));
        if (!Double.isFinite(range) || range < 0 || closest.distanceToSqr(eye) > range * range) return false;
        if (mc.player.getBoundingBox().intersects(new AABB(pos))) return false;
        return noEntityCollision(pos);
    }

    public static boolean noEntityCollision(BlockPos pos) {
        for (Entity e : mc.level.getEntitiesOfClass(
                Entity.class,
                new AABB(pos),
                e -> !(e instanceof ItemEntity
                    || e instanceof ExperienceOrb
                    || e instanceof ThrownExperienceBottle
                    || e instanceof FireworkRocketEntity
                    || e == mc.player))) {
            return false;
        }
        return true;
    }
}
