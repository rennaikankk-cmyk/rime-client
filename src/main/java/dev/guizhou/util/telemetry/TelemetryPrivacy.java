package dev.guizhou.util.telemetry;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

public final class TelemetryPrivacy {
    private TelemetryPrivacy() {}

    /** Stable across installations for per-server diagnostics; this is pseudonymization, not encryption. */
    public static String serverHash(String address) {
        try {
            byte[] bytes = ("gzwx-telemetry-server-v1\n" + address.strip().toLowerCase(Locale.ROOT))
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static boolean safeIdentifier(String text) {
        return text != null && text.matches("[A-Za-z0-9_.:/-]{1,64}");
    }

    public static void setting(JsonObject target, String name, Object value) {
        if (!safeIdentifier(name)) return;
        if (value instanceof Boolean bool) target.addProperty(name, bool);
        else if (value instanceof Number number && Double.isFinite(number.doubleValue())
                && Math.abs(number.doubleValue()) <= 60_000_000) target.addProperty(name, number);
        else if (value instanceof Enum<?> option && safeIdentifier(option.name())) {
            target.addProperty(name, option.name());
        }
    }

    /** Legacy callers may contain a raw server address; never copy their object wholesale. */
    public static JsonObject probe(JsonObject source) {
        JsonObject safe = new JsonObject();
        for (String key : new String[]{"dig", "attack", "move"}) {
            if (!source.has(key) || !source.get(key).isJsonPrimitive()
                    || !source.get(key).getAsJsonPrimitive().isNumber()) continue;
            double value = source.get(key).getAsDouble();
            if (Double.isFinite(value) && value >= -1 && value <= 1_000_000_000) safe.addProperty(key, value);
        }
        return safe;
    }

    public static double bounded(double value, double min, double max) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : min;
    }
}
