package dev.guizhou.features.modules.qinghua.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.math.NumberUtils;

/**
 * Port of eternal TPUtil — packet step math unchanged.
 * Yarn ClientPlayerEntity/networkHandler → Mojang LocalPlayer/connection.
 */
public final class TPUtil {
    private static final Minecraft mc = Minecraft.getInstance();

    private TPUtil() {}

    public static void sendMovePacket(double x, double y, double z, boolean onGround) {
        LocalPlayer player = mc.player;
        if (player != null && player.connection != null) {
            boolean hc = player.horizontalCollision;
            player.connection.send(new ServerboundMovePlayerPacket.Pos(x, y, z, onGround, hc));
        }
    }

    public static Vec3 findVClipVecToMove(Vec3 fromVec, Vec3 toVec, double searchStep, boolean allowVoid) {
        Vec3 findVec = null;
        double step = 0.0;
        if (mc.level == null) {
            return null;
        }
        boolean isEnd = mc.level.dimensionType().hasCeiling();
        int voidY = isEnd ? 0 : -64;

        while (step <= 200.0) {
            Vec3 vec = new Vec3(fromVec.x, fromVec.y + step, fromVec.z);
            if ((!allowVoid || fromVec.y > voidY) && BlockUtil.isSafeBlock(BlockPos.containing(vec))) {
                return vec;
            }
            vec = new Vec3(fromVec.x, fromVec.y - step, fromVec.z);
            if ((!allowVoid || fromVec.y > voidY) && BlockUtil.isSafeBlock(BlockPos.containing(vec))) {
                return vec;
            }
            step += searchStep;
        }
        return findVec;
    }

    public static void doTp(Vec3 fromVec, Vec3 toVec, double moveDistance, boolean onGround) {
        doTp(fromVec.x, fromVec.y, fromVec.z, toVec.x, toVec.y, toVec.z, moveDistance, onGround);
    }

    public static void doTp(double fromX, double fromY, double fromZ, double toX, double toY, double toZ, double moveDistance, boolean onGround) {
        double squareDis = squaredDistance(fromX, fromY, fromZ, toX, toY, toZ);
        int step = (int) Math.ceil(Math.sqrt(squareDis) / moveDistance);
        for (int i = 1; i <= step; i++) {
            sendMovePacket(fromX, fromY, fromZ, onGround);
        }
        sendMovePacket(toX, toY, toZ, onGround);
    }

    private static double squaredDistance(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        return dx * dx + dy * dy + dz * dz;
    }

    public static void doTpMove(Vec3 fromVec, Vec3 toVec, double moveDistance, boolean back, Runnable action) {
        if (mc.player == null || mc.level == null || mc.player.connection == null) return;
        if (isBlinkVec(fromVec) && isBlinkVec(toVec)) {
            Vec3 a1 = findVClipVecToMove(fromVec, toVec);
            Vec3 b1 = findVClipVecToMove(toVec, fromVec);
            if (a1.equals(fromVec)) a1 = toVec;
            if (b1.equals(toVec)) b1 = fromVec;

            int a2 = (int) Math.ceil(fromVec.distanceTo(a1) / moveDistance);
            int b2 = (int) Math.ceil(fromVec.distanceTo(b1) / moveDistance);
            int c = (int) Math.ceil(fromVec.distanceTo(toVec) / moveDistance);
            int p = NumberUtils.max(a2, b2, c) - 1 + 4;

            for (int i = 1; i < p; i++) {
                sendMovePacket(fromVec.x, fromVec.y, fromVec.z, false);
            }
            sendMovePacket(a1.x, a1.y, a1.z, false);
            sendMovePacket(toVec.x, toVec.y, toVec.z, false);
            sendMovePacket(toVec.x, toVec.y, toVec.z, false);
            action.run();
            if (back) {
                sendMovePacket(toVec.x, toVec.y, toVec.z, false);
                sendMovePacket(b1.x, b1.y, b1.z, false);
                sendMovePacket(fromVec.x, fromVec.y, fromVec.z, false);
                mc.player.setPos(fromVec.x, fromVec.y, fromVec.z);
                sendMovePacket(fromVec.x, fromVec.y, fromVec.z, false);
            } else {
                mc.player.setPos(toVec.x, toVec.y, toVec.z);
            }
        }
    }

