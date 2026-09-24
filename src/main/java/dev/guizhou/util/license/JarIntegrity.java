package dev.guizhou.util.license;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/** Signed file inventory for individually issued JARs. Read failures are not evidence of tampering. */
public final class JarIntegrity {
    public static final String ENTRY = "META-INF/gzwx-integrity.signature";
    public enum Result { VALID, TAMPERED, UNAVAILABLE }
    private static final int MAX_MANIFEST = 2 * 1024 * 1024;
    private JarIntegrity() {}

    public static Result verify(Path path, PublicKey key, String subject) {
        if (path == null || !Files.isRegularFile(path)) return Result.UNAVAILABLE;
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            var envelope = jar.getJarEntry(ENTRY);
            if (envelope == null) return Result.TAMPERED;
            byte[] bytes;
            try (InputStream input = jar.getInputStream(envelope)) { bytes = input.readNBytes(MAX_MANIFEST + 1); }
            if (bytes.length > MAX_MANIFEST) return Result.TAMPERED;
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            String[] outer = text.split("\n", -1);
            if (outer.length != 3 || !outer[2].isEmpty() || !outer[1].matches("[0-9a-f]{128}")) return Result.TAMPERED;
            byte[] payload = Base64.getDecoder().decode(outer[0]);
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(key); signature.update(payload);
            if (!signature.verify(HexFormat.of().parseHex(outer[1]))) return Result.TAMPERED;
            String[] lines = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(payload)).toString().split("\n", -1);
            if (lines.length < 5 || lines.length > 10_005 || !lines[0].equals("gzwx-integrity-v1")
                    || !lines[1].equals("subject=" + subject) || !lines[2].matches("license=(?:jar_[0-9]{4}_[0-9a-f]{4}|account_[0-9a-f]{8,64})")
                    || !lines[lines.length - 1].isEmpty()) return Result.TAMPERED;
            Map<String, String> expected = new HashMap<>();
            for (int i = 3; i < lines.length - 1; i++) {
                String line = lines[i];
                if (line.length() < 66 || line.charAt(64) != ' ' || !line.substring(0, 64).matches("[0-9a-f]{64}")) return Result.TAMPERED;
                String name = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(line.substring(65)))).toString();
                if (name.isEmpty() || name.equals(ENTRY) || name.endsWith("/") || expected.put(name, line.substring(0,64)) != null) return Result.TAMPERED;
            }
            Set<String> seen = new HashSet<>();
            long total = 0;
            int count = 0;
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (++count > 10_005 || !seen.add(entry.getName())) return Result.TAMPERED;
                if (entry.isDirectory() || entry.getName().equals(ENTRY)) continue;
                String digest = expected.remove(entry.getName());
                if (digest == null) return Result.TAMPERED;
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                try (InputStream input = jar.getInputStream(entry)) {
                    byte[] buffer = new byte[16_384];
                    int size;
                    while ((size = input.read(buffer)) != -1) {
                        total += size;
                        if (total > 128L * 1024 * 1024) return Result.TAMPERED;
                        sha.update(buffer, 0, size);
                    }
                }
                if (!digest.equals(HexFormat.of().formatHex(sha.digest()))) return Result.TAMPERED;
            }
            return expected.isEmpty() ? Result.VALID : Result.TAMPERED;
        } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException invalid) {
            return Result.TAMPERED;
        } catch (Exception unavailable) {
            return Result.UNAVAILABLE;
        }
    }
}
