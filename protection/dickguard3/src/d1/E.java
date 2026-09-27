package d1;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guard state machine + launcher-edition markers.
 *
 * Edition markers (only present in launcher-distributed builds):
 *   1. fabric.mod.json version carries the -L suffix (visible in ModMenu)
 *   2. join chat banner shows account watermark + licence state (server-signed)
 *   3. <gameDir>/dicklient/edition.txt stores a self-verifiable signed
 *      transcript of the last successful heartbeat — ask for this file to
 *      tell an official copy from the plain website jar or a re-pack.
 */
final class E {
    private E() {}

    private static final SecureRandom RANDOM = new SecureRandom();

    static final int PENDING = 0, OK = 1, GRACE = 2, DENIED = 3;

    static volatile int state = PENDING;
    static volatile String reason = "";
    static volatile boolean launcher; // always true here; decoy for patchers
    static volatile boolean tampered; // one-way: integrity mismatch seen
    static volatile boolean integrityUnavailable; // verification failed without proving tampering
    static volatile String integrityIssue = "";
    static volatile String session;
    static volatile String entrySession, entryNonce, entryChallenge, entryCipher;

    static volatile String token;
    static volatile String wmA, wmH, wmT, wmS;
    static volatile String envNote; // javaagent/agentlib/jdwp/attach presence, reported not enforced
    static volatile long lastOkMs;
    static volatile long graceMs = 2 * 3600_000L;
    static volatile String accountShort = "????????";

    /** Appends a runtime environment finding to the next heartbeat's env field. */
    static synchronized void noteEnv(String tag) {
        if (tag == null || tag.isEmpty()) return;
        if (envNote != null) {
            for (String existing : envNote.split(";")) {
                if (existing.equals(tag)) return;
            }
        }
        envNote = (envNote == null || envNote.isEmpty()) ? tag : envNote + ";" + tag;
    }

    private static volatile long lastBannerMs;
    private static volatile boolean bannerShown;
    private static volatile long editionWrittenMs;

    /** Honeypot: classic "trial flag". Flipping it poisons the edition proof. */
    public static volatile boolean trial = true;
    /** Honeypot: fake license key material, never used by real paths. */
    public static final String KEY = "DICK-0000-0000-0000-0000";

    static void load() {
        String tel = res(S.g(2));
        if (tel != null) token = field(tel, "token");
        String wm = res(S.g(3));
        if (wm != null) {
            wmA = field(wm, "a");
            wmH = field(wm, "h");
            wmT = field(wm, "t");
            wmS = field(wm, "s");
        }
        try {
            StringBuilder env = new StringBuilder();
            for (String a : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                String s = a == null ? "" : a.toLowerCase(java.util.Locale.ROOT);
                if (s.contains("javaagent") || s.contains("agentlib") || s.contains("jdwp")) {
                    if (env.length() > 0) env.append(';');
                    env.append(s.length() > 60 ? s.substring(0, 60) : s);
                }
            }
            if (env.length() > 0) envNote = env.toString(); // absence of note = clean env
        } catch (Throwable ignore) {}
        if (token == null || token.isEmpty()) {
            state = DENIED;
            reason = "no_credential";
        }
    }

    static synchronized void start() {
        daemon("d1-hb", () -> {
            if (state == DENIED && "no_credential".equals(reason)) return; // nothing to phone with
            B.beatOnce();
            sleep(B.jitter(600_000, 0.15));
        });
        daemon("d1-iv", () -> {
            sleep(20_000);
            V.verifyNow();
            sleep(B.jitter(420_000, 0.2));
        });
        daemon("d1-ui", () -> {
            bannerPoll();
            sleep(2_000);
        });
    }

    static void confirm(Map<String, String> data) {
        state = OK;
        reason = "";
        lastOkMs = System.currentTimeMillis();
        String gs = data.get("graceSeconds");
        try {
            if (gs != null) graceMs = Math.min(Math.max(Long.parseLong(gs), 60) * 1000L, 24 * 3600_000L);
        } catch (Throwable ignore) {}
        if (wmA != null && wmA.length() >= 8) accountShort = wmA.substring(0, 8);
        if (V.lastResult && !tampered && !integrityUnavailable) {
            try {
                writeEdition();
            } catch (Throwable ignore) {}
        }
    }

    static void netFail() {
        if (lastOkMs > 0 && System.currentTimeMillis() - lastOkMs < graceMs) {
            if (state != DENIED) state = GRACE;
        } else {
            deny("network_timeout");
        }
    }

    static synchronized void deny(String why) {
        state = DENIED;
        reason = why;
        entrySession = entryNonce = entryChallenge = entryCipher = null;
        bannerShown = false; // let the banner switch to the warning line
    }

    static synchronized void tamper(String tag) {
        tampered = true; // reported by the next heartbeat (integrity=mismatch)
        integrityIssue = tag;
    }

    static void integrityOk() {
        // nothing user-visible; a clean copy simply keeps its green banner
    }

    static boolean official() {
        return G.g0 && trial && !tampered && !integrityUnavailable
                && V.lastResult && (state == OK || state == GRACE);
    }

    // ------------------------------------------------------------- markers

