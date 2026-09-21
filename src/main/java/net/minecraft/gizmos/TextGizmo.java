package net.minecraft.gizmos;

/**
 * 1.21.1 port shim for the 1.21.11 text gizmo. Only the surface used by
 * PlacementLabels is provided: a color/centering/scale style and the
 * always-on-top flag applied to the queued billboard text.
 */
public final class TextGizmo {

    public record Style(int colorARGB, boolean centered, float scale) {

        public static Style forColorAndCentered(int colorARGB) {
            return new Style(colorARGB, true, 1.0f);
        }

        public Style withScale(float scale) {
            return new Style(this.colorARGB, this.centered, scale);
        }
    }

    private Runnable retag;

    TextGizmo(Runnable retag) {
        this.retag = retag;
    }

    public TextGizmo setAlwaysOnTop() {
        if (retag != null) retag.run();
        return this;
    }
}
