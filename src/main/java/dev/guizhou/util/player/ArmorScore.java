package dev.guizhou.util.player;

/**
 * AutoArmor ranking without Minecraft types. Defense dominates, then toughness and enchantments;
 * a piece at or below the low-durability line always loses to any healthy replacement.
 */
public final class ArmorScore {
    public record Piece(double armor, double toughness, int protection, int blastProtection,
                        int unbreaking, int durabilityLeft, int maxDurability, boolean binding) {
        public double durabilityFraction() {
            return maxDurability <= 0 ? 1.0 : Math.max(0, durabilityLeft) / (double) maxDurability;
        }
    }

    private ArmorScore() {}

    public static double score(Piece piece, boolean preferBlast) {
        double blastWeight = preferBlast ? 3.5 : 1.5;
        return piece.armor() * 10.0 + piece.toughness() * 4.0 + piece.protection() * 2.5
                + piece.blastProtection() * blastWeight + piece.unbreaking() * 0.2
                + piece.durabilityFraction();
    }

    public static boolean low(Piece piece, double lowFraction) {
        return piece.maxDurability() > 0 && piece.durabilityFraction() <= lowFraction;
    }

    /**
     * Whether {@code candidate} should replace {@code current} (null = empty slot). A cursed
     * current piece cannot be taken off; a cursed candidate is never put on automatically.
     */
    public static boolean shouldReplace(Piece current, Piece candidate, boolean preferBlast, double lowFraction) {
        if (candidate == null || candidate.binding()) return false;
        if (low(candidate, lowFraction)) return false;
        if (current == null) return true;
        if (current.binding()) return false;
        if (low(current, lowFraction)) return true;
        return score(candidate, preferBlast) > score(current, preferBlast) + 0.5;
    }
}
