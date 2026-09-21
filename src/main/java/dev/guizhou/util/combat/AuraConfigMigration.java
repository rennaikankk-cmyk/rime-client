package dev.guizhou.util.combat;

import com.google.gson.*;

public final class AuraConfigMigration {
    private AuraConfigMigration() {}
    /** Old Delay/packet-critical defaults are intentionally replaced; user bindings remain intact. */
    public static JsonElement migrate(JsonElement element) {
        if (element == null || !element.isJsonObject()) return element;
        JsonObject copy = element.getAsJsonObject().deepCopy();
        if (!copy.has("AttackCD") && copy.has("Delay")) copy.addProperty("AttackCD", 1.0);
        if (!copy.has("CritMode") && copy.has("Criticals")) {
            try { copy.addProperty("CritMode", copy.get("Criticals").getAsBoolean() ? "SMART" : "OFF"); }
            catch (RuntimeException ignored) { copy.addProperty("CritMode", "OFF"); }
        }
        copy.remove("Delay"); copy.remove("Criticals"); copy.remove("CritsWithSword"); copy.remove("Strict");
        return copy;
    }
}
