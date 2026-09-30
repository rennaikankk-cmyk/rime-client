import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import javax.tools.*;
import javax.tools.JavaFileObject.Kind;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * GuardMerge — assembles the guarded client jar with two business-layer hardening passes.
 *
 * Pass A (string pooling): LDC string constants in business classes are replaced by
 *   INVOKESTATIC d1/P.g(idx). Plaintext ships only inside an encrypted pool
 *   (SHA-256(secret+idx) XOR over UTF-8 bytes; secret split into two fragments,
 *   gen_s.py style). Module names / descriptions / command text stop being
 *   readable constants.
 *
 * Pass B (filesystem-hostile class names): business class internal names get a
 *   zero-width suffix so unzipped trees produce invisible-suffix names that fail
 *   naive filename↔this_class consistency checks.
 *   Modes: off | cfa (U+200E suffix — python-zipfile-safe, the default) |
 *   nul (NUL+LRM, Obelisk form — also hostile to python tooling; only enable when
 *   the whole release pipeline is NUL-aware).
 *
 * fabric.mod.json: verifies reviewed entrypoints, points them at d1.G, and appends
 * a NEUTRAL edition suffix (no protection-mechanism inventory).
 *
 * Usage:
 *   java GuardMerge --original=client.jar --guard=guard-obf.jar --output=out.jar
 *        [--strings=on|off] [--names=off|cfa|nul] [--keep-prefix=pkg/,pkg2/]
 */
public class GuardMerge {

    /** Product profiles: entrypoint contract + per-product skip prefixes. */
    static final class Profile {
        final String name;
        final String expectEntry;      // original entry class FQN (dotted), must be referenced by entrypoints
        final boolean keepMain;        // rime: main+client both -> d1.G ; dick: client -> d1.G, main removed
        final String[] skipPrefixes;   // classes exempt from BOTH passes
        Profile(String name, String expectEntry, boolean keepMain, String[] skipPrefixes) {
            this.name = name; this.expectEntry = expectEntry; this.keepMain = keepMain; this.skipPrefixes = skipPrefixes;
        }
    }

    static final Profile RIME = new Profile("rime", "dev.guizhou.GuizhouWare", true, new String[]{
            "d1/",                             // guard itself
            "dev/guizhou/mixin/",              // mixins.json refs + annotation strings stay literal
            "dev/guizhou/GuizhouWare",         // entrypoint: server sends this exact name (entryCipher)
            "dev/guizhou/features/settings/",  // kept classes resolved by EnumSet/name at runtime
            "dev/guizhou/event/system/"        // kept annotation + reflective EventBus scanning
    });

    static final Profile DICK = new Profile("dick", "com.dickclient.DickClient", false, new String[]{
            "d1/",
            "com/dickclient/"
    });

    static Profile profile = RIME;

    static boolean isClassFile(byte[] b) {
        return b != null && b.length > 8 && (b[0] & 0xFF) == 0xCA && (b[1] & 0xFF) == 0xFE
                && (b[2] & 0xFF) == 0xBA && (b[3] & 0xFF) == 0xBE;
    }

