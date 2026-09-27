package d1;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.BiPredicate;

/**
 * Runtime licence heartbeat (protocol 2: per-request nonce + session echo,
 * server-signed lease/grace). Responses are Ed25519-signed by the auth server
 * over the canonical form (sorted k=v joined with &, all-string values);
 * verified against a pinned key so a local proxy cannot forge an "ok".
 */
final class B {
    private B() {}

    /** Honeypot: decoy pubkey. Real verify path uses id 1; patching 99 changes nothing. */
    public static final String PUB_FAKE =
            "302a300506032b6570032100dead0000c0de0000dead0000c0de0000dead0000c0de0000dead0000c0de0099";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private static final Random R = new Random();

    static volatile String lastCanonical; // edition transcript (signed data)
    static volatile String lastSig;

    static void beatOnce() {
        try {
            W.probe(); // late-attach witness: re-checked every beat, reported via env
            V.spotCheck(); // distinguishes proven tampering from an unreadable JAR
            String hwid = H.compute();
            String nonce = E.hex(16);
            String session = E.session != null ? E.session : (E.session = E.hex(16));
            exchange(URI.create(S.g(0)), hwid, nonce, session, B::verify);
        } catch (Throwable t) {
            E.netFail();
        }
    }

    /** Protocol exchange isolated so tests can use a loopback mock server. */
    static void exchange(URI endpoint, String hwid, String nonce, String session,
                         BiPredicate<Map<String, String>, String> verifier) {
        try {
            HttpRequest req = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody(hwid, nonce, session), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) { E.netFail(); return; }

            Map<String, String> data = E.parseObject(res.body(), "data");
            String sig = E.parseStringField(res.body(), "sig");
            acceptSignedResponse(data, sig, nonce, session, verifier);
        } catch (Throwable t) {
            E.netFail();
        }
    }

    static void acceptSignedResponse(Map<String, String> data, String sig, String nonce,
                                     String session, BiPredicate<Map<String, String>, String> verifier) {
            if (data == null || sig == null || !verifier.test(data, sig)) {
                E.deny("bad_signature"); // forged response — treat as hostile
                return;
            }
            // protocol 2: the server must echo our nonce+session inside the signed body
            if (!nonce.equals(data.get("nonce")) || !session.equals(data.get("session"))) {
                E.deny("bad_binding");
                return;
            }
            if (!"true".equals(data.get("ok"))) {
                E.deny(data.getOrDefault("reason", "denied"));
                return;
            }
            String challenge = data.get("entryChallenge");
            String cipher = data.get("entryCipher");
            if (challenge == null || !challenge.matches("[0-9a-f]{32}")
                    || cipher == null || !cipher.matches("[A-Za-z0-9_-]{1,128}")) {
                E.deny("missing_entry_material");
                return;
            }
            if (S.decodeEntry(session, nonce, challenge, cipher).isEmpty()) {
                E.deny("bad_entry_material");
                return;
            }
            E.entrySession = session;
            E.entryNonce = nonce;
            E.entryChallenge = challenge;
            E.entryCipher = cipher;
            lastCanonical = canonical(data);
            lastSig = sig;
            E.confirm(data);
    }

    static String requestBody(String hwid, String nonce, String session) {
        StringBuilder body = new StringBuilder(256).append('{');
        appendField(body, "token", E.token);
        appendField(body, "hwid", hwid);
        appendField(body, "protocol", "2");
        appendField(body, "guard", "dickguard3.0");
        appendField(body, "client", "guizhouware");
        appendField(body, "nonce", nonce);
        appendField(body, "session", session);
        if (E.envNote != null) appendField(body, "env", E.envNote);
        if (E.entryChallenge != null && session.equals(E.entrySession)) {
            appendField(body, "challengeProof", challengeProof(E.entryChallenge, session, nonce));
        }
        if (E.wmA != null) {
            appendField(body, "a", E.wmA);
            appendField(body, "h", E.wmH);
            appendField(body, "t", E.wmT);
            appendField(body, "s", E.wmS);
        }
        if (E.tampered) appendField(body, "integrity", "mismatch");
        else if (E.integrityUnavailable) appendField(body, "integrity", "unavailable");
        return body.append('}').toString();
    }

    static String challengeProof(String challenge, String session, String nonce) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    ("dick-guard-echo-v1|" + challenge + "|" + session + "|" + nonce)
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >>> 4) & 15, 16));
                hex.append(Character.forDigit(b & 15, 16));
            }
            return hex.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void appendField(StringBuilder body, String name, String value) {
        if (body.length() > 1) body.append(',');
        body.append('"').append(name).append("\":\"");
        String text = value == null ? "" : value;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                body.append('\\').append(c);
            } else if (c < 0x20) {
                body.append("\\u00");
                body.append(Character.forDigit((c >>> 4) & 0xf, 16));
                body.append(Character.forDigit(c & 0xf, 16));
            } else {
                body.append(c);
            }
        }
        body.append('"');
    }

    static String canonical(Map<String, String> data) {
        TreeMap<String, String> sorted = new TreeMap<>(data);
        StringBuilder sb = new StringBuilder(128);
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    static boolean verify(Map<String, String> data, String sigHex) {
        try {
            return vsig(canonical(data).getBytes(StandardCharsets.UTF_8), sigHex)
                    || vsig(canonical(data).getBytes(StandardCharsets.UTF_8), sigHex.toUpperCase(Locale.ROOT));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Ed25519 verify against the pinned SPKI key (id 1). */
    static boolean vsig(byte[] msg, String sigHex) {
        try {
            byte[] sig = E.unhex(sigHex);
            if (sig == null || sig.length != 64) return false;
            PublicKey key = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(E.unhex(S.g(1))));
            Signature v = Signature.getInstance("Ed25519");
            v.initVerify(key);
            v.update(msg);
            return v.verify(sig);
        } catch (Throwable t) {
            return false;
        }
    }

    static long jitter(long ms, double frac) {
        double j = 1.0 + (R.nextDouble() * 2 - 1) * frac;
        return (long) (ms * Math.max(0.25, j));
    }
}
