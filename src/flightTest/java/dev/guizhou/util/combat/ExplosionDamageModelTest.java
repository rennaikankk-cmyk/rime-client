package dev.guizhou.util.combat;

public final class ExplosionDamageModelTest {
    private static int checks;
    public static void main(String[] args) {
        near(ExplosionDamageModel.reduce(20f, 0f, 0f, 1f, 0), 20f, "Unarmored explosion");
        near(ExplosionDamageModel.reduce(20f, 20f, 8f, 1f, 0), 8f, "Armor with toughness");
        near(ExplosionDamageModel.reduce(20f, 20f, 8f, .8f, 20), 1.28f, "Armor, resistance and protection order");
        near(ExplosionDamageModel.reduce(20f, 20f, 8f, 1f, 40), 1.6f, "Protection caps at twenty");
        near(ExplosionDamageModel.reduce(20f, -10f, -10f, 2f, -5), 20f, "Invalid negative stats and multiplier clamp");
        near(ExplosionDamageModel.resistanceMultiplier(-1), 1f, "No resistance");
        near(ExplosionDamageModel.resistanceMultiplier(0), .8f, "Resistance I");
        near(ExplosionDamageModel.resistanceMultiplier(1), .6f, "Resistance II");
        near(ExplosionDamageModel.resistanceMultiplier(4), 0f, "Resistance V");
        near(ExplosionDamageModel.resistanceMultiplier(Integer.MAX_VALUE), 0f, "Large amplifier does not overflow");
        near(ExplosionDamageModel.reduce(20f, 500f, 0f, 1f, 0), 4f, "Attribute-modified armor caps safely");
        near(ExplosionDamageModel.reduce(20f, Float.NaN, Float.NaN, Float.NaN, 0), 20f, "Unknown reduction stats do not grant safety");
        for (int raw = 1; raw <= 120; raw++) {
            float base = ExplosionDamageModel.reduce(raw, 20f, 8f, 1f, 0);
            for (int protection = 0; protection <= 24; protection++) {
                float guarded = ExplosionDamageModel.reduce(raw, 20f, 8f, .6f, protection);
                check(Float.isFinite(guarded) && guarded >= 0f && guarded <= base,
                        "Reductions never create or increase damage");
            }
        }
        System.out.println("ExplosionDamageModelTest passed: " + checks + " checks");
    }
    private static void near(float actual, float expected, String message) {
        check(Math.abs(actual - expected) < .0001f, message + ": " + actual);
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
