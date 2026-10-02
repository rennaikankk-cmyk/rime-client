package dev.guizhou.util.license;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModMetadata;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** The distribution guard supplements the existing per-action licence checks. */
public final class GuardStatus {
    private GuardStatus() {}
    private static volatile boolean resolved;
    private static MethodHandle authorization;
    private static boolean required;

    public static boolean authorized() {
        if (!resolved) resolve();
        if (!required) return true;
        if (authorization == null) return false;
        // Cache only the call target, never the authorization result: revocation is read on every action.
        try { return (boolean) authorization.invokeExact(); }
        catch (Throwable ignored) { return false; }
    }

    private static synchronized void resolve() {
        if (resolved) return;
        required = true;
        try {
            var mod = FabricLoader.getInstance().getModContainer("guizhouware");
            required = mod.isPresent() && requiresGuard(mod.get().getMetadata());
            if (required) authorization = bind(Class.forName("d1.G"));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            authorization = null;
        }
        resolved = true;
    }

    static MethodHandle bind(Class<?> bridge) throws ReflectiveOperationException {
        // Reject wrong signatures/instance methods rather than adapting or boxing an unexpected ABI.
        return MethodHandles.publicLookup().findStatic(bridge, "isAuthorized", MethodType.methodType(boolean.class));
    }

    static boolean requiresGuard(ModMetadata metadata) {
        return metadata.containsCustomValue("rimeguard") || metadata.containsCustomValue("dickguard");
    }
}
