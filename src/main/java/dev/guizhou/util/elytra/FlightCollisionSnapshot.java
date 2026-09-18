package dev.guizhou.util.elytra;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Immutable numeric collision data. Only capture reads the live client world. */
public final class FlightCollisionSnapshot implements FlightRoutePlanner.Clearance {
    public static final double MAX_RADIUS = 12.0;
    public static final int MAX_BLOCKS = 24_576;
    public static final int MAX_BOXES = 2_048;
    private static final long CAPTURE_BUDGET_NANOS = 4_000_000L;
    private static final ThreadLocal<CaptureScale> CAPTURE_SCALE = ThreadLocal.withInitial(CaptureScale::new);

    private final Vec3 origin;
    private final AABB body;
    private final AABB bounds;
    private final List<AABB> obstacles;

    /** localBody is the player's inflated collision box relative to origin. */
    public FlightCollisionSnapshot(Vec3 origin, AABB localBody, AABB bounds, List<AABB> obstacles) {
        if (!finite(origin) || !finite(localBody) || !finite(bounds)) throw new IllegalArgumentException("Invalid snapshot geometry");
        if (!fitsBounds(localBody, bounds)) {
            throw new IllegalArgumentException("Snapshot bounds cannot contain body");
        }
        this.origin = origin;
        this.body = localBody;
        this.bounds = bounds;
        this.obstacles = List.copyOf(Objects.requireNonNull(obstacles));
        if (this.obstacles.size() > MAX_BOXES || this.obstacles.stream().anyMatch(box -> !finite(box))) {
            throw new IllegalArgumentException("Invalid snapshot obstacles");
        }
    }

    /** Call on the client thread. A budget overrun leaves no partially trusted snapshot. */
    public static FlightCollisionSnapshot capture(LocalPlayer player, ClientLevel level, double radius) {
        if (player == null || level == null || player.level() != level
                || !Double.isFinite(radius) || radius < 1.0) return null;
        CaptureScale scale = CAPTURE_SCALE.get();
        FlightCollisionSnapshot result = captureAtRadius(player, level, Math.min(radius, scale.radius));
        if (result == null) {
            scale.radius = Math.max(3.0, scale.radius * 0.7);
            scale.successes = 0;
        } else if (++scale.successes >= 20) {
            scale.radius = Math.min(MAX_RADIUS, scale.radius + 1.0);
            scale.successes = 0;
        }
        return result;
    }

