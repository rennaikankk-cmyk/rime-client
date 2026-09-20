package dev.guizhou.util.combat;

/** Immutable values only: safe to pass to a background combat calculation. */
public record CombatantSnapshot(int entityId, long identity, long revision, double x, double y, double z,
                                double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                float health, float absorption, float armor, float toughness,
                                float resistanceMultiplier, int protection, int assumedProtection, boolean alive) {
    public float effectiveHealth() { return health + absorption; }
}
