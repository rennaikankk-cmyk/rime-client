package d1;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Real signed-inventory fixture: then alter one listed JAR entry. */
public final class IntegritySmoke {
    public static void main(String[] args) throws Exception {
        Path jar = Files.createTempFile("guard-integrity-", ".jar");
        try {
            KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("fabric.mod.json", "{}".getBytes(StandardCharsets.UTF_8));
            entries.put("d1/G.class", new byte[] { 1, 2, 3 });
            entries.put("sample.bin", new byte[] { 4, 5, 6 });

            StringBuilder inventory = new StringBuilder("gzwx-integrity-v1\n");
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                inventory.append(hex(MessageDigest.getInstance("SHA-256").digest(entry.getValue())))
                        .append(' ')
                        .append(Base64.getUrlEncoder().withoutPadding().encodeToString(
                                entry.getKey().getBytes(StandardCharsets.UTF_8)))
                        .append('\n');
            }
            byte[] signed = inventory.toString().getBytes(StandardCharsets.UTF_8);
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key.getPrivate());
            signer.update(signed);
            String sig = hex(signer.sign());

            writeJar(jar, entries, signed, sig);
            require(V.verifyNow(jar, (data, signature) -> verify(key, data, signature)),
                    "signed fixture should pass");
            entries.put("sample.bin", new byte[] { 4, 5, 7 });
            writeJar(jar, entries, signed, sig);
            E.tampered = false;
            E.integrityUnavailable = false;
            require(!V.verifyNow(jar, (data, signature) -> verify(key, data, signature)),
                    "modified listed entry must fail");
            require(E.tampered && !E.integrityUnavailable && "hash:sample.bin".equals(E.integrityIssue),
                    "modified entry must be classified mismatch");

            E.tampered = false;
            E.integrityUnavailable = false;
            Method installedJar = V.class.getDeclaredMethod("installedJar");
            installedJar.setAccessible(true);
            require(installedJar.invoke(null) == null, "test environment should have no PATH mod origin");
            require(!V.verifyNow(), "missing PATH origin should fail");
            require(E.integrityUnavailable && !E.tampered, "missing origin must be unavailable, not mismatch");
            System.out.println("IntegritySmoke passed");
        } finally {
            Files.deleteIfExists(jar);
        }
    }

    private static void writeJar(Path jar, Map<String, byte[]> entries, byte[] inventory, String signature)
            throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("META-INF/gzwx-integrity.signature"));
            zip.write((Base64.getUrlEncoder().withoutPadding().encodeToString(inventory)
                    + "\n" + signature + "\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static boolean verify(KeyPair key, byte[] data, String signature) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key.getPublic());
            verifier.update(data);
            return verifier.verify(E.unhex(signature));
        } catch (Exception e) {
            return false;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            text.append(Character.forDigit((b >>> 4) & 15, 16));
            text.append(Character.forDigit(b & 15, 16));
        }
        return text.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
