package dev.guizhou.util.combat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Small motion may reuse a plan, but equipment/pose/identity changes invalidate it. Winner damage is always recomputed live. */
public final class CombatSnapshotValidation {
    private CombatSnapshotValidation() {}

    public static boolean compatible(CombatantSnapshot before, CombatantSnapshot now, double maxMovement) {
        if (before.entityId() != now.entityId() || before.identity() != now.identity() || !now.alive()) return false;
        if (before.armor() != now.armor() || before.toughness() != now.toughness()
                || before.resistanceMultiplier() != now.resistanceMultiplier()
                || before.protection() != now.protection() || before.assumedProtection() != now.assumedProtection()) return false;
        if (Math.abs((before.maxX() - before.minX()) - (now.maxX() - now.minX())) > 1.0e-6
                || Math.abs((before.maxY() - before.minY()) - (now.maxY() - now.minY())) > 1.0e-6
                || Math.abs((before.maxZ() - before.minZ()) - (now.maxZ() - now.minZ())) > 1.0e-6) return false;
        double dx = now.x() - before.x(), dy = now.y() - before.y(), dz = now.z() - before.z();
        return dx * dx + dy * dy + dz * dz <= maxMovement * maxMovement;
    }

    public static boolean compatibleTargets(List<CombatantSnapshot> before, List<CombatantSnapshot> now, double movement) {
        if (before.size() != now.size()) return false;
        Map<Integer, CombatantSnapshot> indexed = new HashMap<>();
        for (CombatantSnapshot target : now) indexed.put(target.entityId(), target);
        for (CombatantSnapshot target : before) {
            CombatantSnapshot current = indexed.get(target.entityId());
            if (current == null || !compatible(target, current, movement)) return false;
        }
        return true;
    }
}
