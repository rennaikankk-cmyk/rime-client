package net.minecraft.gizmos;

/**
 * 1.21.1 port shim for the 1.21.6+ gizmo style API. Styles are plain data here;
 * rendering happens in {@code dev.guizhou.util.render.GizmoQueue} at the end of the
 * 3D pass instead of vanilla's automatic gizmo pass.
 */
public record GizmoStyle(int colorARGB, float lineWidth, boolean stroke) {

    public static GizmoStyle fill(int colorARGB) {
        return new GizmoStyle(colorARGB, 0.0f, false);
    }

    public static GizmoStyle stroke(int colorARGB, float lineWidth) {
        return new GizmoStyle(colorARGB, lineWidth, true);
    }
}
