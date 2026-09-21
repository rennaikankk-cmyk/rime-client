package net.minecraft.gizmos;

/**
 * 1.21.1 port shim: a queued gizmo handle. {@link #setAlwaysOnTop()} retags the
 * queued entry so the flush draws it in the no-depth pass.
 */
public final class Gizmo {

    private Runnable retag;

    Gizmo(Runnable retag) {
        this.retag = retag;
    }

    public Gizmo setAlwaysOnTop() {
        if (retag != null) retag.run();
        return this;
    }
}
