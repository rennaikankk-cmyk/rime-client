package dev.guizhou.util.combat;

/**
 * Pure damage estimates behind the AutoTotem lethal check. No Minecraft types so the
 * thresholds stay testable; world sampling lives in {@link LethalPredictor}.
 */
public final class TotemSafety {
    private TotemSafety() {}

    /** Vanilla fall damage before reductions: {@code ceil((fall - safeFall) * multiplier)}. */
    public static float fallDamage(double fallDistance, double safeFallDistance, double multiplier) {
        if (!Double.isFinite(fallDistance) || !Double.isFinite(safeFallDistance) || !Double.isFinite(multiplier)) return 0f;
        double excess = fallDistance - safeFallDistance;
        if (excess <= 0 || multiplier <= 0) return 0f;
        return (float) Math.ceil(excess * multiplier);
    }

    /**
     * Fall damage ignores armor; resistance applies, then Protection (1 point/level) and
     * Feather Falling (3 points/level) share the 20-point magic cap.
     */
    public static float fallAfterReductions(float raw, int protectionLevels, int featherFallingLevels,
                                            float resistanceMultiplier) {
        if (!(raw > 0f)) return 0f;
        float resist = Float.isFinite(resistanceMultiplier) ? Math.clamp(resistanceMultiplier, 0f, 1f) : 1f;
        int points = Math.clamp(Math.max(0, protectionLevels) + 3L * Math.max(0, featherFallingLevels), 0, 20);
        return raw * resist * (1f - points / 25f);
    }

    /** Elytra wall impact: vanilla hurts for {@code speedLost * 10 - 3}. */
    public static float kineticDamage(double horizontalSpeedLost) {
        if (!Double.isFinite(horizontalSpeedLost)) return 0f;
        return (float) Math.max(0.0, horizontalSpeedLost * 10.0 - 3.0);
    }

    /** Vanilla mace smash bonus for the attacker's current fall distance (Density excluded). */
    public static float maceFallBonus(double fallDistance) {
        if (!Double.isFinite(fallDistance) || fallDistance <= 1.5) return 0f;
        if (fallDistance <= 3.0) return (float) (4.0 * fallDistance);
        if (fallDistance <= 8.0) return (float) (12.0 + 2.0 * (fallDistance - 3.0));
        return (float) (22.0 + (fallDistance - 8.0));
    }

    /**
     * Raw melee estimate in vanilla order: the critical multiplier scales only the attribute
     * damage, Sharpness is added afterwards, and a mace smash bonus after that.
     */
    public static float meleeRaw(double weaponDamage, int sharpness, boolean critical, float smashBonus) {
        if (!Double.isFinite(weaponDamage) || weaponDamage <= 0) return 0f;
        double sharp = sharpness > 0 ? 0.5 * sharpness + 0.5 : 0.0;
        double smash = Float.isFinite(smashBonus) ? Math.max(0f, smashBonus) : 0.0;
        return (float) (weaponDamage * (critical ? 1.5 : 1.0) + sharp + smash);
    }

    /**
     * A single worst-case hit of {@code predictedDamage} would leave the player at or below
     * {@code margin}. Health includes absorption because vanilla consumes absorption first.
     */
    public static boolean lethal(float health, float absorption, float predictedDamage, double margin) {
        if (!(predictedDamage > 0f)) return false;
        float effective = Math.max(0f, health) + Math.max(0f, absorption);
        return effective - predictedDamage <= Math.max(0.0, margin);
    }
}
