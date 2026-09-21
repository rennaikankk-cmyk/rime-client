package dev.guizhou.util.combat;

/** Immutable values only: safe to pass to a background combat calculation. */
public record CombatantSnapshot(int entityId, long identity, long revision, double x, double y, double z,
                                double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                float health, float absorption, float armor, float toughness,
                                float resistanceMultiplier, int protection, int assumedProtection, boolean alive) {
    public float effectiveHealth() { return health + absorption; }
    public CombatantSnapshot at(double nextX, double nextY, double nextZ) {
        double dx = nextX - x, dy = nextY - y, dz = nextZ - z;
        return new CombatantSnapshot(entityId, identity, revision, nextX, nextY, nextZ,
                minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz,
                health, absorption, armor, toughness, resistanceMultiplier, protection, assumedProtection, alive);
    }
}
