package d1;

import java.lang.reflect.Method;

/**
 * Environment witness (v2.1): looks for traces of a loaded java agent.
 *
 * v2.0 lesson (do not repeat): on Windows the HotSpot attach pipe and the
 * "Attach Listener" thread are created eagerly at JVM startup — their mere
 * presence proves nothing. On Linux they are lazy, but our audience is
 * Windows, so thread-name probing is a constant-true signal there.
 *
 * What actually survives an external jdk.attach: the agent's classes get
 * defined into the system class loader. There is no public "is loaded"
 * API, but ClassLoader.findLoadedClass(name) is exactly that lookup and is
 * reachable via reflection without triggering a load. We probe a set of
 * characteristic agent class names (common tooling conventions plus the
 * names our own dump toolchain ships with — the likeliest recycled tools).
 *
 * Self-attach elevation leaves the jdk.attach.allowAttachSelf system
 * property set; a clean launch never defines it.
 *
 * Findings are REPORTED, never enforced locally: the heartbeat carries them
 * in the existing env field and the auth server decides.
 */
final class W {
    private W() {}

    /** One-way latch: report the agent finding once, not every beat. */
    private static volatile boolean agentFound;

    /** One-way latch for the native tier's static facts (version/hash/hwid). */
    private static volatile boolean ngFactsReported;

    /** Probe re-run on every heartbeat — agents can attach at any moment. */
    static void probe() {
        nativeWitness();
        if (!agentFound) {
            Method flc = null;
            try {
                flc = ClassLoader.class.getDeclaredMethod(S.g(19), String.class);
                flc.setAccessible(true); // strong encapsulation may refuse -> fallback below
            } catch (Throwable t) { flc = null; }
            ClassLoader sys = ClassLoader.getSystemClassLoader();
            for (int i = 0; i < OBF.length; i++) {
                String cn = deobf(i);
                try {
                    boolean hit;
                    if (flc != null) {
                        hit = flc.invoke(sys, cn) != null;
                    } else {
                        // initialize=false: links without running any agent static init
                        Class.forName(cn, false, sys);
                        hit = true;
                    }
                    if (hit) { E.noteEnv("agent:" + cn); agentFound = true; break; }
                } catch (ClassNotFoundException ignore) {
                } catch (Throwable ignore) {}
            }
        }
        try {
            if (System.getProperty(S.g(20)) != null) E.noteEnv("selfattach");
        } catch (Throwable ignore) {}
    }

    /**
     * Native-tier witness (rimeguard-native dll via the bridge).
     *
     * The Java-side probes above read spoofable JVM surfaces (management
     * properties, class loaders); the dll reads the real process command
     * line and debugger flags. Same discipline: findings are REPORTED in the
     * env field only — the auth server correlates ngp=/ngh= tags with the
     * release-expected dll hash and decides. Absent dll = no tags = older
     * payload shape, so non-native builds stay byte-identical.
     */
    private static void nativeWitness() {
        try {
            if (!dev.rimeguard.bridge.NativeGuard.available()) return;
            if (!ngFactsReported) {
                ngFactsReported = true;
                E.noteEnv("ng=" + (dev.rimeguard.bridge.NativeGuard.version() & 0xFF));
                E.noteEnv("ngh=" + hex(dev.rimeguard.bridge.NativeGuard.selfHash()));
                E.noteEnv("ngw=" + hex8(dev.rimeguard.bridge.NativeGuard.hwid()));
            }
            int p = dev.rimeguard.bridge.NativeGuard.envProbe();
            if (p > 0) E.noteEnv("ngp=" + p); // noteEnv dedupes; escalating bits append
        } catch (Throwable ignore) {}
    }

    /**
     * Native hwid (hex), null when the native tier is absent. Sent as a
     * first-class heartbeat field on every beat — not an env note — so the
     * server can bind and then verify the auth material against a payload
     * shape it expects of native builds; the strippable env copy above stays
     * as telemetry only.
     */
    static String nativeHwidHex() {
        try {
            if (!dev.rimeguard.bridge.NativeGuard.available()) return null;
            byte[] w = dev.rimeguard.bridge.NativeGuard.hwid();
            return w != null && w.length == 32 ? hex(w) : null;
        } catch (Throwable t) { return null; }
    }

    /**
     * Per-beat authorization material over this heartbeat's nonce (Future
     * form): the environment state is sampled inside the dll at call time and
     * woven into the digest — no Java-side branch on any probe result exists
     * to locate and NOP, and the server answers a mismatch with an ordinary
     * hwid rejection rather than naming a detection. Null when the native
     * tier is absent (legacy payloads stay byte-identical).
     */
    static String authMaterial(String nonceHex) {
        try {
            if (!dev.rimeguard.bridge.NativeGuard.available()) return null;
            byte[] m = dev.rimeguard.bridge.NativeGuard.authMaterial(E.unhex(nonceHex));
            return m != null && m.length == 32 ? hex(m) : null;
        } catch (Throwable t) { return null; }
    }

    private static String hex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) sb.append(String.format("%02x", v));
        return sb.toString();
    }

    private static String hex8(byte[] b) {
        if (b == null || b.length < 8) return "";
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 8; i++) sb.append(String.format("%02x", b[i]));
        return sb.toString();
    }

    /** Characteristic agent class names, XOR-split so no single searchable
     *  constant appears in the binary; reassembled per probe (gen_names.py). */
    private static final int[] MASK = { 0x35, 0x5a, 0x71, 0x24, 0x6f, 0x18, 0x42, 0x3d, 0x57, 0x29, 0x4e, 0x63 };
    private static final char[][] OBF = {
        { 0x0071, 0x002f, 0x001c, 0x0054, 0x002e, 0x007f, 0x0027, 0x0053, 0x0023 },
        { 0x0008, 0x0014, 0x0050, 0x001d, 0x0079, 0x002c, 0x004e, 0x0031, 0x0046, 0x003c, 0x000e, 0x0071, 0x002f, 0x001c, 0x0054 },
        { 0x0021, 0x004b, 0x0000, 0x0074, 0x0007, 0x0045, 0x0027, 0x0046, 0x003c, 0x0017, 0x0074, 0x003d, 0x0014, 0x004a, 0x001b },
        { 0x006e, 0x000e, 0x006e, 0x0023, 0x007c, 0x0030, 0x004c, 0x0020, 0x0017 },
        { 0x002e, 0x007f, 0x0027, 0x0053, 0x0023, 0x0064, 0x002f, 0x000a, 0x005b },
        { 0x0051, 0x002c, 0x004e, 0x0023, 0x005b, 0x003b, 0x000e, 0x0050, 0x0034, 0x0005, 0x0065, 0x0008, 0x007d, 0x002c, 0x0049 },
        { 0x0001, 0x0051, 0x0036, 0x005a, 0x003d, 0x0027, 0x0040, 0x0037, 0x0001, 0x0041, 0x001d },
    };    private static String deobf(int i) {
        char[] c = OBF[i].clone(); // never mutate the table: probe runs every beat
        for (int j = 0; j < c.length; j++) c[j] ^= (char) MASK[(i + j) % MASK.length];
        return new String(c);
    }
}
