package dev.guizhou.util.license;

import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Flow;

/** Ephemeral test keys only; no network, customer credentials or production private key. */
public final class SignedHeartbeatTest {
    private static int checks;
    private static KeyPair key;
    public static void main(String[] args) throws Exception {
        key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String success = envelope(Map.of("ok", "true", "next", "600", "expiresAt", "2026-12-01 12:00:00"));
        check(verify(success).allowed(), "Existing sorted all-string success protocol is accepted");
        var denied = verify(envelope(Map.of("reason", "banned", "ok", "false")));
        check(!denied.allowed() && denied.reason().equals("banned"), "Signed refusal remains a refusal");
        var message = verify(envelope(Map.of("msg", "授权已过期", "ok", "false")));
        check(message.reason().equals("授权已过期"), "Unicode signed reason remains compatible");
        reject(success.replace("\"true\"", "\"false\""), "Tampered verdict");
        reject(success.replace("\"sig\":\"", "\"sig\":\"f"), "Odd-length signature formerly truncated");
        reject(success.replace("\"sig\":\"", "\"sig\":\"zz"), "Nonhex or excessive signature");
        reject(success.replace("\"ok\":\"true\"", "\"ok\":true"), "Coercible non-string verdict");
        reject(success.replace("\"ok\":\"true\"", "\"ok\":\"false\",\"ok\":\"true\""), "Duplicate signed field");
        reject(success.replaceFirst("\\{", "{\"sig\":\"00\","), "Duplicate envelope signature");
        reject(success + "{}", "Trailing JSON document");
        reject(success.replaceFirst("\\{", "{/* comment */"), "Non-JSON comments");
        reject(success.replaceFirst("\\{", "{\"extra\":\"ignored\","), "Unknown envelope field");
        reject(envelope(Map.of("ok", "TRUE")), "Noncanonical verdict value");
        reject(envelope(Map.of("ok", "true", "msg", "hello&ok=false")), "Ambiguous canonical field delimiter even with valid signature");
        reject(envelope(Map.of("ok", "true", "msg", "bad\nvalue")), "Control characters even with valid signature");
        reject(envelope(Map.of("next", "600")), "Missing verdict");
        reject(envelope(Map.of("ok", "true", "msg", "x".repeat(1025))), "Oversized field");
        var many = new TreeMap<String, String>();
        many.put("ok", "true");
        for (int i = 0; i < 16; i++) many.put("field" + i, "value");
        reject(envelope(many), "Unbounded signed field count");
        try {
            SignedHeartbeat.verify(success.getBytes(StandardCharsets.UTF_8), KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic());
            throw new AssertionError("Other signing key accepted");
        } catch (java.io.IOException expected) { checks++; }
        try {
            SignedHeartbeat.verify(new byte[SignedHeartbeat.MAX_BYTES + 1], key.getPublic());
            throw new AssertionError("Oversized body accepted");
        } catch (java.io.IOException expected) { checks++; }
        byte[] invalidUtf8 = {(byte)0xc3, (byte)0x28};
        try { SignedHeartbeat.verify(invalidUtf8, key.getPublic()); throw new AssertionError("Invalid UTF-8 accepted"); }
        catch (java.nio.charset.CharacterCodingException expected) { checks++; }
        streaming();
        System.out.println("SignedHeartbeatTest passed: " + checks + " checks");
    }

    private static void streaming() {
        var subscriber = SignedHeartbeat.limitedBody();
        var flow = new TestSubscription();
        subscriber.onSubscribe(flow);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[10]), ByteBuffer.wrap(new byte[SignedHeartbeat.MAX_BYTES - 10])));
        subscriber.onComplete();
        check(subscriber.getBody().toCompletableFuture().join().length == SignedHeartbeat.MAX_BYTES,
                "Body at exact bound survives multiple chunks");
        check(!flow.cancelled && flow.requests == 2, "One batch at a time applies backpressure");
        var oversized = SignedHeartbeat.limitedBody();
        var stopped = new TestSubscription();
        oversized.onSubscribe(stopped);
        oversized.onNext(List.of(ByteBuffer.wrap(new byte[SignedHeartbeat.MAX_BYTES]), ByteBuffer.wrap(new byte[1])));
        oversized.onComplete();
        check(stopped.cancelled && oversized.getBody().toCompletableFuture().isCompletedExceptionally(),
                "Body over bound cancels transport and cannot be accepted by later completion");
        var failed = SignedHeartbeat.limitedBody();
        failed.onSubscribe(new TestSubscription());
        failed.onError(new java.io.IOException("fixture failure"));
        failed.onComplete();
        check(failed.getBody().toCompletableFuture().isCompletedExceptionally(), "Streaming failures remain failures");
    }

    private static final class TestSubscription implements Flow.Subscription {
        boolean cancelled;
        int requests;
        public void request(long n) { check(n == 1, "Demand stays bounded"); requests++; }
        public void cancel() { cancelled = true; }
    }
    private static String envelope(Map<String, String> data) throws Exception {
        var sorted = new TreeMap<>(data);
        var payload = new JsonObject();
        sorted.forEach(payload::addProperty);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(key.getPrivate());
        signature.update(String.join("&", sorted.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()).getBytes(StandardCharsets.UTF_8));
        var root = new JsonObject();
        root.add("data", payload);
        root.addProperty("sig", HexFormat.of().formatHex(signature.sign()));
        return root.toString();
    }
    private static SignedHeartbeat.Verdict verify(String value) throws Exception {
        return SignedHeartbeat.verify(value.getBytes(StandardCharsets.UTF_8), key.getPublic());
    }
    private static void reject(String value, String label) throws Exception {
        try { verify(value); throw new AssertionError(label + " accepted"); }
        catch (java.io.IOException | IllegalStateException expected) { checks++; }
    }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
}
