package d1;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises JSON requests and signed response parsing over loopback HTTP. */
public final class MockHeartbeatTest {
    public static void main(String[] args) throws Exception {
        KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String session = "0123456789abcdef0123456789abcdef";
        String challenge = "fedcba9876543210fedcba9876543210";
        E.token = "test-token";
        E.session = session;
        E.tampered = false;
        E.integrityUnavailable = false;
        E.envNote = null;
        E.entryChallenge = null;

        String nonce1 = "11111111111111111111111111111111";
        String okRequest = roundTrip(okData(session, nonce1, challenge), key, nonce1, session);
        require("dickguard3.0".equals(E.field(okRequest, "guard")),
                "request must opt in to the deployed strict handshake");
        require("guizhouware".equals(E.field(okRequest, "client")),
                "server must know which entrypoint it is issuing");
        require(E.field(okRequest, "integrity") == null, "normal request must omit integrity");
        require(E.field(okRequest, "env") == null, "clean request must omit env");
        require(E.field(okRequest, "challengeProof") == null, "first request has no prior challenge");
        require(E.state == E.OK && "com.dickclient.DickClient".equals(S.g(9)),
                "signed ok response must unlock entry name");

        String nonce2 = "22222222222222222222222222222222";
        E.tampered = true;
        Map<String, String> mismatch = new LinkedHashMap<>();
        mismatch.put("ok", "false");
        mismatch.put("reason", "integrity_mismatch");
        mismatch.put("nonce", nonce2);
        mismatch.put("session", session);
        String mismatchRequest = roundTrip(mismatch, key, nonce2, session);
        require("mismatch".equals(E.field(mismatchRequest, "integrity")), "tamper request must report mismatch");
        require(B.challengeProof(challenge, session, nonce2).equals(E.field(mismatchRequest, "challengeProof")),
                "subsequent request must prove prior signed challenge");
        require(E.state == E.DENIED, "server mismatch decision must be parsed");

        String nonce3 = "33333333333333333333333333333333";
        E.tampered = false;
        E.integrityUnavailable = true;
        E.envNote = "agent:example";
        String unavailableRequest = roundTrip(okData(session, nonce3, challenge), key, nonce3, session);
        require("unavailable".equals(E.field(unavailableRequest, "integrity")),
                "unreadable inventory must report unavailable");
        require("agent:example".equals(E.field(unavailableRequest, "env")), "env witness must be reported");
        require(E.state == E.OK && !E.official(), "server ok must not turn an unverifiable copy official");
        E.noteEnv("selfattach");
        E.noteEnv("selfattach");
        require("agent:example;selfattach".equals(E.envNote), "repeated witness must not grow env indefinitely");
        E.envNote = "x".repeat(1100) + ";agent:example";
        String bounded = B.requestBody("mock-hwid", nonce3, session);
        require(E.field(bounded, "env").length() <= 1024
                        && E.field(bounded, "env").endsWith("agent:example"),
                "large startup environment must preserve recent witness within server limit");
        System.out.println("MockHeartbeatTest passed");
    }

    private static String roundTrip(Map<String, String> data, KeyPair key, String nonce, String session)
            throws Exception {
        String signature = sign(key, data);
        StringBuilder body = new StringBuilder("{\"data\":{");
        for (Map.Entry<String, String> entry : data.entrySet()) {
            if (body.charAt(body.length() - 1) != '{') body.append(',');
            body.append('"').append(entry.getKey()).append("\":\"").append(entry.getValue()).append('"');
        }
        body.append("},\"sig\":\"").append(signature).append("\"}");
        byte[] response = body.toString().getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> request = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/heartbeat", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            B.exchange(new URI("http://127.0.0.1:" + server.getAddress().getPort() + "/heartbeat"),
                    "mock-hwid", nonce, session, (d, s) -> verify(key, d, s));
            require(request.get() != null, "mock server must receive heartbeat");
            return request.get();
        } finally {
            server.stop(0);
        }
    }

    private static Map<String, String> okData(String session, String nonce, String challenge) throws Exception {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("ok", "true");
        data.put("nonce", nonce);
        data.put("session", session);
        data.put("entryChallenge", challenge);
        byte[] key = MessageDigest.getInstance("SHA-256").digest(
                ("dick-guard-entry-v1|" + session + "|" + nonce + "|" + challenge)
                        .getBytes(StandardCharsets.UTF_8));
        byte[] name = "com.dickclient.DickClient".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < name.length; i++) name[i] ^= key[i];
        data.put("entryCipher", Base64.getUrlEncoder().withoutPadding().encodeToString(name));
        return data;
    }

    private static String sign(KeyPair key, Map<String, String> data) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        signer.update(B.canonical(data).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : signer.sign()) {
            hex.append(Character.forDigit((b >>> 4) & 15, 16));
            hex.append(Character.forDigit(b & 15, 16));
        }
        return hex.toString();
    }

    private static boolean verify(KeyPair key, Map<String, String> data, String signature) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key.getPublic());
            verifier.update(B.canonical(data).getBytes(StandardCharsets.UTF_8));
            return verifier.verify(E.unhex(signature));
        } catch (Exception e) {
            return false;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
