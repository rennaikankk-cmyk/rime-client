package dev.guizhou.util.combat;

public final class MaceDamageScoreTest {
    private static int checks;

    public static void main(String[] args) {
        double sword = MaceDamageScore.damage(8.0, MaceDamageScore.enchantmentDamage(5, 0), 0.0, 2.0, 0);
        double mace = MaceDamageScore.damage(6.0, 0.0, 8.0, 2.0, 0);
        check(sword == 11.0, "Sharpness V adds three damage to a netherite sword");
        check(mace == 14.0 && mace > sword, "A real two-block smash outranks the sword");
        check(MaceDamageScore.damage(6.0, 0.0, 0.0, 2.0, 5) == 6.0,
                "Density does not create smash damage when vanilla did not permit a smash");
        check(MaceDamageScore.damage(6.0, 0.0, 8.0, 2.0, 5) == 19.0,
                "Density V adds half a point per level per fallen block");
        check(MaceDamageScore.damage(6.0, 0.0, 12.0, 3.0, 1) == 19.5,
                "A different Density level affects the selected mace score");
        check(MaceDamageScore.enchantmentDamage(0, 0) == 0.0,
                "Smite and Bane contribute nothing against an inapplicable target");
        check(MaceDamageScore.enchantmentDamage(0, 5) == 12.5,
                "A target-sensitive level five enchantment adds its actual bonus");
        check(MaceDamageScore.enchantmentDamage(1, 0) == 1.0, "Sharpness I uses the vanilla first-level bonus");
        check(MaceDamageScore.enchantmentDamage(0, -1) == 0.0, "Invalid negative enchantment levels add nothing");
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            check(MaceDamageScore.damage(invalid, 0.0, 8.0, 2.0, 5) < 0.0, "Invalid base damage is not selectable");
            check(MaceDamageScore.damage(6.0, invalid, 8.0, 2.0, 5) < 0.0, "Invalid enchantment damage is not selectable");
            check(MaceDamageScore.damage(6.0, 0.0, invalid, 2.0, 5) < 0.0, "Invalid fall bonus is not selectable");
            check(MaceDamageScore.damage(6.0, 0.0, 8.0, invalid, 5) < 0.0, "Invalid fall distance is not selectable");
        }
        System.out.println("MaceDamageScoreTest passed: " + checks + " checks");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
