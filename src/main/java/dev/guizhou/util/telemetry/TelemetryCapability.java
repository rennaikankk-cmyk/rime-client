package dev.guizhou.util.telemetry;

import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Local format/expiry check only; the collector verifies the HMAC signature. */
public record TelemetryCapability(String token, long expiresAt) {
    public static TelemetryCapability read(InputStream stream) {
        if (stream == null) return null;
        try {
            byte[] bytes = stream.readNBytes(4097);
            if (bytes.length > 4096) return null;
            String value = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                    .getAsJsonObject().get("token").getAsString();
            if (!value.matches("t1\\.[0-9a-f]{32}\\.[0-9]{1,12}\\.[0-9a-f]{64}")) return null;
            return new TelemetryCapability(value, Long.parseLong(value.split("\\.")[2]));
        } catch (Exception ignored) { return null; }
    }

    public boolean validAt(long epochSeconds) { return epochSeconds < expiresAt; }
}
