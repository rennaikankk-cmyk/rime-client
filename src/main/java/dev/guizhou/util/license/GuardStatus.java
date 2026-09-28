package dev.guizhou.util.license;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModMetadata;
import java.lang.reflect.Method;

/** The distribution guard supplements the existing per-action licence checks. */
public final class GuardStatus {
    private GuardStatus() {}
    private static volatile boolean resolved;
    private static Method authorization;
    private static boolean required;

    public static boolean authorized() {
        if (!resolved) resolve();
        if (!required) return true;
        if (authorization == null) return false;
        try { return Boolean.TRUE.equals(authorization.invoke(null)); }
        catch (ReflectiveOperationException | LinkageError ignored) { return false; }
    }

    private static synchronized void resolve() {
        if (resolved) return;
        required = true;
        try {
            var mod = FabricLoader.getInstance().getModContainer("guizhouware");
            required = mod.isPresent() && requiresGuard(mod.get().getMetadata());
            if (required) authorization = Class.forName("d1.G").getMethod("isAuthorized");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            authorization = null;
        }
        resolved = true;
    }

    static boolean requiresGuard(ModMetadata metadata) {
        return metadata.containsCustomValue("rimeguard") || metadata.containsCustomValue("dickguard");
    }
}
