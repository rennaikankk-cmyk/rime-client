package dev.guizhou.util.combat;

/** Shared armor, resistance and protection reduction for explosion estimates. */
public final class ExplosionDamageModel {
    private ExplosionDamageModel() {}

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
}
