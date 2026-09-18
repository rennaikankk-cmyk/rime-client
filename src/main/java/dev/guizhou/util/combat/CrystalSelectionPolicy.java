package dev.guizhou.util.combat;

/** Shared damage gate for optimistic candidate bounds and exact placement scores. */
public final class CrystalSelectionPolicy {
    private CrystalSelectionPolicy() {}

    public static double minimumDamage(double configuredMinimum, float effectiveHealth,
                                       boolean armorBroken, boolean faceEligible) {
        if (faceEligible) return 0.5;
        double minimum = configuredMinimum;
        if (effectiveHealth / 36.0f <= 0.20) minimum /= 4.0;
        if (armorBroken) minimum /= 4.0;
        return minimum;
    }

    public static boolean isSelfDamageSafe(float damage, double maximum, float health, double reserve) {
        return Float.isFinite(damage) && damage >= 0.0F && Double.isFinite(maximum)
                && Float.isFinite(health) && Double.isFinite(reserve) && reserve >= 0.0
                && damage <= maximum && damage + reserve < health;
    }
}