    static void bannerPoll() {
        try {
            Object player = mcPlayer();
            if (player == null) return;
            long now = System.currentTimeMillis();
            boolean refresh = now - lastBannerMs > 20 * 60_000;
            if (bannerShown && !refresh) return;
            if (state == OK) V.spotCheck(); // the green claim is re-earned per refresh
            String line;
            if (state == OK && official()) {
                line = "\u00a7d[Rime] \u00a7f\u542f\u52a8\u5668\u5b98\u65b9\u7248 \u00a7a\u2714 \u00a77| \u8d26\u53f7 " + accountShort
                        + (tampered ? " | \u00a7c\u5b8c\u6574\u6027\u5f02\u5e38" : " | \u00a77\u5b8c\u6574\u6027\u6821\u9a8c\u901a\u8fc7");
            } else if (tampered || integrityUnavailable) {
                line = "\u00a7c[Rime] \u5b8c\u6574\u6027\u65e0\u6cd5\u901a\u8fc7\uff08" + integrityIssue + "\uff09\uff0c\u5b98\u65b9\u7248\u6807\u8bb0\u505c\u7528";
            } else if (state == GRACE) {
                line = "\u00a7e[Rime] \u00a77\u542f\u52a8\u5668\u5b98\u65b9\u7248\uff08\u7f51\u7edc\u5bbd\u965c\u5bbd\u9650\u4e2d\uff0c\u7a0d\u540e\u91cd\u8bd5\uff09| \u8d26\u53f7 " + accountShort;
            } else if ("no_credential".equals(reason)) {
                line = "\u00a7c[Rime] \u00a77\u6b64\u526f\u672c\u975e\u542f\u52a8\u5668\u5b98\u65b9\u7248\uff08\u7f3a\u5c11\u5206\u53d1\u51ed\u636e\uff09\u2014\u2014 \u65e0\u5b98\u65b9\u6807\u8bb0\u3001\u4e0d\u53ef\u6eaf\u6e90\uff0c\u8bf7\u4ece\u542f\u52a8\u5668\u4e0b\u8f7d\u5b98\u65b9\u7248";
            } else {
                line = "\u00a7c[Rime] \u6388\u6743\u6821\u9a8c\u672a\u901a\u8fc7\uff08" + reason + "\uff09\uff0c\u5b98\u65b9\u7248\u6807\u8bb0\u505c\u7528";
            }
            if (chat(player, line)) {
                bannerShown = true;
                lastBannerMs = now;
            }
        } catch (Throwable ignore) {}
    }

    private static Object mcPlayer() {
        try {
            Class<?> mc = Class.forName(S.g(6));
            Object inst = mc.getMethod(S.g(7)).invoke(null);
            return mc.getField(S.g(8)).get(inst);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean chat(Object player, String text) {
        try {
            Class<?> comp = Class.forName(S.g(15));
            Object component = comp.getMethod(S.g(16), String.class).invoke(null, text);
            player.getClass().getMethod(S.g(17), comp).invoke(player, component);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void writeEdition() throws Exception {
        long now = System.currentTimeMillis();
        if (editionWrittenMs > 0 && now - editionWrittenMs < 30 * 60_000) return; // session-fresh transcript
        if (B.lastCanonical == null || B.lastSig == null) return;
        Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                .resolve(S.g(13));
        Files.createDirectories(dir);
        Path f = dir.resolve(S.g(14));
        String content = "Rime Launcher Edition - \u9a8c\u8bc1\u6863\u6848 (launcher-edition-v1)\n"
                + "\u6b64\u6587\u4ef6\u7531\u542f\u52a8\u5668\u5b98\u65b9\u7248\u5728\u6388\u6743\u6210\u529f\u540e\u751f\u6210\uff1b\u4f2a\u9020\u65e0\u6cd5\u901a\u8fc7\u7b7e\u540d\u6821\u9a8c\u3002\n"
                + "\u5c06 data \u4e0e sig \u4e24\u884c\u53d1\u7ed9\u5356\u5bb6\u5373\u53ef\u9a8c\u8bc1\u771f\u4f2a\u3002\n"
                + "data=" + B.lastCanonical + "\n"
                + "sig=" + B.lastSig + "\n";
        Files.write(f, content.getBytes(StandardCharsets.UTF_8));
        editionWrittenMs = now;
    }

    static void bootFail(Throwable t) {
        // never break the original client; leave a trace for support
        try {
            Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve(S.g(13));
            Files.createDirectories(dir);
            Files.write(dir.resolve("edition-boot-error.txt"),
                    String.valueOf(t).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignore) {}
    }

    // ------------------------------------------------------------- helpers

    static String hex(int bytes) {
        byte[] random = new byte[bytes];
        RANDOM.nextBytes(random);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : random) {
            sb.append(Character.forDigit((b >>> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    static byte[] unhex(String hex) {
        try {
            if (hex == null || (hex.length() & 1) != 0) return null;
            int n = hex.length() / 2;
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    static String field(String json, String name) {
        if (json == null) return null;
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** Extract every "k":"v" pair of the top-level object named <code>into</code>. */
    static Map<String, String> parseObject(String json, String into) {
        try {
            int i = json.indexOf("\"" + into + "\"");
            if (i < 0) return null;
            int open = json.indexOf('{', i);
            if (open < 0) return null;
            int depth = 0, end = -1;
            for (int p = open; p < json.length(); p++) {
                char c = json.charAt(p);
                if (c == '{') depth++;
                else if (c == '}') { depth--; if (depth == 0) { end = p; break; } }
            }
            if (end < 0) return null;
            String inner = json.substring(open + 1, end);
            Map<String, String> out = new LinkedHashMap<>();
            Matcher m = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(inner);
            while (m.find()) out.put(m.group(1), m.group(2));
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    static String parseStringField(String json, String name) {
        return field(json, name);
    }

    private static String res(String path) {
        try (InputStream is = E.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void daemon(String name, Runnable loop) {
        Thread t = new Thread(() -> {
            //noinspection InfiniteLoopStatement
            while (true) loop.run();
        }, name);
        t.setDaemon(true);
        t.start();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(Math.max(1000, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
