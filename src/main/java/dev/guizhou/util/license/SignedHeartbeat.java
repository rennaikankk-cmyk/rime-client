package dev.guizhou.util.license;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Signature verification plus explicit nonce, subject, session and bounded lease validation. */
public final class SignedHeartbeat {
    public static final int MAX_BYTES = 16_384;
    public record Window(long freshNanos, long totalNanos) {}
    public record Verdict(boolean allowed, String reason, String nonce, String subject, Map<String, String> fields) {
        public boolean matchesSession(String session) {
            return session != null && session.matches("[0-9a-f]{32}")
                    && "2".equals(fields.get("protocol")) && session.equals(fields.get("session"));
        }
        public Window windowFor(String session, long elapsedNanos) throws IOException {
            if (!allowed || !matchesSession(session) || elapsedNanos < 0 || elapsedNanos > 15_000_000_000L)
                throw new IOException("Invalid heartbeat session or response age");
            try {
                long issued = positive("issuedAt"), fresh = positive("leaseSeconds"), total = positive("graceSeconds");
                if (issued <= 0 || fresh > 900 || total > 86400 || total < fresh)
                    throw new IOException("Invalid signed lease limits");
                long remaining = total * 1_000_000_000L - elapsedNanos;
                long current = fresh * 1_000_000_000L - elapsedNanos;
                if (current <= 0 || remaining <= 0) throw new IOException("Response lease already expired");
                return new Window(current, remaining);
            } catch (NumberFormatException e) { throw new IOException("Invalid signed lease", e); }
        }
        private long positive(String name) throws IOException {
            String value = fields.get(name);
            if (value == null || !value.matches("[1-9][0-9]{0,15}")) throw new IOException("Missing or invalid " + name);
            return Long.parseLong(value);
        }
        public boolean matchesRequest(String expectedNonce, String expectedSubject) {
            return expectedNonce != null && expectedNonce.matches("[0-9a-f]{32}")
                    && expectedSubject != null && expectedSubject.matches("[0-9a-f]{64}")
                    && expectedNonce.equals(nonce) && expectedSubject.equals(subject);
        }
    }
    private SignedHeartbeat() {}

    public static Verdict verify(byte[] response, PublicKey publicKey) throws Exception {
        if (response == null || response.length == 0 || response.length > MAX_BYTES)
            throw new IOException("Invalid heartbeat response size");
        // Report malformed UTF-8 instead of silently replacing bytes before signature verification.
        String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(response)).toString();
        Map<String, String> data = null;
        String signature = null;
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false); // Minecraft 1.21.1 bundles Gson 2.10.1.
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if ("data".equals(key) && data == null) data = readData(reader);
                else if ("sig".equals(key) && signature == null && reader.peek() == JsonToken.STRING)
                    signature = reader.nextString();
                else throw new IOException("Unexpected or duplicate envelope field");
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing response data");
        }
        if (data == null || signature == null || !signature.matches("[0-9a-fA-F]{128}"))
            throw new IOException("Invalid signed envelope");
        String canonical = String.join("&", data.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
        if (!verifier.verify(java.util.HexFormat.of().parseHex(signature)))
            throw new IOException("Invalid heartbeat signature");
        String ok = data.get("ok");
        if (!"true".equals(ok) && !"false".equals(ok)) throw new IOException("Invalid verdict");
        String reason = data.getOrDefault("reason", data.getOrDefault("msg", "denied"));
        return new Verdict("true".equals(ok), reason.length() > 128 ? reason.substring(0, 128) : reason,
                data.getOrDefault("nonce", ""), data.getOrDefault("subject", ""), Map.copyOf(data));
    }

    private static Map<String, String> readData(JsonReader reader) throws IOException {
        Map<String, String> fields = new TreeMap<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String key = reader.nextName();
            if (!key.matches("[A-Za-z][A-Za-z0-9_]{0,31}") || fields.containsKey(key)
                    || fields.size() >= 16 || reader.peek() != JsonToken.STRING)
                throw new IOException("Invalid signed fields");
            String value = reader.nextString();
            // '&' is the canonical separator; accepting it would make field boundaries ambiguous.
            if (value.length() > 1024 || value.indexOf('&') >= 0 || value.chars().anyMatch(c -> c < 32))
                throw new IOException("Invalid signed value");
            fields.put(key, value);
        }
        reader.endObject();
        return fields;
    }

    /** Cancels oversized responses while streaming, before the HTTP client accumulates the full body. */
    public static HttpResponse.BodySubscriber<byte[]> limitedBody() { return new LimitedBody(); }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription next) {
            if (subscription != null) { next.cancel(); return; }
            subscription = next;
            next.request(1);
        }
        public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_BYTES - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("Heartbeat response too large"));
                    return;
                }
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { result.completeExceptionally(failure); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
