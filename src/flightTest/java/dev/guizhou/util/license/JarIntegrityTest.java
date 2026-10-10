package dev.guizhou.util.license;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Signed synthetic files and ephemeral keys only; no production credentials. */
public final class JarIntegrityTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path folder = Files.createTempDirectory("gzwx-integrity-test-");
        String subject = "a".repeat(64);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("example.class", new byte[]{1,2,3}); files.put("assets/example.txt", "fixture".getBytes(StandardCharsets.UTF_8));
        StringBuilder body = new StringBuilder("gzwx-integrity-v1\nsubject=" + subject + "\nlicense=jar_0921_abcd\n");
        for (var entry : files.entrySet()) body.append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(entry.getValue())))
                .append(' ').append(Base64.getUrlEncoder().withoutPadding().encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8))).append('\n');
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("Ed25519"); signer.initSign(key.getPrivate()); signer.update(payload);
        byte[] certificate = (Base64.getEncoder().encodeToString(payload) + "\n" + HexFormat.of().formatHex(signer.sign()) + "\n").getBytes(StandardCharsets.UTF_8);
        files.put(JarIntegrity.ENTRY, certificate);
        try {
            Path jar = write(folder.resolve("valid.jar"), files);
            check(JarIntegrity.verify(jar,key.getPublic(),subject),JarIntegrity.Result.VALID,"Valid inventory");
            check(JarIntegrity.verify(jar,key.getPublic(),"b".repeat(64)),JarIntegrity.Result.TAMPERED,"Other licence");
            check(JarIntegrity.verify(jar,KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic(),subject),JarIntegrity.Result.TAMPERED,"Wrong issuer");
            Map<String,byte[]> altered=new LinkedHashMap<>(files); altered.put("example.class",new byte[]{9});
            check(JarIntegrity.verify(write(folder.resolve("changed.jar"),altered),key.getPublic(),subject),JarIntegrity.Result.TAMPERED,"Changed code");
            altered=new LinkedHashMap<>(files);altered.put("extra.class",new byte[]{1});
            check(JarIntegrity.verify(write(folder.resolve("extra.jar"),altered),key.getPublic(),subject),JarIntegrity.Result.TAMPERED,"Added code");
            altered=new LinkedHashMap<>(files);altered.remove("assets/example.txt");
            check(JarIntegrity.verify(write(folder.resolve("removed.jar"),altered),key.getPublic(),subject),JarIntegrity.Result.TAMPERED,"Missing file");
            altered=new LinkedHashMap<>(files);altered.remove(JarIntegrity.ENTRY);
            check(JarIntegrity.verify(write(folder.resolve("unsigned.jar"),altered),key.getPublic(),subject),JarIntegrity.Result.TAMPERED,"Missing signature");
            altered=new LinkedHashMap<>(files);altered.put(JarIntegrity.ENTRY,"malformed".getBytes(StandardCharsets.UTF_8));
            check(JarIntegrity.verify(write(folder.resolve("bad-signature.jar"),altered),key.getPublic(),subject),JarIntegrity.Result.TAMPERED,"Malformed signature");
            check(JarIntegrity.verify(folder.resolve("does-not-exist.jar"),key.getPublic(),subject),JarIntegrity.Result.UNAVAILABLE,"Missing path does not auto-ban");
            check(JarIntegrity.verify(folder,key.getPublic(),subject),JarIntegrity.Result.UNAVAILABLE,"Unsupported origin does not auto-ban");
            Path invalid=folder.resolve("unreadable-zip.jar");Files.writeString(invalid,"not a zip");
            check(JarIntegrity.verify(invalid,key.getPublic(),subject),JarIntegrity.Result.UNAVAILABLE,"I/O format failure does not auto-ban");
            byte[] accountPayload = new String(payload, StandardCharsets.UTF_8).replace("license=jar_0921_abcd", "license=account_1234abcd").getBytes(StandardCharsets.UTF_8);
            signer.initSign(key.getPrivate()); signer.update(accountPayload);
            altered = new LinkedHashMap<>(files);
            altered.put(JarIntegrity.ENTRY, (Base64.getEncoder().encodeToString(accountPayload) + "\n"
                    + HexFormat.of().formatHex(signer.sign()) + "\n").getBytes(StandardCharsets.UTF_8));
            check(JarIntegrity.verify(write(folder.resolve("account.jar"), altered), key.getPublic(), subject), JarIntegrity.Result.VALID, "Official member inventory");
            System.out.println("JarIntegrityTest passed: " + checks + " checks");
        } finally {
            try(var paths=Files.list(folder)){for(Path p:paths.toList())Files.delete(p);} Files.delete(folder);
        }
    }
    private static Path write(Path path,Map<String,byte[]> entries)throws Exception {
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(path))){
            for(var entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}
        }return path;
    }
    private static void check(JarIntegrity.Result actual,JarIntegrity.Result expected,String label){checks++;if(actual!=expected)throw new AssertionError(label+": "+actual);}
}
