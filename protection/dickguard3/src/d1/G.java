package d1;

import net.fabricmc.api.ClientModInitializer;

/**
 * Launcher-edition bootstrap. Replaces the original entrypoint in the merged
 * jar; the original author's classes are launched byte-identical via
 * reflection only after a signed licence response has been verified.
 */
public final class G implements net.fabricmc.api.ModInitializer, ClientModInitializer {

    /** Honeypot: patching this to false silently poisons the edition proof. */
    public static boolean g0 = true;

    private static Object original;

    public static boolean isAuthorized() {
        long elapsed = System.currentTimeMillis() - E.lastOkMs;
        return E.official() && elapsed >= 0 && elapsed < E.graceMs;
    }

    @Override
    public void onInitializeClient() {
        if (original == null || !isAuthorized()) {
            E.deny("main_not_authorized");
            return;
        }
        try { original.getClass().getMethod(S.g(10)).invoke(original); }
        catch (Throwable t) { E.deny("client_init_failed"); E.bootFail(t); }
    }

    @Override
    public void onInitialize() {
        nativeGate();
        try {
            E.load();
        } catch (Throwable t) {
            E.state = E.DENIED;
            E.reason = "boot";
        }
        // Fabric's development-environment flag is not an authorization signal.
        // Check synchronously so the original entrypoint never runs while a
        // licence decision is still pending or the network is unavailable.
        if (E.state != E.DENIED) {
            V.verifyNow();
            B.beatOnce();
        }
        if (E.state != E.OK || E.tampered || E.integrityUnavailable || !V.lastResult) {
            String cause = E.tampered ? "integrity_mismatch" :
                    E.integrityUnavailable || !V.lastResult ? "integrity_unavailable" : "license_" + E.reason;
            E.bootFail(new IllegalStateException(cause));
            return;
        }
        try {
            E.start();
        } catch (Throwable t) {
            E.state = E.DENIED;
            E.reason = "boot2";
            E.bootFail(t);
            return;
        }
        try {
            Class<?> c = Class.forName(S.g(9));
            Object o = c.getDeclaredConstructor().newInstance();
            c.getMethod("onInitialize").invoke(o);
            original = o;
        } catch (Throwable t) {
            E.bootFail(t);
        }
    }

    /**
     * Native-tier gate: builds stamped by GuardMerge --native carry
     * META-INF/rimeguard-native.json with mode "required"; the dll must then
     * be present and speak protocol >= 1 or the client never starts. Builds
     * without the stamp (dev, free line) skip the gate entirely.
     */
    private static void nativeGate() {
        byte[] meta;
        try (java.io.InputStream is = G.class.getClassLoader()
                .getResourceAsStream("META-INF/rimeguard-native.json")) {
            meta = is == null ? null : is.readAllBytes();
        } catch (Throwable t) {
            E.deny("native_meta");
            E.bootFail(t);
            return;
        }
        if (meta == null) return;
        if (!new String(meta, java.nio.charset.StandardCharsets.UTF_8).contains("\"required\"")) return;
        try {
            if (!dev.rimeguard.bridge.NativeGuard.available()
                    || (dev.rimeguard.bridge.NativeGuard.version() & 0xFF) < 1) {
                E.deny("native_missing");
                E.bootFail(new IllegalStateException("native_missing"));
            }
        } catch (Throwable t) {
            E.deny("native_missing");
            E.bootFail(t);
        }
    }
}