    private static FlightCollisionSnapshot captureAtRadius(LocalPlayer player, ClientLevel level, double radius) {
        long started = System.nanoTime();
        radius = Math.min(radius, MAX_RADIUS);
        Vec3 origin = player.position();
        AABB body = player.getBoundingBox().inflate(0.06, 0.02, 0.06).move(origin.scale(-1.0));
        var border = level.getWorldBorder();
        double minBoundX = Math.max(origin.x - radius, border.getMinX());
        double minBoundZ = Math.max(origin.z - radius, border.getMinZ());
        double maxBoundX = Math.min(origin.x + radius, border.getMaxX());
        double maxBoundZ = Math.min(origin.z + radius, border.getMaxZ());
        if (minBoundX >= maxBoundX || minBoundZ >= maxBoundZ) return null;
        AABB bounds = new AABB(minBoundX, origin.y - radius, minBoundZ,
                maxBoundX, origin.y + radius, maxBoundZ);
        if (!finite(bounds) || !fitsBounds(body, bounds)) return null;
        int minX = (int) Math.floor(bounds.minX) - 1;
        int minY = (int) Math.floor(bounds.minY) - 1;
        int minZ = (int) Math.floor(bounds.minZ) - 1;
        int maxX = (int) Math.floor(bounds.maxX) + 1;
        int maxY = (int) Math.floor(bounds.maxY) + 1;
        int maxZ = (int) Math.floor(bounds.maxZ) + 1;
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume <= 0 || volume > MAX_BLOCKS) return null;
        List<AABB> obstacles = new ArrayList<>();
        CollisionContext context = CollisionContext.of(player);
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        int visited = 0;
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                if (!level.hasChunkAt(new BlockPos(chunkX << 4, minY, chunkZ << 4))) {
                    obstacles.add(new AABB(chunkX << 4, bounds.minY, chunkZ << 4,
                            (chunkX + 1) << 4, bounds.maxY, (chunkZ + 1) << 4));
                    if (obstacles.size() > MAX_BOXES) return null;
                    continue;
                }
                for (int x = Math.max(minX, chunkX << 4); x <= Math.min(maxX, ((chunkX + 1) << 4) - 1); x++) {
                    for (int z = Math.max(minZ, chunkZ << 4); z <= Math.min(maxZ, ((chunkZ + 1) << 4) - 1); z++) {
                        int solidStart = Integer.MIN_VALUE;
                        for (int y = minY; y <= maxY; y++) {
                            if ((visited++ & 127) == 0 && System.nanoTime() - started > CAPTURE_BUDGET_NANOS) return null;
                            position.set(x, y, z);
                            var state = level.getBlockState(position);
                            List<AABB> boxes = state.isAir() ? List.of()
                                    : state.getCollisionShape(level, position, context).toAabbs();
                            if (boxes.size() == 1 && fullCube(boxes.getFirst())) {
                                if (solidStart == Integer.MIN_VALUE) solidStart = y;
                                continue;
                            }
                            if (solidStart != Integer.MIN_VALUE) {
                                addSolidColumn(obstacles, bounds, x, z, solidStart, y);
                                solidStart = Integer.MIN_VALUE;
                            }
                            for (AABB box : boxes) {
                                AABB worldBox = box.move(x, y, z);
                                if (worldBox.intersects(bounds)) obstacles.add(worldBox);
                            }
                            if (obstacles.size() > MAX_BOXES) return null;
                        }
                        if (solidStart != Integer.MIN_VALUE) addSolidColumn(obstacles, bounds, x, z, solidStart, maxY + 1);
                        if (obstacles.size() > MAX_BOXES) return null;
                    }
                }
            }
        }
        if (System.nanoTime() - started > CAPTURE_BUDGET_NANOS) return null;
        return new FlightCollisionSnapshot(origin, body, bounds, obstacles);
    }

    private static void addSolidColumn(List<AABB> obstacles, AABB bounds, int x, int z, int minY, int maxY) {
        AABB column = new AABB(x, minY, z, x + 1, maxY, z + 1);
        if (column.intersects(bounds)) obstacles.add(column);
    }

    private static boolean fullCube(AABB box) {
        return box.minX == 0.0 && box.minY == 0.0 && box.minZ == 0.0
                && box.maxX == 1.0 && box.maxY == 1.0 && box.maxZ == 1.0;
    }

    public Vec3 origin() { return origin; }

    public AABB bounds() { return bounds; }

    AABB navigationBounds() {
        return new AABB(bounds.minX - body.minX, bounds.minY - body.minY, bounds.minZ - body.minZ,
                bounds.maxX - body.maxX, bounds.maxY - body.maxY, bounds.maxZ - body.maxZ);
    }

    public boolean clearOffsets(Vec3 from, Vec3 to) {
        return finite(from) && finite(to) && clear(origin.add(from), origin.add(to));
    }

    @Override
    public boolean clear(Vec3 from, Vec3 to) {
        if (!finite(from) || !finite(to)) return false;
        AABB start = body.move(from);
        AABB end = body.move(to);
        if (!contains(start) || !contains(end)) return false;
        Vec3 delta = to.subtract(from);
        AABB swept = start.expandTowards(delta);
        for (AABB obstacle : obstacles) {
            if (!swept.intersects(obstacle)) continue;
            if (intersects(from, delta, obstacle)) return false;
        }
        return true;
    }

    private boolean contains(AABB box) {
        return box.minX >= bounds.minX && box.minY >= bounds.minY && box.minZ >= bounds.minZ
                && box.maxX <= bounds.maxX && box.maxY <= bounds.maxY && box.maxZ <= bounds.maxZ;
    }

    private boolean intersects(Vec3 from, Vec3 delta, AABB obstacle) {
        double enter = 0.0;
        double exit = 1.0;
        for (int axis = 0; axis < 3; axis++) {
            double start = axis == 0 ? from.x : axis == 1 ? from.y : from.z;
            double direction = axis == 0 ? delta.x : axis == 1 ? delta.y : delta.z;
            double min = axis == 0 ? obstacle.minX - body.maxX
                    : axis == 1 ? obstacle.minY - body.maxY : obstacle.minZ - body.maxZ;
            double max = axis == 0 ? obstacle.maxX - body.minX
                    : axis == 1 ? obstacle.maxY - body.minY : obstacle.maxZ - body.minZ;
            if (Math.abs(direction) < 1.0E-12) {
                if (start < min || start > max) return false;
            } else {
                double first = (min - start) / direction;
                double last = (max - start) / direction;
                enter = Math.max(enter, Math.min(first, last));
                exit = Math.min(exit, Math.max(first, last));
                if (enter > exit) return false;
            }
        }
        return true;
    }

    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static boolean finite(AABB box) {
        return box != null && Double.isFinite(box.minX) && Double.isFinite(box.minY) && Double.isFinite(box.minZ)
                && Double.isFinite(box.maxX) && Double.isFinite(box.maxY) && Double.isFinite(box.maxZ)
                && box.minX < box.maxX && box.minY < box.maxY && box.minZ < box.maxZ;
    }

    private static boolean fitsBounds(AABB body, AABB bounds) {
        return bounds.maxX - bounds.minX > body.maxX - body.minX
                && bounds.maxY - bounds.minY > body.maxY - body.minY
                && bounds.maxZ - bounds.minZ > body.maxZ - body.minZ;
    }

    private static final class CaptureScale {
        double radius = MAX_RADIUS;
        int successes;
    }
}
