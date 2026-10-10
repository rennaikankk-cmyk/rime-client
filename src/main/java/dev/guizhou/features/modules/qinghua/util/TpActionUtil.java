package dev.guizhou.features.modules.qinghua.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class TpActionUtil {
    public static final List<Block> SHIFT_BLOCKS = List.of(
            Blocks.CHEST,
            Blocks.TRAPPED_CHEST,
            Blocks.ENDER_CHEST,
            Blocks.SHULKER_BOX,
            Blocks.WHITE_SHULKER_BOX,
            Blocks.ORANGE_SHULKER_BOX,
            Blocks.MAGENTA_SHULKER_BOX,
            Blocks.LIGHT_BLUE_SHULKER_BOX,
            Blocks.YELLOW_SHULKER_BOX,
            Blocks.LIME_SHULKER_BOX,
            Blocks.PINK_SHULKER_BOX,
            Blocks.GRAY_SHULKER_BOX,
            Blocks.LIGHT_GRAY_SHULKER_BOX,
            Blocks.CYAN_SHULKER_BOX,
            Blocks.PURPLE_SHULKER_BOX,
            Blocks.BLUE_SHULKER_BOX,
            Blocks.BROWN_SHULKER_BOX,
            Blocks.GREEN_SHULKER_BOX,
            Blocks.RED_SHULKER_BOX,
            Blocks.BLACK_SHULKER_BOX
    );
    public static final CopyOnWriteArrayList<BlockPos> placedPos = new CopyOnWriteArrayList<>();

    private TpActionUtil() {}

    public static boolean isSafeBlock(BlockPos pos) {
        Level world = Minecraft.getInstance().level;
        if (world == null) return false;
        BlockState state = world.getBlockState(pos);
        return state.isAir() || state.canBeReplaced();
    }

    public static boolean canTpPlaceAt(BlockPos pos, boolean ignoreEntity) {
        Level world = Minecraft.getInstance().level;
        if (world == null) return false;
        BlockState state = world.getBlockState(pos);
        if (!state.isAir() && !state.canBeReplaced()) return false;
        if (state.is(Blocks.BEDROCK)) return false;
        if (!ignoreEntity && hasEntity(pos, false)) return false;
        return pos.getY() >= world.getMinY() && pos.getY() <= world.getMaxY();
    }

    public static boolean hasEntity(BlockPos pos, boolean ignorePlayer) {
        Level world = Minecraft.getInstance().level;
        if (world == null) return false;
        AABB box = new AABB(pos);
        try {
            List<Entity> entities = world.getEntities((Entity) null, box, ex -> !ignorePlayer || !(ex instanceof Player));
            return !entities.isEmpty();
        } catch (ConcurrentModificationException e) {
            return false;
        }
    }

    public static boolean hasEntity(AABB box, boolean ignorePlayer) {
        Level world = Minecraft.getInstance().level;
        if (world == null) return false;
        try {
            List<Entity> entities = world.getEntities((Entity) null, box, ex -> !ignorePlayer || !(ex instanceof Player));
            return !entities.isEmpty();
        } catch (ConcurrentModificationException e) {
            return false;
        }
    }

    public static boolean canReplace(BlockPos pos) {
        Level world = Minecraft.getInstance().level;
        return world != null && world.getBlockState(pos).canBeReplaced();
    }

    public static void clickAt(BlockPos pos, InteractionHand hand, Vec3 hitVec) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.connection == null) return;
        Vec3 clamped = getVec3d(pos, hitVec);
        Direction side = getTpPlaceSide(pos, hitVec);
        BlockHitResult hit = new BlockHitResult(clamped, side, pos, false);
        player.connection.send(new ServerboundUseItemOnPacket(hand, hit, 0));
    }

    public static void clickAt(BlockPos pos, Direction direction, InteractionHand hand) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.connection == null) return;
        Vec3 hitVec = new Vec3(
                pos.getX() + 0.5 + direction.getStepX() * 0.5,
                pos.getY() + 0.5 + direction.getStepY() * 0.5,
                pos.getZ() + 0.5 + direction.getStepZ() * 0.5
        );
        BlockHitResult hit = new BlockHitResult(hitVec, direction, pos, false);
        player.connection.send(new ServerboundUseItemOnPacket(hand, hit, 0));
    }

    public static void doPlaceAt(BlockPos pos, InteractionHand hand, Vec3 hitVec) {
        clickAt(pos, hand, hitVec);
    }

    public static void doAttackEntity(Entity entity) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.connection == null) return;
        player.connection.send(ServerboundInteractPacket.createAttackPacket(entity, player.isShiftKeyDown()));
    }

    public static Direction getTpPlaceSide(BlockPos pos, Vec3 viewPos) {
        if (viewPos.y > pos.getY() + 0.5) return Direction.DOWN;
        if (viewPos.y < pos.getY() - 0.5) return Direction.UP;
        double dx = viewPos.x - (pos.getX() + 0.5);
        double dz = viewPos.z - (pos.getZ() + 0.5);
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0.0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0.0 ? Direction.SOUTH : Direction.NORTH;
    }

    public static Vec3 getVec3d(BlockPos pos, Vec3 viewPos) {
        return new Vec3(
                Mth.clamp(viewPos.x, (double) pos.getX(), (double) (pos.getX() + 1)),
                Mth.clamp(viewPos.y, (double) pos.getY(), (double) (pos.getY() + 1)),
                Mth.clamp(viewPos.z, (double) pos.getZ(), (double) (pos.getZ() + 1))
        );
    }

    public static boolean canHit(Vec3 viewPos, BlockPos pos, double range) {
        Vec3 closest = getClosest(viewPos, new AABB(pos));
        return closest.distanceToSqr(viewPos) <= range * range;
    }

    public static boolean canHit(Vec3 viewPos, AABB box, double range) {
        Vec3 closest = getClosest(viewPos, box);
        return closest.distanceToSqr(viewPos) <= range * range;
    }

    public static Vec3 getClosest(Vec3 pos, AABB box) {
        return new Vec3(
                Mth.clamp(pos.x, box.minX, box.maxX),
                Mth.clamp(pos.y, box.minY, box.maxY),
                Mth.clamp(pos.z, box.minZ, box.maxZ)
        );
    }

    public static Vec3 getEyesPos(Vec3 playerPos) {
        return playerPos.add(0.0, 1.62, 0.0);
    }
}
