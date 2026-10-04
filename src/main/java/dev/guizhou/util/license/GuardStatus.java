package dev.guizhou.util.license;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModMetadata;
import dev.guizhou.util.BuildConfig;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** The distribution guard supplements the existing per-action licence checks. */
public final class GuardStatus {
    private GuardStatus() {}
    private static volatile boolean resolved;
    private static MethodHandle authorization;

    public static boolean authorized() {
        if (!requiresGuard(null)) return true;
        if (!resolved) resolve();
        if (authorization == null) return false;
        // Cache only the call target, never the authorization result: revocation is read on every action.
        try { return (boolean) authorization.invokeExact(); }
        catch (Throwable ignored) { return false; }
    }

    /** Both business entrypoints enforce this even when the Fabric wrapper is bypassed. */
    public static void requireAuthorized() {
        if (!authorized()) throw new IllegalStateException("Rime authorization required before business initialization");
    }

    private static synchronized void resolve() {
        if (resolved) return;
        try {
            authorization = bind(Class.forName("d1.G"));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            authorization = null;
        }
        resolved = true;
    }

    static MethodHandle bind(Class<?> bridge) throws ReflectiveOperationException {
        // Reject wrong signatures/instance methods rather than adapting or boxing an unexpected ABI.
        return MethodHandles.publicLookup().findStatic(bridge, "isAuthorized", MethodType.methodType(boolean.class));
    }

    static boolean requiresGuard(ModMetadata ignored) {
        // Distribution metadata is editable, so it can never waive a production gate.
        // Development-authorized classes are rejected by every Jar task in build.gradle.
        return !(BuildConfig.DEVELOPMENT_BUILD && FabricLoader.getInstance().isDevelopmentEnvironment());
    }
}
