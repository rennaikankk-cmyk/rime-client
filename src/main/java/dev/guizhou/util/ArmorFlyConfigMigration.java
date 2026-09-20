package dev.guizhou.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Import the removed Tricks switch without retaining a second runtime owner. */
public final class ArmorFlyConfigMigration {
    private ArmorFlyConfigMigration() {}

    public static JsonObject migrate(JsonObject input) {
        JsonObject copy = input.deepCopy();
        JsonObject flight = object(copy, "GuangxiElytra");
        if (flight == null || !flight.has("ArmorFly")) return copy;
        Boolean legacy = bool(flight.remove("ArmorFly"));
        if (!Boolean.TRUE.equals(legacy) || Boolean.FALSE.equals(bool(flight.get("Enabled")))) return copy;
        JsonObject standalone = object(copy, "ArmorFly");
        if (standalone == null) { standalone = new JsonObject(); copy.add("ArmorFly", standalone); }
        // Preserve active old ownership without activating a dormant flight profile.
        // A partial preset with no Enabled key explicitly requests the new independent owner.
        standalone.addProperty("Enabled", true);
        return copy;
    }

    private static JsonObject object(JsonObject root, String key) {
        JsonElement value = root.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }
    private static Boolean bool(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return null;
        String raw = value.getAsString();
        return "true".equalsIgnoreCase(raw) ? Boolean.TRUE : "false".equalsIgnoreCase(raw) ? Boolean.FALSE : null;
    }
}
