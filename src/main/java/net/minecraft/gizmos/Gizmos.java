package net.minecraft.gizmos;

import dev.guizhou.util.render.GizmoQueue;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 1.21.1 port shim for the 1.21.6+ {@code net.minecraft.gizmos.Gizmos} entry points.
 * Call sites compiled against this package on 1.21.11 keep working; the primitives
 * are queued into {@link GizmoQueue} and flushed at the tail of the 3D render event.
 */
public final class Gizmos {

    private Gizmos() {}

    public static Gizmo cuboid(AABB box, GizmoStyle style) {
        if (style.stroke()) {
            GizmoQueue.stroke(box, style.colorARGB(), style.lineWidth(), false);
            return new Gizmo(GizmoQueue::retagLastStroke);
        }
        GizmoQueue.fill(box, style.colorARGB(), false);
        return new Gizmo(() -> GizmoQueue.retagLastFill());
    }

    public static Gizmo line(Vec3 from, Vec3 to, int colorARGB, float lineWidth) {
        GizmoQueue.line(from, to, colorARGB, lineWidth, false);
        return new Gizmo(() -> GizmoQueue.retagLastLine());
    }

    public static Gizmo rect(Vec3 a, Vec3 b, Vec3 c, Vec3 d, GizmoStyle style) {
        GizmoQueue.quad(a, b, c, d, style.colorARGB(), false);
        return new Gizmo(() -> GizmoQueue.retagLastQuad());
    }

    public static TextGizmo billboardText(String text, Vec3 at, TextGizmo.Style style) {
        GizmoQueue.text(text, at, style.colorARGB(), style.centered(), style.scale(), false);
        return new TextGizmo(() -> GizmoQueue.retagLastText());
    }
}