    public static Vec3 findVClipVecToMove(Vec3 fromVec, Vec3 toVec) {
        if (mc.level == null) return fromVec;
        for (double step = 0.0; step <= 200.0; step++) {
            Vec3 vecUp = new Vec3(fromVec.x, fromVec.y + step, fromVec.z);
            if (!(vecUp.y > mc.level.getMaxY())) {
                if (canTpThrough(vecUp, toVec)) return vecUp;
                Vec3 vecDown = new Vec3(fromVec.x, fromVec.y - step, fromVec.z);
                if (vecDown.y > mc.level.getMinY() && canTpThrough(vecDown, toVec)) return vecDown;
            }
        }
        return fromVec;
    }

    public static boolean canTpThrough(Vec3 fromVec, Vec3 toVec) {
        if (fromVec.x == toVec.x && fromVec.z == toVec.z) return true;
        for (double[] offset : getPlayerDoubles()) {
            Vec3 from = fromVec.add(offset[0], offset[1], offset[2]);
            Vec3 to = toVec.add(offset[0], offset[1], offset[2]);
            if (!canSee(from, to)) return false;
        }
        return true;
    }

    private static double[][] getPlayerDoubles() {
        if (mc.player == null) return new double[0][];
        AABB box = mc.player.getBoundingBox();
        double halfLx = box.getXsize() / 2.0 - 0.1;
        double Ly = box.getYsize() - 1.0E-4;
        double halfLz = box.getZsize() / 2.0 - 0.1;
        return new double[][]{
                {halfLx, 1.0E-4, halfLz}, {halfLx, 1.0E-4, -halfLz},
                {-halfLx, 1.0E-4, halfLz}, {-halfLx, 1.0E-4, -halfLz},
                {halfLx, 1.0, halfLz}, {halfLx, 1.0, -halfLz},
                {-halfLx, 1.0, halfLz}, {-halfLx, 1.0, -halfLz},
                {halfLx, Ly, halfLz}, {halfLx, Ly, -halfLz},
                {-halfLx, Ly, halfLz}, {-halfLx, Ly, -halfLz}
        };
    }

    public static boolean isBlinkVec(Vec3 vec) {
        if (mc.player == null || mc.level == null) return false;
        if (vec.y < mc.level.getMinY()) return false;
        AABB box = mc.player.getBoundingBox();
        double halfX = box.getXsize() / 2.0;
        double height = box.getYsize();
        double halfZ = box.getZsize() / 2.0;
        double[][] corners = {{halfX, 0.0, halfZ}, {halfX, 0.0, -halfZ}, {-halfX, 0.0, halfZ}, {-halfX, 0.0, -halfZ}};
        for (double[] corner : corners) {
            BlockPos feet = BlockPos.containing(vec.add(corner[0], 0.0, corner[2]));
            if (isCollisionBlock(feet)) return false;
            if (isCollisionBlock(feet.above())) return false;
            BlockPos head = BlockPos.containing(vec.add(corner[0], height, corner[2]));
            if (isCollisionBlock(head)) return false;
        }
        return true;
    }

    public static boolean isCollisionBlock(BlockPos pos) {
        return mc.level == null || !mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty();
    }

    public static boolean canSee(Vec3 from, Vec3 to) {
        if (mc.player == null || mc.level == null) return false;
        ClipContext ctx = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player);
        BlockHitResult hit = mc.level.clip(ctx);
        return hit == null || hit.getType() == HitResult.Type.MISS;
    }
}