    public static void main(String[] args) throws Exception {
        String original = null, guard = null, output = null;
        boolean strings = true;
        String names = "cfa";
        String nativeHash = null, bridge = null;
        List<String> extraKeep = new ArrayList<>();
        for (String a : args) {
            if (a.startsWith("--original=")) original = a.substring(11);
            else if (a.startsWith("--guard=")) guard = a.substring(8);
            else if (a.startsWith("--output=")) output = a.substring(9);
            else if (a.startsWith("--strings=")) strings = a.endsWith("on");
            else if (a.startsWith("--names=")) names = a.substring(8);
            else if (a.startsWith("--keep-prefix=")) extraKeep.addAll(Arrays.asList(a.substring(14).split(",")));
            else if (a.startsWith("--native-hash=")) nativeHash = a.substring(14);
            else if (a.startsWith("--bridge=")) bridge = a.substring(9);
            else if (a.startsWith("--profile=")) {
                String p = a.substring(10);
                profile = p.equals("rime") ? RIME : p.equals("dick") ? DICK : null;
                if (profile == null) throw new IllegalArgumentException("--profile must be rime|dick");
            }
        }
        if (original == null || guard == null || output == null) {
            System.err.println("usage: GuardMerge --original=.. --guard=.. --output=.. [--profile=rime|dick] [--strings=on|off] [--names=off|cfa|nul] [--keep-prefix=..]");
            System.exit(2);
        }
        if (!names.equals("off") && !names.equals("cfa") && !names.equals("nul"))
            throw new IllegalArgumentException("--names must be off|cfa|nul");
        if ((nativeHash == null) != (bridge == null))
            throw new IllegalArgumentException("--native-hash and --bridge must be passed together");

        List<String> skips = new ArrayList<>(Arrays.asList(profile.skipPrefixes));
        skips.addAll(extraKeep);

        Map<String, byte[]> business = new LinkedHashMap<>();
        Map<String, byte[]> resources = new LinkedHashMap<>();
        try (JarFile jf = new JarFile(original)) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                byte[] b = jf.getInputStream(e).readAllBytes();
                (e.getName().endsWith(".class") ? business : resources).put(e.getName(), b);
            }
        }
        if (!resources.containsKey("fabric.mod.json"))
            throw new IllegalStateException("original jar has no fabric.mod.json");
        Map<String, byte[]> guardClasses = new LinkedHashMap<>();
        try (JarFile jf = new JarFile(guard)) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                if (e.isDirectory() || !e.getName().endsWith(".class")) continue;
                if (business.containsKey(e.getName()) || resources.containsKey(e.getName()))
                    throw new IllegalStateException("guard entry collides with original: " + e.getName());
                guardClasses.put(e.getName(), jf.getInputStream(e).readAllBytes());
            }
        }
        if (!guardClasses.containsKey("d1/G.class"))
            throw new IllegalStateException("guard jar has no d1/G.class entrypoint");

        // ---------- native tier: bridge classes + required-mode stamp ----------
        Map<String, byte[]> bridgeClasses = new LinkedHashMap<>();
        if (bridge != null) {
            try (JarFile jf = new JarFile(bridge)) {
                Enumeration<JarEntry> en = jf.entries();
                while (en.hasMoreElements()) {
                    JarEntry e = en.nextElement();
                    if (e.isDirectory() || !e.getName().endsWith(".class")) continue;
                    if (business.containsKey(e.getName()) || resources.containsKey(e.getName())
                            || guardClasses.containsKey(e.getName()))
                        throw new IllegalStateException("bridge entry collides: " + e.getName());
                    bridgeClasses.put(e.getName(), jf.getInputStream(e).readAllBytes());
                }
            }
            if (!bridgeClasses.containsKey("dev/rimeguard/bridge/NativeGuard.class"))
                throw new IllegalStateException("bridge jar has no NativeGuard class");
            resources.put("META-INF/rimeguard-native.json",
                    ("{\"v\": 1, \"h\": \"" + nativeHash + "\", \"mode\": \"required\"}")
                            .getBytes(StandardCharsets.UTF_8));
        }

        // ---------- Pass A: string pooling ----------
        Map<String, Integer> pool = new LinkedHashMap<>();
        if (strings)
            for (Map.Entry<String, byte[]> e : business.entrySet())
                if (!skip(e.getKey(), skips) && isClassFile(e.getValue())) scanLdc(e.getValue(), pool);
        byte[] poolClass = null;
        if (!pool.isEmpty()) {
            SecureRandom rnd = new SecureRandom();
            byte[] secret = new byte[32], fragA = new byte[32], fragB = new byte[32];
            rnd.nextBytes(secret);
            rnd.nextBytes(fragA);
            for (int i = 0; i < 32; i++) fragB[i] = (byte) (secret[i] ^ fragA[i]);
            poolClass = compilePoolClass(pool, secret, fragA, fragB);
        }
        int pooledClasses = 0;
        if (strings) {
            Map<String, byte[]> out = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> e : business.entrySet()) {
                if (skip(e.getKey(), skips) || !isClassFile(e.getValue())) { out.put(e.getKey(), e.getValue()); continue; }
                byte[] r = rewriteLdc(e.getValue(), pool);
                if (r != e.getValue()) pooledClasses++;
                out.put(e.getKey(), r);
            }
            business = out;
        }

        // ---------- Pass B: filesystem-hostile class names ----------
        Map<String, String> rename = new LinkedHashMap<>();
        if (!names.equals("off")) {
            for (String name : business.keySet()) {
                if (skip(name, skips) || !isClassFile(business.get(name))) continue;
                String internal = name.substring(0, name.length() - 6);
                String pkg = internal.contains("/") ? internal.substring(0, internal.lastIndexOf('/') + 1) : "";
                String simple = internal.substring(internal.lastIndexOf('/') + 1);
                rename.put(internal, pkg + (names.equals("nul") ? "\u0000" : "") + simple + "\u200e");
            }
            if (!rename.isEmpty()) {
                Remapper rm = new Remapper() {
                    @Override public String map(String n) { String m = rename.get(n); return m != null ? m : n; }
                };
                Map<String, byte[]> out = new LinkedHashMap<>();
                for (Map.Entry<String, byte[]> e : business.entrySet()) {
                    String internal = e.getKey().substring(0, e.getKey().length() - 6);
                    ClassReader cr = new ClassReader(e.getValue());
                    ClassWriter cw = new ClassWriter(0);
                    cr.accept(new ClassRemapper(cw, rm), 0);
                    out.put(rename.getOrDefault(internal, internal) + ".class", cw.toByteArray());
                }
                business = out;
            }
        }

        // ---------- fabric.mod.json ----------
        String json = new String(resources.get("fabric.mod.json"), StandardCharsets.UTF_8);
        String entryLit = "\"" + profile.expectEntry + "\"";
        String entryRe = "\"" + profile.expectEntry.replace(".", "\\.") + "\"";
        int epCount = json.split(entryRe, -1).length - 1;
        int required = profile.keepMain ? 2 : 1; // rime: main+client both reviewed; dick: client only
        if (epCount < required) throw new IllegalStateException("reviewed entrypoints missing (found " + epCount + ", need " + required + ")");
        json = json.replace(entryLit, "\"d1.G\"");
        if (!profile.keepMain) {
            // dick profile: strip remaining entrypoints (e.g. main) so nothing runs before the guard
            json = json.replaceFirst(",?\s*\"main\"\\s*:\\s*\\[[^\\]]*\\]", "");
        }
        json = json.replaceFirst("(\"description\"\\s*:\\s*\")([^\"]*)(\")", "$1$2 Launcher Edition.$3");
        // guard version marker (release tooling and smoke checks read it)
        String guardKey = profile == RIME ? "rimeguard" : "dickguard";
        if (json.contains("\"" + guardKey + "\"")) {
            json = json.replaceFirst("(\"" + guardKey + "\"\\s*:\\s*\")[^\"]*(\")", "$13.2$2");
        } else if (json.contains("\"custom\"")) {
            json = json.replaceFirst("(\"custom\"\\s*:\\s*\\{)", "$1\"" + guardKey + "\": \"3.2\", ");
        } else {
            // real newlines: "\n" in a replaceFirst replacement is the escape for
            // literal 'n' and used to emit a broken JSON tail here
            json = json.replaceFirst("\\s*\\}$",
                    ",\n  \"custom\": {\n    \"" + guardKey + "\": \"3.2\"\n  }\n}");
        }
        resources.put("fabric.mod.json", json.getBytes(StandardCharsets.UTF_8));

        // ---------- write (plain ZipOutputStream: no auto-manifest; MANIFEST stays first) ----------
        Path outp = Paths.get(output);
        if (outp.getParent() != null) Files.createDirectories(outp.getParent());
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outp.toFile()))) {
            byte[] man = resources.remove("META-INF/MANIFEST.MF");
            if (man != null) write(zos, "META-INF/MANIFEST.MF", man);
            if (poolClass != null) write(zos, "d1/P.class", poolClass);
            for (Map.Entry<String, byte[]> e : resources.entrySet()) write(zos, e.getKey(), e.getValue());
            for (Map.Entry<String, byte[]> e : business.entrySet()) write(zos, e.getKey(), e.getValue());
            for (Map.Entry<String, byte[]> e : bridgeClasses.entrySet()) write(zos, e.getKey(), e.getValue());
            for (Map.Entry<String, byte[]> e : guardClasses.entrySet()) write(zos, e.getKey(), e.getValue());
        }
        System.out.printf("GuardMerge ok: entries=%d pooledStrings=%d pooledClasses=%d renamedClasses=%d names=%s native=%s%n",
                resources.size() + business.size() + guardClasses.size() + bridgeClasses.size() + (poolClass != null ? 1 : 0),
                pool.size(), pooledClasses, rename.size(), names, nativeHash != null ? nativeHash : "off");
    }

    static boolean skip(String entryName, List<String> skips) {
        for (String p : skips) if (entryName.startsWith(p)) return true;
        return false;
    }

    static void write(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    // ===== Pass A =====

    static boolean poolable(String s) {
        if (s.length() < 4) return false;
        boolean numeric = true;
        for (char c : s.toCharArray()) if (!Character.isDigit(c) && c != '.') { numeric = false; break; }
        return !numeric;
    }

    static void scanLdc(byte[] clazz, Map<String, Integer> pool) {
        new ClassReader(clazz).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int acc, String n, String d, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitLdcInsn(Object v) {
                        if (v instanceof String s && poolable(s)) pool.computeIfAbsent(s, k -> pool.size());
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
    }

    static byte[] rewriteLdc(byte[] clazz, Map<String, Integer> pool) {
        boolean[] touched = {false};
        ClassReader cr = new ClassReader(clazz);
        ClassWriter cw = new ClassWriter(0); // String→String: verifier types unchanged, original frames stay valid
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override public MethodVisitor visitMethod(int acc, String n, String d, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(acc, n, d, sig, ex);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override public void visitLdcInsn(Object v) {
                        Integer idx = (v instanceof String s) ? pool.get(s) : null;
                        if (idx != null) {
                            pushInt(mv, idx);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "d1/P", "g", "(I)Ljava/lang/String;", false);
                            touched[0] = true;
                        } else {
                            mv.visitLdcInsn(v);
                        }
                    }
                };
            }
        }, 0);
        return touched[0] ? cw.toByteArray() : clazz;
    }

    static void pushInt(MethodVisitor mv, int v) {
        if (v >= -1 && v <= 5) mv.visitInsn(Opcodes.ICONST_0 + v);
        else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) mv.visitIntInsn(Opcodes.BIPUSH, v);
        else mv.visitIntInsn(Opcodes.SIPUSH, v);
    }

    /** d1/P source compiled in-process (gen_s.py pattern); decrypts to UTF-8 bytes then decodes. */
    static byte[] compilePoolClass(Map<String, Integer> pool, byte[] secret, byte[] fragA, byte[] fragB) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String[] plain = pool.keySet().toArray(new String[0]);
        // split encrypted entries into chunk methods: a single <clinit> array literal
        // blows the 64KB Code limit on large pools (measured on the business jar).
        final int CHUNK = 120;
        List<String> chunkMethods = new ArrayList<>();
        StringBuilder initD = new StringBuilder();
        for (int c0 = 0; c0 < plain.length; c0 += CHUNK) {
            int cEnd = Math.min(c0 + CHUNK, plain.length);
            StringBuilder body = new StringBuilder();
            for (int i = c0; i < cEnd; i++) {
                md.reset();
                byte[] key = md.digest(concat(secret, Integer.toString(i).getBytes(StandardCharsets.ISO_8859_1)));
                byte[] p = plain[i].getBytes(StandardCharsets.UTF_8);
                if (body.length() > 0) body.append(",\n");
                body.append("            { ").append(xorDecl(p, key)).append(" }");
            }
            chunkMethods.add("    static byte[][] c" + (c0 / CHUNK) + "() { return new byte[][]{\n"
                    + body + "\n    }; }\n");
            initD.append(c0 == 0 ? "            D = c0();\n"
                                 : "            D = cat(D, c" + (c0 / CHUNK) + "());\n");
        }
        String src = "package d1;\n"
                + "/** Business string pool (generated by GuardMerge; plaintext never ships as LDC). */\n"
                + "public final class P {\n"
                + "    private P() {}\n"
                + "    public static String g(int id) {\n"
                + "        try {\n"
                + "            byte[] d0 = D[id];\n"
                + "            byte[] sec = new byte[A.length];\n"
                + "            for (int i = 0; i < A.length; i++) sec[i] = (byte) (A[i] ^ K[i]);\n"
                + "            java.security.MessageDigest md = java.security.MessageDigest.getInstance(\"SHA-256\");\n"
                + "            byte[] key = md.digest((new String(sec, java.nio.charset.StandardCharsets.ISO_8859_1) + id)\n"
                + "                    .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));\n"
                + "            byte[] out = new byte[d0.length];\n"
                + "            for (int i = 0; i < d0.length; i++) out[i] = (byte) (d0[i] ^ key[i % key.length]);\n"
                + "            return new String(out, java.nio.charset.StandardCharsets.UTF_8);\n"
                + "        } catch (Throwable t) {\n"
                + "            return \"\";\n"
                + "        }\n"
                + "    }\n"
                + "    static final byte[] A = { " + bytesDecl(fragA) + " };\n"
                + "    static final byte[] K = { " + bytesDecl(fragB) + " };\n"
                + "    static byte[][] D;\n"
                + "    static {\n" + initD + "    }\n"
                + "    static byte[][] cat(byte[][] a, byte[][] b) {\n"
                + "        byte[][] r = new byte[a.length + b.length][];\n"
                + "        System.arraycopy(a, 0, r, 0, a.length);\n"
                + "        System.arraycopy(b, 0, r, a.length, b.length);\n"
                + "        return r;\n"
                + "    }\n"
                + String.join("", chunkMethods)
                + "}\n";

        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        if (jc == null) throw new IllegalStateException("JDK compiler unavailable (run with a full JDK)");
        class Mem extends SimpleJavaFileObject {
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            Mem(String name, Kind kind) { super(URI.create("mem:///" + name.replace('.', '/') + kind.extension), kind); }
            @Override public CharSequence getCharContent(boolean ignore) { return src; }
            @Override public OutputStream openOutputStream() { return bos; }
        }
        Mem srcObj = new Mem("d1/P", Kind.SOURCE);
        Mem clsObj = new Mem("d1/P", Kind.CLASS);
        javax.tools.DiagnosticCollector<javax.tools.JavaFileObject> diag = new javax.tools.DiagnosticCollector<>();
        JavaFileManager fm = new ForwardingJavaFileManager<>(jc.getStandardFileManager(diag, null, null)) {
            @Override public JavaFileObject getJavaFileForOutput(Location loc, String name, Kind kind, FileObject sibling) {
                return clsObj;
            }
        };
        boolean ok = jc.getTask(null, fm, diag, List.of("--release", "8"), null, List.of(srcObj)).call();
        if (!ok) {
            for (var dm : diag.getDiagnostics()) System.err.println("pool-src diag: " + dm);
            throw new IllegalStateException("pool class compilation failed");
        }
        return clsObj.bos.toByteArray();
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    static String xorDecl(byte[] plain, byte[] key) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < plain.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append("(byte) 0x").append(String.format("%02x", plain[i] ^ key[i % key.length]));
        }
        return sb.toString();
    }

    static String bytesDecl(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(i % 12 == 0 ? ",\n            " : ", ");
            sb.append("(byte) 0x").append(String.format("%02x", b[i]));
        }
        return sb.toString();
    }
}
