package dev.guizhou.util.combat;

public final class TotemSafetyTest {
    private static int checks;

    public static void main(String[] args) {
        near(TotemSafety.fallDamage(3.0, 3.0, 1.0), 0f, "Safe fall distance is free");
        near(TotemSafety.fallDamage(3.2, 3.0, 1.0), 1f, "Fall damage rounds up");
        near(TotemSafety.fallDamage(23.0, 3.0, 1.0), 20f, "Twenty extra blocks deal twenty");
        near(TotemSafety.fallDamage(23.0, 3.0, 0.5), 10f, "Fall multiplier applies before rounding");
        near(TotemSafety.fallDamage(Double.NaN, 3.0, 1.0), 0f, "Unknown fall is not a threat");
        near(TotemSafety.fallAfterReductions(20f, 0, 4, 1f), 20f * (1f - 12f / 25f), "Feather Falling IV: 12 points");
        near(TotemSafety.fallAfterReductions(20f, 16, 4, 1f), 20f * (1f - 20f / 25f), "Protection and feather share the 20 cap");
        near(TotemSafety.fallAfterReductions(20f, 0, 0, 0.6f), 12f, "Resistance II scales fall damage");
        near(TotemSafety.kineticDamage(0.2), 0f, "Slow wall bumps are harmless");
        near(TotemSafety.kineticDamage(2.0), 17f, "Elytra impact: speed * 10 - 3");
        near(TotemSafety.maceFallBonus(1.5), 0f, "No smash at 1.5 blocks");
        near(TotemSafety.maceFallBonus(3.0), 12f, "4 per block up to 3");
        near(TotemSafety.maceFallBonus(8.0), 22f, "2 per block from 3 to 8");
        near(TotemSafety.maceFallBonus(20.0), 34f, "1 per block beyond 8");
        near(TotemSafety.meleeRaw(8.0, 5, true, 0f), 8f * 1.5f + 3f, "Crit scales attribute damage, Sharpness V adds 3");
        near(TotemSafety.meleeRaw(8.0, 0, false, 10f), 18f, "Smash bonus is added after the base");
        near(TotemSafety.meleeRaw(-1.0, 5, true, 0f), 0f, "Empty hand estimate is not negative");
        check(TotemSafety.lethal(20f, 0f, 18.5f, 2.0), "18.5 on 20 hp leaves 1.5 <= margin 2");
        check(!TotemSafety.lethal(20f, 16f, 18.5f, 2.0), "Absorption keeps the player safe");
        check(!TotemSafety.lethal(20f, 0f, 0f, 2.0), "No threat is never lethal");
        check(TotemSafety.lethal(4f, 0f, 1f, 3.0), "Low health with a small hit is inside the margin");
        check(!TotemSafety.lethal(20f, 0f, Float.NaN, 2.0), "NaN predictions do not force a totem");
        for (float damage = 0f; damage <= 40f; damage += 0.5f) {
            boolean lower = TotemSafety.lethal(20f, 0f, damage, 2.0);
            boolean higher = TotemSafety.lethal(20f, 0f, damage + 0.5f, 2.0);
            check(!lower || higher, "Lethality is monotonic in predicted damage");
        }
        System.out.println("TotemSafetyTest passed: " + checks + " checks");
    }

    private static void near(float actual, float expected, String message) {
        check(Math.abs(actual - expected) < 1.0e-4f, message + ": " + actual);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
