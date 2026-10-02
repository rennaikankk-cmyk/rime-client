package dev.guizhou.util.combat;

import dev.guizhou.util.diagnostics.ClientDiagnostics;
import dev.guizhou.util.traits.Util;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** A synchronous calculation pass. Reset before actions, packet callbacks, or a new scan.
 * Caches terrain and the exact vanilla sample points, never the outcome of a ray. */
public final class ExplosionExposure implements Util {
    private final Long2ObjectOpenHashMap<BlockState> states = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<VoxelShape> shapes = new Long2ObjectOpenHashMap<>();
    private final Map<AABB, Vec3[]> samples = new HashMap<>();
    private net.minecraft.client.multiplayer.ClientLevel level;

    public void reset() {
        states.clear();
        shapes.clear();
        samples.clear();
        level = mc.level;
    }

    public BlockState stateAt(BlockPos pos) {
        long key = pos.asLong();
        BlockState state = states.get(key);
        if (state == null) {
            state = level.getBlockState(pos);
            if (states.size() < 8192) states.put(key, state);
            ClientDiagnostics.count("combat.exposure.blockReads", 1);
        }
        return state;
    }

    public VoxelShape shapeAt(BlockPos pos) {
        long key = pos.asLong();
        VoxelShape shape = shapes.get(key);
        if (shape == null) {
            BlockState state = stateAt(pos);
            shape = state.isAir() ? Shapes.empty() : state.getCollisionShape(level, pos);
            if (shapes.size() < 8192) shapes.put(key, shape);
        }
        return shape;
    }

    public double exposure(Vec3 source, AABB box, Function<BlockPos, VoxelShape> terrain, String rayCounter) {
        if (!mc.isSameThread()) throw new IllegalStateException("Explosion exposure requires the client thread");
        if (mc.level == null) return 0;
        if (level != mc.level) reset();
        Vec3[] points = samples.get(box);
        if (points == null) {
            double[] coordinates = ExplosionSampling.points(box.minX, box.minY, box.minZ,
                    box.maxX, box.maxY, box.maxZ);
            points = new Vec3[coordinates.length / 3];
            for (int i = 0; i < points.length; i++)
                points[i] = new Vec3(coordinates[i * 3], coordinates[i * 3 + 1], coordinates[i * 3 + 2]);
            if (samples.size() < 128) samples.put(box, points);
            ClientDiagnostics.count("combat.exposure.sampleBuilds", 1);
        }
        int clear = 0;
        for (Vec3 point : points) {
            if (BlockGetter.traverseBlocks(point, source, point,
                    (start, pos) -> {
                        VoxelShape shape = terrain.apply(pos);
                        return shape.isEmpty() ? null : shape.clip(start, source, pos);
                    }, start -> null) == null) clear++;
        }
        ClientDiagnostics.count(rayCounter, points.length);
        return points.length == 0 ? 0 : (double) clear / points.length;
    }
}
