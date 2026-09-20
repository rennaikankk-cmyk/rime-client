package dev.guizhou.util.combat;

import dev.guizhou.util.traits.Util;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Client-thread boundary for a bounded collision snapshot and its live-state validation token. */
public final class CombatTerrainCapture implements Util {
    public static final int MAX_CELLS = 32768;
    public static final int MAX_BOXES = 65536;
    private CombatTerrainCapture() {}

    public static final class Captured {
        private final ClientLevel level;
        private final int minX, minY, minZ, sizeX, sizeY, sizeZ;
        private final BlockState[] states;
        private final CombatCollisionSnapshot terrain;
        private Captured(ClientLevel level, int x, int y, int z, int sx, int sy, int sz,
                         BlockState[] states, CombatCollisionSnapshot terrain) {
            this.level = level; minX = x; minY = y; minZ = z; sizeX = sx; sizeY = sy; sizeZ = sz;
            this.states = states; this.terrain = terrain;
        }
        public CombatCollisionSnapshot terrain() { return terrain; }
        public int cells() { return states.length; }
        /** Called only on the client thread; verifies actual states, not merely packet receipt time. */
        public boolean stillValid(long budgetNanos) {
            requireClientThread();
            if (mc.level != level) return false;
            long deadline = System.nanoTime() + budgetNanos;
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            int index = 0;
            for (int x = minX; x < minX + sizeX; x++) for (int y = minY; y < minY + sizeY; y++) for (int z = minZ; z < minZ + sizeZ; z++) {
                if ((index & 127) == 0 && System.nanoTime() > deadline) return false;
                cursor.set(x, y, z);
                if (!mc.level.hasChunkAt(cursor) || mc.level.getBlockState(cursor) != states[index++]) return false;
            }
            return true;
        }
    }

    public static Captured capture(List<AnchorPlanner.Candidate> candidates, List<CombatantSnapshot> targets,
                                   CombatantSnapshot self, long budgetNanos) {
        requireClientThread();
        if (mc.level == null || candidates.isEmpty()) return null;
        long deadline = System.nanoTime() + budgetNanos;
        double minX = self.minX(), minY = self.minY(), minZ = self.minZ();
        double maxX = self.maxX(), maxY = self.maxY(), maxZ = self.maxZ();
        for (CombatantSnapshot target : targets) {
            minX = Math.min(minX, target.minX()); minY = Math.min(minY, target.minY()); minZ = Math.min(minZ, target.minZ());
            maxX = Math.max(maxX, target.maxX()); maxY = Math.max(maxY, target.maxY()); maxZ = Math.max(maxZ, target.maxZ());
        }
        for (AnchorPlanner.Candidate candidate : candidates) {
            minX = Math.min(minX, candidate.x()); minY = Math.min(minY, candidate.y()); minZ = Math.min(minZ, candidate.z());
            maxX = Math.max(maxX, candidate.x() + 1); maxY = Math.max(maxY, candidate.y() + 1); maxZ = Math.max(maxZ, candidate.z() + 1);
        }
        int x0 = (int)Math.floor(minX) - 1, y0 = (int)Math.floor(minY) - 1, z0 = (int)Math.floor(minZ) - 1;
        int sx = (int)Math.floor(maxX) + 2 - x0, sy = (int)Math.floor(maxY) + 2 - y0, sz = (int)Math.floor(maxZ) + 2 - z0;
        long volume = (long)sx * sy * sz;
        if (volume <= 0 || volume > MAX_CELLS) return null;
        BlockState[] states = new BlockState[(int)volume];
        Map<Integer, List<CombatCollisionSnapshot.Box>> geometry = new HashMap<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int index = 0, boxCount = 0;
        for (int x = x0; x < x0 + sx; x++) for (int y = y0; y < y0 + sy; y++) for (int z = z0; z < z0 + sz; z++) {
            if ((index & 127) == 0 && System.nanoTime() > deadline) return null;
            cursor.set(x, y, z);
            if (!mc.level.hasChunkAt(cursor)) return null;
            BlockState state = mc.level.getBlockState(cursor);
            states[index] = state;
            if (!state.isAir()) {
                var boxes = state.getCollisionShape(mc.level, cursor).toAabbs();
                if (!boxes.isEmpty()) {
                    List<CombatCollisionSnapshot.Box> copies = new ArrayList<>(boxes.size());
                    for (var box : boxes) {
                        if (++boxCount > MAX_BOXES) return null;
                        copies.add(new CombatCollisionSnapshot.Box(box.minX + x, box.minY + y, box.minZ + z,
                                box.maxX + x, box.maxY + y, box.maxZ + z));
                    }
                    geometry.put(index, copies);
                }
            }
            index++;
        }
        CombatCollisionSnapshot terrain = new CombatCollisionSnapshot(x0, y0, z0, sx, sy, sz, geometry);
        if (System.nanoTime() > deadline) return null;
        return new Captured(mc.level, x0, y0, z0, sx, sy, sz, states, terrain);
    }

    private static void requireClientThread() {
        if (!mc.isSameThread()) throw new IllegalStateException("Combat terrain capture requires the client thread");
    }
}
