package d1;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiPredicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

/**
 * Runtime integrity: verifies the server-signed entry inventory
 * (META-INF/gzwx-integrity.signature, written per-download by the auth
 * server after watermarking) against the actual bytes of every entry in
 * this jar. Any repack, class patch, injected entry or stripped watermark
 * breaks the signed inventory; the mismatch is reported through the next
 * heartbeat (protocol 2, integrity=mismatch → attributable auto-ban).
 */
final class V {
    private V() {}

    /** Honeypot: a "license file" crackers love to hunt for. Never read by real paths. */
    public static final String LICENSE_RES = "META-INF/dick.license";

    static volatile boolean lastResult = false;

    /** Cheap spot check: verify a few load-bearing entries against the signed
     *  inventory without walking the whole jar. Called before each heartbeat
     *  and before the join banner, so patching the full-check thread alone
     *  does not neutralise integrity verification. */
    static boolean spotCheck() {
        try {
            return spotCheck(installedJar(), B::vsig);
        } catch (Throwable t) {
            return unavailable("inventory_unreadable");
        }
    }

    static boolean spotCheck(Path jar, BiPredicate<byte[], String> signatureVerifier) {
        try {
            if (jar == null) return unavailable("missing_jar");
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                ZipEntry sigEntry = zf.getEntry(S.g(4));
                if (sigEntry == null) { E.tamper("no_inventory"); lastResult = false; return false; }
                String[] lines = read(zf.getInputStream(sigEntry)).split("\n");
                if (lines.length < 2) return unavailable("bad_inventory");
                // Distribution service encodes the complete inventory with standard Base64.
                // Individual entry names inside that inventory use base64url.
                byte[] inventory = Base64.getDecoder().decode(lines[0].trim());
                if (!signatureVerifier.test(inventory, lines[1].trim())) { E.tamper("bad_inventory_sig"); lastResult = false; return false; }
                if (!identityMatches(inventory)) return false;
                Map<String, String> expected = parseInventory(inventory);
                // Fixed entrypoints plus one other guard class per beat.
                List<String> targets = new ArrayList<>(List.of("d1/G.class", "fabric.mod.json"));
                List<String> guardClasses = new ArrayList<>();
                for (String name : expected.keySet()) {
                    if (name.startsWith("d1/") && name.endsWith(".class") && !name.equals("d1/G.class"))
                        guardClasses.add(name);
                }
                if (!guardClasses.isEmpty())
                    targets.add(guardClasses.get(ThreadLocalRandom.current().nextInt(guardClasses.size())));
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                for (String name : targets) {
                    ZipEntry e = zf.getEntry(name);
                    if (e == null) { E.tamper("gone:" + name); lastResult = false; return false; }
                    if (!expected.containsKey(name)) { E.tamper("unlisted:" + name); lastResult = false; return false; }
                    byte[] d = readAll(zf.getInputStream(e));
                    sha.reset();
                    byte[] h = sha.digest(d);
                    StringBuilder hex = new StringBuilder(64);
                    for (byte b : h) hex.append(String.format("%02x", b));
                    if (!expected.get(name).equals(hex.toString())) { E.tamper("hash:" + name); lastResult = false; return false; }
                }
                lastResult = true;
                return true;
            }
        } catch (Throwable t) {
            return unavailable("inventory_unreadable");
        }
    }

    private static boolean unavailable(String reason) {
        E.integrityUnavailable = true;
        E.integrityIssue = reason;
        lastResult = false;
        return false;
    }

    private static Path installedJar() {
        ModContainer container = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer(S.g(11)).orElse(null);
        if (container == null) return null;
        ModOrigin origin = container.getOrigin();
        if (origin.getKind() != ModOrigin.Kind.PATH) return null;
        List<Path> paths = origin.getPaths();
        if (paths.size() != 1 || !Files.isRegularFile(paths.get(0))) return null;
        return paths.get(0);
    }

    private static Map<String, String> parseInventory(byte[] inventory) {
        Map<String, String> expected = new HashMap<>();
        String invText = new String(inventory, StandardCharsets.UTF_8);
        for (String ln : invText.split("\n")) {
            String l = ln.trim();
            if (l.isEmpty() || l.startsWith(S.g(12)) || l.startsWith("subject=") || l.startsWith("license=")) continue;
            int sp = l.indexOf(' ');
            if (sp <= 0 || sp >= l.length() - 1) continue;
            try {
                String name = new String(Base64.getUrlDecoder().decode(l.substring(sp + 1)), StandardCharsets.UTF_8);
                expected.put(name, l.substring(0, sp).toLowerCase(Locale.ROOT));
            } catch (Throwable ignore) {}
        }
        return expected;
    }

    static boolean verifyNow() {
        try {
            return verifyNow(installedJar(), B::vsig);
        } catch (Throwable t) {
            return unavailable("inventory_unreadable");
        }
    }

    private static boolean identityMatches(byte[] inventory) {
        try {
            String[] lines = new String(inventory, StandardCharsets.UTF_8).split("\n", 4);
            if (lines.length < 3 || !S.g(12).equals(lines[0])
                    || !lines[1].matches("subject=[0-9a-f]{64}")
                    || !lines[2].startsWith("license=account_")) return unavailable("bad_inventory_header");
            if (E.token == null || E.token.isEmpty() || E.wmA == null || E.wmA.isEmpty())
                return unavailable("missing_identity");
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(E.token.getBytes(StandardCharsets.UTF_8));
            StringBuilder subject = new StringBuilder(64);
            for (byte b : digest) {
                subject.append(Character.forDigit((b >>> 4) & 15, 16));
                subject.append(Character.forDigit(b & 15, 16));
            }
            if (!lines[1].equals("subject=" + subject)) {
                E.tamper("subject_mismatch"); lastResult = false; return false;
            }
            if (!lines[2].equals("license=account_" + E.wmA)) {
                E.tamper("license_mismatch"); lastResult = false; return false;
            }
            return true;
        } catch (Throwable t) {
            return unavailable("bad_inventory_header");
        }
    }

    /** Package-scoped seam for exercising real JAR bytes with a test signing key. */
    static boolean verifyNow(Path jar, BiPredicate<byte[], String> signatureVerifier) {
        try {
            if (jar == null) return unavailable("missing_jar");

            try (ZipFile zf = new ZipFile(jar.toFile())) {
                ZipEntry sigEntry = zf.getEntry(S.g(4));
                if (sigEntry == null) { E.tamper("no_inventory"); lastResult = false; return false; }
                String sigText = read(zf.getInputStream(sigEntry));
                String[] lines = sigText.split("\n");
                if (lines.length < 2) { E.tamper("bad_inventory"); lastResult = false; return false; }
                byte[] inventory = Base64.getDecoder().decode(lines[0].trim());
                String sigHex = lines[1].trim();

                // 1) signature over the inventory text (covers header + every hash line)
                if (!signatureVerifier.test(inventory, sigHex)) { E.tamper("bad_inventory_sig"); lastResult = false; return false; }
                if (!identityMatches(inventory)) return false;

                // 2) parse the signed inventory into an expected-entry map
                Map<String, String> expected = new HashMap<>();
                String invText = new String(inventory, StandardCharsets.UTF_8);
                String[] invLines = invText.split("\n");
                for (int i = 0; i < invLines.length; i++) {
                    String ln = invLines[i].trim();
                    if (ln.isEmpty() || ln.startsWith(S.g(12)) || ln.startsWith("subject=") || ln.startsWith("license=")) continue;
                    int sp = ln.indexOf(' ');
                    if (sp <= 0 || sp >= ln.length() - 1) continue;
                    String hash = ln.substring(0, sp);
                    String name = new String(Base64.getUrlDecoder().decode(ln.substring(sp + 1)), StandardCharsets.UTF_8);
                    expected.put(name, hash.toLowerCase(Locale.ROOT));
                }

                // 3) every jar entry (except the signature entry itself) must be present and byte-identical
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                var en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory()) continue;
                    String name = e.getName();
                    if (name.equals(S.g(4))) continue;
                    byte[] data = readAll(zf.getInputStream(e));
                    sha.reset();
                    byte[] d = sha.digest(data);
                    StringBuilder hex = new StringBuilder(64);
                    for (byte b : d) hex.append(String.format("%02x", b));
                    String want = expected.remove(name);
                    if (want == null) { E.tamper("extra:" + name); lastResult = false; return false; }
                    if (!want.equals(hex.toString())) { E.tamper("hash:" + name); lastResult = false; return false; }
                }
                if (!expected.isEmpty()) { E.tamper("missing:" + expected.keySet().iterator().next()); lastResult = false; return false; }
                lastResult = true;
                E.integrityUnavailable = false;
                if (!E.tampered) E.integrityIssue = "";
                E.integrityOk();
                return true;
            }
        } catch (Throwable t) {
            return unavailable("inventory_unreadable");
        }
    }

    private static String read(InputStream is) throws Exception {
        return new String(readAll(is), StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream is) throws Exception {
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            try { is.close(); } catch (Exception ignore) {}
        }
    }
}
