package dev.guizhou.util.combat;

import net.minecraft.world.Difficulty;

/**
 * Shared armor, resistance and protection reduction for explosion estimates,
 * plus the vanilla blast "settlement" math (impact falloff + raw damage curve).
 *
 * The three constants below match the vanilla explosion damage curve and the
 * Obelisk teardown's engine readout ((impact^2+impact) with 2.0 / 7.0 / 12.0):
 * crystals are power 6, so their effective blast radius is 2*6 = 12 blocks.
 * Geometry (exposure grid sampling with see-through / phantom-obsidian
 * projection) stays inside GuangxiCrystal — that part carries product
 * semantics; the pure math lives here so every caller settles identically.
 */
public final class ExplosionDamageModel {
    private ExplosionDamageModel() {}

    /** Effective blast radius of an end crystal: 2 * power(6). */
    public static final double CRYSTAL_RADIUS = 12.0;

    /** An absent resistance effect is represented by amplifier -1. */
    public static float resistanceMultiplier(int amplifier) {
        return amplifier < 0 ? 1.0f : Math.max(0.0f, 1.0f - 0.2f * ((long) amplifier + 1));
    }

    public static float reduce(float raw, float armor, float toughness,
                               float resistanceMultiplier, int protectionPoints) {
        if (!Float.isFinite(raw) || raw <= 0f) return 0f;
        armor = Float.isFinite(armor) ? Math.max(0f, armor) : 0f;
        toughness = Float.isFinite(toughness) ? Math.max(0f, toughness) : 0f;
        if (!Float.isFinite(resistanceMultiplier)) resistanceMultiplier = 1f;
        float absorbed = Math.clamp(armor - raw / (2f + toughness / 4f), Math.min(armor * 0.2f, 20f), 20f);
        float damage = raw * (1f - absorbed / 25f);
        damage *= Math.clamp(resistanceMultiplier, 0f, 1f);
        damage *= 1f - Math.clamp(protectionPoints, 0, 20) / 25f;
        return Math.max(0f, damage);
    }

    /** Impact falloff before armor: (1 - dist/radius) * exposure, floored at 0. */
    public static double impact(double distance, double exposure, double radius) {
        if (radius <= 0 || distance < 0 || exposure <= 0) return 0.0;
        return Math.max(0.0, (1.0 - distance / radius) * exposure);
    }

    /** Raw explosion damage for an impact value at the given power, with difficulty scaling. */
    public static float blastDamage(double impact, double power, Difficulty difficulty) {
        float dmg = (float) ((impact * impact + impact) / 2.0 * 7.0 * (power * 2.0) + 1.0);
        switch (difficulty) {
            case PEACEFUL -> dmg = 0f;
            case EASY -> dmg = Math.min(dmg / 2f + 1f, dmg);
            case HARD -> dmg *= 1.5f;
        }
        return dmg;
    }

    /** Crystal settlement in one call: power 6, radius 12. */
    public static float crystalDamage(double distance, double exposure, Difficulty difficulty) {
        return blastDamage(impact(distance, exposure, CRYSTAL_RADIUS), 6.0, difficulty);
    }
}

