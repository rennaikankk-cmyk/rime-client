package dev.guizhou.features.modules.qinghua.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;

/** Port of eternal BlockUtil — placement/safety checks unchanged (Mojang names). */
public final class BlockUtil {
    public static final List<BlockPos> placedPos = new ArrayList<>();
    private static final Minecraft mc = Minecraft.getInstance();

    private BlockUtil() {}

    public static boolean isSafeBlock(BlockPos pos) {
        if (mc.level == null) return false;
        return mc.level.getBlockState(pos).isAir()
                && mc.level.getBlockState(pos.above()).isAir()
                && !mc.level.getBlockState(pos).is(Blocks.BEDROCK);
    }

    public static BlockPos findVclipHole(BlockPos pos, double vclip) {
        if (vclip > 0.0) {
            for (int i = (int) vclip + pos.getY(); i >= pos.getY(); i--) {
                BlockPos checkPos = new BlockPos(pos.getX(), i, pos.getZ());
                if (isSafeBlock(checkPos) && isSafeBlock(checkPos.above())) return checkPos;
            }
        }
        if (vclip < 0.0) {
            for (int ix = (int) vclip + pos.getY(); ix <= pos.getY(); ix++) {
                BlockPos checkPos = new BlockPos(pos.getX(), ix, pos.getZ());
                if (isSafeBlock(checkPos) && isSafeBlock(checkPos.above())) return checkPos;
            }
        }
        return pos;
    }

    public static Direction getClickSide(BlockPos pos) {
        Direction side = Direction.UP;
        double minDistance = Double.MAX_VALUE;
        if (mc.player == null) return side;
        for (Direction i : Direction.values()) {
            double disSq = mc.player.getEyePosition().distanceToSqr(pos.relative(i).getCenter());
            if (disSq < minDistance) {
                side = i;
                minDistance = disSq;
            }
        }
        return side;
    }

    public static void clickBlock(BlockPos pos, Direction side, boolean rotate, boolean packet) {
        if (mc.player == null || mc.gameMode == null) return;
        Vec3 vec = pos.getCenter().add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
        BlockHitResult result = new BlockHitResult(vec, side, pos, false);
        if (packet && mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, result, 0));
        } else {
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, result);
        }
    }

    public static void clickBlock(BlockPos pos, Direction side, boolean rotate) {
        clickBlock(pos, side, rotate, false);
    }

    public static boolean canReplace(BlockPos pos) {
        return mc.level != null && mc.level.getBlockState(pos).canBeReplaced();
    }

    public static boolean clientCanPlace(BlockPos pos, boolean ignoreEntity) {
        return mc.level != null && mc.level.getBlockState(pos).canBeReplaced();
    }

    public static Direction getPlaceSide(BlockPos pos, double reachDistance) {
        double minDistance = Double.MAX_VALUE;
        Direction side = null;
        if (mc.player == null) return null;
        for (Direction i : Direction.values()) {
            if (canClick(pos.relative(i)) && !canReplace(pos.relative(i)) && isStrictDirection(pos.relative(i), i.getOpposite())) {
                double vecDis = mc.player.getEyePosition().distanceToSqr(
                        pos.getCenter().add(i.getStepX() * 0.5, i.getStepY() * 0.5, i.getStepZ() * 0.5));
                if (!(Math.sqrt(vecDis) > reachDistance) && !(vecDis > minDistance)) {
                    side = i;
                    minDistance = vecDis;
                }
            }
        }
        return side;
    }

    public static boolean canPlace(BlockPos pos, double reachDistance, boolean ignoreCrystal) {
        if (getPlaceSide(pos, reachDistance) == null) return false;
        if (!canReplace(pos)) return false;
        return !hasEntity(new AABB(pos), ignoreCrystal);
    }

    public static boolean canClick(BlockPos pos) {
        if (mc.player == null) return false;
        double reach = mc.player.blockInteractionRange();
        return mc.player.distanceToSqr(pos.getCenter()) <= reach * reach;
    }

    public static boolean isStrictDirection(BlockPos pos, Direction dir) {
        return mc.level != null && mc.level.getBlockState(pos).isSolidRender();
    }

    public static Direction getPlaceSide(BlockPos pos) {
        double minDistance = Double.MAX_VALUE;
        Direction side = null;
        if (mc.player == null) return null;
        for (Direction i : Direction.values()) {
            if (canClick(pos.relative(i)) && !canReplace(pos.relative(i)) && isStrictDirection(pos.relative(i), i.getOpposite())) {
                double vecDis = mc.player.getEyePosition().distanceToSqr(
                        pos.getCenter().add(i.getStepX() * 0.5, i.getStepY() * 0.5, i.getStepZ() * 0.5));
                if (!(vecDis > minDistance)) {
                    side = i;
                    minDistance = vecDis;
                }
            }
        }
        return side;
    }

    public static void placeBlock(BlockPos pos, boolean rotate) {
        Direction side = getPlaceSide(pos);
        if (side != null && mc.player != null) {
            placedPos.add(pos);
            clickBlock(pos.relative(side), side.getOpposite(), rotate);
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    public static void airPlaceBlock(BlockPos pos, boolean rotate) {
        if (mc.player == null) return;
        placedPos.add(pos);
        clickBlock(pos.above(), Direction.DOWN, rotate, true);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    public static boolean hasEntity(AABB box, boolean ignoreCrystal) {
        if (mc.level == null) return false;
        try {
            for (Entity entity : mc.level.getEntities((Entity) null, box, e -> true)) {
                if (entity != null
                        && entity.getBoundingBox().intersects(box)
                        && entity.isAlive()
                        && !(entity instanceof ItemEntity)
                        && !(entity instanceof ExperienceOrb)
                        && !(entity instanceof ThrownExperienceBottle)
                        && !(entity instanceof AbstractArrow)
                        && (!ignoreCrystal || !(entity instanceof EndCrystal))) {
                    return true;
                }
            }
            return false;
        } catch (ConcurrentModificationException e) {
            return false;
        }
    }

    public static List<BlockPos> getSphere(float range) {
        List<BlockPos> result = new ArrayList<>();
        if (mc.player == null || mc.level == null) return result;
        BlockPos center = mc.player.blockPosition();
        int r = (int) Math.ceil(range);
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    if (mc.player.distanceToSqr(pos.getCenter()) <= range * range) result.add(pos);
                }
            }
        }
        return result;
    }

    public static List<BlockEntity> getTileEntities() {
        List<BlockEntity> result = new ArrayList<>();
        if (mc.level == null || mc.player == null) return result;
        int view = Math.max(2, mc.options.getEffectiveRenderDistance());
        int cx = mc.player.chunkPosition().x;
        int cz = mc.player.chunkPosition().z;
        for (int x = cx - view; x <= cx + view; x++) {
            for (int z = cz - view; z <= cz + view; z++) {
                if (!mc.level.hasChunk(x, z)) continue;
                LevelChunk chunk = mc.level.getChunk(x, z);
                result.addAll(chunk.getBlockEntities().values());
            }
        }
        return result;
    }
}
