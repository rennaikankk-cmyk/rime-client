package dev.guizhou.util.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.LinkedHashMap;
import java.util.Map;

/** Received crystal identity is confirmation; an attack submission or unrelated blast is not. */
public final class CrystalSpawnLedger {
    private final Map<Integer, BlockPos> crystals = new LinkedHashMap<>();

    public void spawned(int id, Vec3 position) {
        if (position == null || !Double.isFinite(position.x) || !Double.isFinite(position.y)
                || !Double.isFinite(position.z)) return;
        crystals.remove(id);
        if (crystals.size() >= 512) crystals.remove(crystals.keySet().iterator().next());
        crystals.put(id, BlockPos.containing(position));
    }

    public BlockPos removed(int id) { return crystals.remove(id); }
    public boolean occupied(BlockPos pos) { return crystals.containsValue(pos); }
    public void clear() { crystals.clear(); }

    public static boolean matchesBlast(BlockPos air, Vec3 center) {
        if (air == null || center == null) return false;
        // A crystal's position is the bottom of the air cell, not one block above it.
        double d = center.distanceToSqr(new Vec3(air.getX() + .5, air.getY(), air.getZ() + .5));
        return Double.isFinite(d) && d <= .015625;
    }
}
