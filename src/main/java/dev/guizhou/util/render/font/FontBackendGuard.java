package dev.guizhou.util.render.font;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Stops retrying a broken optional font backend; errors in the native fallback still propagate. */
final class FontBackendGuard {
    private volatile boolean enabled;
    private final BiConsumer<String, Throwable> report;
    FontBackendGuard(boolean enabled, BiConsumer<String, Throwable> report) {
        this.enabled = enabled;
        this.report = report;
    }
    boolean enabled() { return enabled; }
    <T> T use(String phase, Supplier<T> custom, Supplier<T> vanilla) {
        if (enabled) {
            try { return custom.get(); }
            catch (Throwable failure) { fail(phase, failure); }
        }
        return vanilla.get();
    }
    void fail(String phase, Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath fatal) throw fatal;
        synchronized (this) {
            if (!enabled) return;
            enabled = false;
        }
        report.accept(phase, failure);
    }
}
