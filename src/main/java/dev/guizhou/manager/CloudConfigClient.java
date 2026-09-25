package dev.guizhou.manager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.guizhou.util.license.AuthTransport;
import net.minecraft.client.Minecraft;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Authenticated, signed cloud presets. Network work never blocks a client tick. */
public final class CloudConfigClient {
    private static final URI ENDPOINT = URI.create("https://62.234.117.226/authapi/api/configs");
    private static final HttpClient HTTP = AuthTransport.create();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "gzwx-cloud-config");
        thread.setDaemon(true);
        return thread;
    });

    public record Reply(boolean ok, String message, List<String> names, String bundle) {
        public static Reply error(String message) { return new Reply(false, message, List.of(), null); }
    }

    private CloudConfigClient() {}

    public static void request(String scope, String action, String name, String bundle, Consumer<Reply> done) {
        if (done == null) return;
        String token = HeartbeatManager.cloudConfigToken();
        if (token == null) {
            done.accept(Reply.error("请先完成客户端授权校验"));
            return;
        }
        if (!("private".equals(scope) || "public".equals(scope))
                || !("list".equals(action) || "save".equals(action)
                || "load".equals(action) || "delete".equals(action))) {
            done.accept(Reply.error("云参数操作无效"));
            return;
        }
        WORKER.execute(() -> {
            Reply result;
            try {
                result = send(scope, action, name, bundle, token);
            } catch (Exception failure) {
                result = Reply.error("云参数连接失败，请稍后重试");
            }
            Reply resolved = result;
            Minecraft.getInstance().execute(() -> done.accept(resolved));
        });
    }

    private static Reply send(String scope, String action, String name, String bundle, String token) throws Exception {
        String nonce = UUID.randomUUID().toString().replace("-", "");
        JsonObject request = new JsonObject();
        request.addProperty("token", token);
        request.addProperty("hwid", HeartbeatManager.cloudConfigHwid());
        request.addProperty("nonce", nonce);
        request.addProperty("scope", scope);
        request.addProperty("action", action);
        if (name != null) request.addProperty("name", name);
        if (bundle != null) request.addProperty("bundle", bundle);
        HttpRequest http = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request.toString(), StandardCharsets.UTF_8)).build();
        HttpResponse<byte[]> response = HTTP.send(http, HttpResponse.BodyHandlers.ofByteArray());
        byte[] bytes = response.body();
        if (bytes == null || bytes.length > 1024 * 1024) throw new IllegalArgumentException("Oversized cloud response");
        JsonObject envelope = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject data = envelope.getAsJsonObject("data");
        String signature = envelope.get("sig").getAsString();
        if (data == null || !signature.matches("[0-9a-fA-F]{128}"))
            throw new IllegalArgumentException("Invalid signed response");
        Map<String, String> fields = new TreeMap<>();
        for (var entry : data.entrySet()) {
            if (!entry.getValue().isJsonPrimitive()) throw new IllegalArgumentException("Invalid signed field");
            String value = entry.getValue().getAsString();
            if (value.indexOf('&') >= 0 || value.chars().anyMatch(c -> c < 32))
                throw new IllegalArgumentException("Invalid signed value");
            fields.put(entry.getKey(), value);
        }
        String canonical = String.join("&", fields.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()).toList());
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(HeartbeatManager.serverSigningKey());
        verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
        if (!verifier.verify(java.util.HexFormat.of().parseHex(signature))
                || !nonce.equals(fields.get("nonce")))
            throw new IllegalArgumentException("Invalid cloud response");
        if (!"true".equals(fields.get("ok")))
            return Reply.error(fields.getOrDefault("msg", "云参数请求失败（HTTP " + response.statusCode() + "）"));
        if (response.statusCode() != 200) throw new IllegalArgumentException("Unexpected cloud status");
        List<String> names = new ArrayList<>();
        if (fields.containsKey("names")) {
            var array = JsonParser.parseString(fields.get("names")).getAsJsonArray();
            if (array.size() > 100) throw new IllegalArgumentException("Too many presets");
            for (var value : array) names.add(value.getAsString());
        }
        String savedBundle = fields.containsKey("bundle")
                ? new String(Base64.getUrlDecoder().decode(fields.get("bundle")), StandardCharsets.UTF_8)
                : null;
        if (savedBundle != null && savedBundle.length() > 512 * 1024)
            throw new IllegalArgumentException("Oversized cloud preset");
        return new Reply(true, fields.getOrDefault("name", "完成"), List.copyOf(names), savedBundle);
    }
}
