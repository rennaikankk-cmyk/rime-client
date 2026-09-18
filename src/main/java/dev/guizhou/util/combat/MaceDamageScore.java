package dev.guizhou.util.combat;

/** Client estimate of raw damage during a vanilla smash opportunity. */
public final class MaceDamageScore {
    private MaceDamageScore() {
    }

    public static double enchantmentDamage(int sharpness, int applicableTargetLevels) {
        return (sharpness > 0 ? 0.5 * sharpness + 0.5 : 0.0)
                + 2.5 * Math.max(0, applicableTargetLevels);
    }

    public static double damage(double baseDamage, double enchantmentDamage, double vanillaFallBonus,
            double fallDistance, int density) {
        if (!Double.isFinite(baseDamage) || !Double.isFinite(enchantmentDamage)
                || !Double.isFinite(vanillaFallBonus) || !Double.isFinite(fallDistance)) return -1.0;
        double densityBonus = vanillaFallBonus > 0.0 && fallDistance > 1.5
                ? 0.5 * Math.max(0, density) * fallDistance : 0.0;
        return Math.max(0.0, baseDamage) + Math.max(0.0, enchantmentDamage)
                + Math.max(0.0, vanillaFallBonus) + densityBonus;
    }
}
