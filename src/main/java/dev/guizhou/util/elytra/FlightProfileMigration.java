package dev.guizhou.util.elytra;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Import only supported controls; legacy packet/angle knobs cannot re-enable retired paths. */
public final class FlightProfileMigration {
    private FlightProfileMigration() {}

    public static JsonElement migrate(JsonElement element) {
        if (element == null || !element.isJsonObject()) return new JsonObject();
        JsonObject source = element.getAsJsonObject();
        JsonObject result = source.deepCopy();
        if (!source.has("Mode") && source.has("MotionMode")) {
            String legacy = string(source, "MotionMode", "FIREWORKS");
            boolean direct = "VOID".equalsIgnoreCase(legacy)
                    || ("FIREWORKS".equalsIgnoreCase(legacy)
                    && "false".equalsIgnoreCase(string(source, "AutoRescale", "true"))
                    && !"true".equalsIgnoreCase(string(source, "CoupledCruise", "false"))
                    && !"true".equalsIgnoreCase(string(source, "GrimBoost", "false")));
            result.addProperty("Mode", direct ? "NoAC" : "Grim");
        }
        String mode = string(result, "Mode", "Grim");
        if (result.has("Mode")) result.addProperty("Mode", "NoAC".equalsIgnoreCase(mode) ? "NoAC" : "Grim");
        if (!source.has("SpeedBps") && "NoAC".equalsIgnoreCase(mode)) {
            double legacy = number(source, "Speed", 2.0) * 20.0;
            result.addProperty("SpeedBps", bounded(legacy));
            if (!source.has("ClimbBps")) result.addProperty("ClimbBps", bounded(legacy));
        }
        for (String key : new String[]{"SpeedBps", "ClimbBps"}) {
            if (result.has(key)) result.addProperty(key, bounded(number(result, key, 40.0)));
        }
        return result;
    }

    private static String string(JsonObject object, String key, String fallback) {
        try { return object.get(key).getAsString(); }
        catch (RuntimeException e) { return fallback; }
    }

    private static double number(JsonObject object, String key, double fallback) {
        try {
            double value = object.get(key).getAsDouble();
            return Double.isFinite(value) ? value : fallback;
        } catch (RuntimeException e) { return fallback; }
    }

    private static double bounded(double value) { return Math.max(1, Math.min(300, value)); }
}
