package d1;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public final class EntrySessionTest {
    public static void main(String[] args) throws Exception {
        String session = "0123456789abcdef0123456789abcdef";
        String nonce = "abcdef0123456789abcdef0123456789";
        String challenge = "9876543210abcdef9876543210abcdef";
        String entry = "com.dickclient.DickClient";
        KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Map<String, String> data = new LinkedHashMap<>();
        data.put("ok", "true");
        data.put("nonce", nonce);
        data.put("session", session);
        data.put("entryChallenge", challenge);
        data.put("entryCipher", encrypt(entry, session, nonce, challenge));
        String sig = sign(key, data);

        E.state = E.PENDING;
        E.session = null;
        require(S.g(9).isEmpty(), "entry name must be unavailable before a session");
        E.session = session;
        require(S.g(9).isEmpty(), "client-created session alone must not unlock entry name");
        B.acceptSignedResponse(data, sig, nonce, session, (d, s) -> verify(key, d, s));
        require(E.state == E.OK && entry.equals(S.g(9)), "signed response must unlock the entry for this session");

        E.session = "different-session";
        require(S.g(9).isEmpty(), "entry name must remain bound to the accepted session");
        E.session = session;
        B.acceptSignedResponse(data, "00", nonce, session, (d, s) -> verify(key, d, s));
        require(E.state == E.DENIED && S.g(9).isEmpty(), "invalid signature must clear entry material");

        Map<String, String> missing = new LinkedHashMap<>(data);
        missing.remove("entryCipher");
        B.acceptSignedResponse(missing, sign(key, missing), nonce, session, (d, s) -> verify(key, d, s));
        require(E.state == E.DENIED && "missing_entry_material".equals(E.reason),
                "old server response must fail closed");
        System.out.println("EntrySessionTest passed");
    }

    private static String encrypt(String name, String session, String nonce, String challenge) throws Exception {
        byte[] key = MessageDigest.getInstance("SHA-256").digest(
                ("dick-guard-entry-v1|" + session + "|" + nonce + "|" + challenge)
                        .getBytes(StandardCharsets.UTF_8));
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) bytes[i] ^= key[i];
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
