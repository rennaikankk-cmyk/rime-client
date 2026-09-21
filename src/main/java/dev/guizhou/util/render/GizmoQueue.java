package dev.guizhou.util.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * 1.21.1 backing store for the {@code net.minecraft.gizmos.Gizmos} shim. The 1.21.6+
 * gizmo API renders queued primitives automatically at the end of the frame; here the
 * queue is flushed explicitly at the tail of the 3D render event (MixinWorldRenderer).
 */
public final class GizmoQueue {

    private record FillEntry(AABB box, int colorARGB, boolean[] onTop) {}
    private record QuadEntry(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int colorARGB, boolean[] onTop) {}
    private record StrokeEntry(AABB box, int colorARGB, float width, boolean[] onTop) {}
    private record LineEntry(Vec3 from, Vec3 to, int colorARGB, float width, boolean[] onTop) {}
    private record TextEntry(String text, Vec3 pos, int colorARGB, boolean centered, float scale, boolean[] onTop) {}

    private static final List<FillEntry> FILLS = new ArrayList<>();
    private static final List<QuadEntry> QUADS = new ArrayList<>();
    private static final List<StrokeEntry> STROKES = new ArrayList<>();
    private static final List<LineEntry> LINES = new ArrayList<>();
    private static final List<TextEntry> TEXTS = new ArrayList<>();

    private GizmoQueue() {}

    public static void fill(AABB box, int colorARGB, boolean onTop) {
        synchronized (FILLS) { FILLS.add(new FillEntry(box, colorARGB, new boolean[]{onTop})); }
    }

    public static void retagLastFill() { retagLast(FILLS); }

    public static void quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int colorARGB, boolean onTop) {
        synchronized (QUADS) { QUADS.add(new QuadEntry(a, b, c, d, colorARGB, new boolean[]{onTop})); }
    }

    public static void retagLastQuad() { retagLast(QUADS); }

    public static void stroke(AABB box, int colorARGB, float width, boolean onTop) {
        synchronized (STROKES) { STROKES.add(new StrokeEntry(box, colorARGB, width, new boolean[]{onTop})); }
    }

    public static void retagLastStroke() { retagLast(STROKES); }

    public static void line(Vec3 from, Vec3 to, int colorARGB, float width, boolean onTop) {
        synchronized (LINES) { LINES.add(new LineEntry(from, to, colorARGB, width, new boolean[]{onTop})); }
    }

    public static void retagLastLine() { retagLast(LINES); }

    public static void text(String text, Vec3 pos, int colorARGB, boolean centered, float scale, boolean onTop) {
        synchronized (TEXTS) { TEXTS.add(new TextEntry(text, pos, colorARGB, centered, scale, new boolean[]{onTop})); }
    }

    public static void retagLastText() { retagLast(TEXTS); }

    private static <T> void retagLast(List<T> list) {
        synchronized (list) {
            if (list.isEmpty()) return;
            T last = list.get(list.size() - 1);
            if (last instanceof FillEntry e) e.onTop()[0] = true;
            else if (last instanceof QuadEntry e) e.onTop()[0] = true;
            else if (last instanceof StrokeEntry e) e.onTop()[0] = true;
            else if (last instanceof LineEntry e) e.onTop()[0] = true;
            else if (last instanceof TextEntry e) e.onTop()[0] = true;
        }
    }

    /** Called on the render thread at the end of the 3D pass; consumes the queue. */
    public static void flush(Vec3 cameraPos) {
        List<FillEntry> fills;
        List<QuadEntry> quads;
        List<StrokeEntry> strokes;
        List<LineEntry> lines;
        List<TextEntry> texts;
        synchronized (FILLS) { fills = new ArrayList<>(FILLS); FILLS.clear(); }
        synchronized (QUADS) { quads = new ArrayList<>(QUADS); QUADS.clear(); }
        synchronized (STROKES) { strokes = new ArrayList<>(STROKES); STROKES.clear(); }
        synchronized (LINES) { lines = new ArrayList<>(LINES); LINES.clear(); }
        synchronized (TEXTS) { texts = new ArrayList<>(TEXTS); TEXTS.clear(); }
        if (fills.isEmpty() && quads.isEmpty() && strokes.isEmpty() && lines.isEmpty() && texts.isEmpty()) return;

        // Review item 8: by renderLevel's RETURN the global model-view is already
        // popped, so camera-relative vertices have no view transform. Apply the camera
        // rotation around the whole flush (entries carry raw camera-relative
        // positions, including the billboard texts whose matrix excludes rotation).
        // Review clarification: camera.rotation() is the camera orientation, not the
        // view transform; use the renderLevel view matrix captured in MatrixCapture.
        org.joml.Matrix4fStack viewStack = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
        viewStack.pushMatrix();
        viewStack.mul(new org.joml.Matrix4f(MatrixCapture.view));
        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
        try {
            for (boolean through : new boolean[]{false, true}) {
                drawFills(fills, cameraPos, through);
                drawQuads(quads, cameraPos, through);
                drawStrokes(strokes, cameraPos, through);
                drawLines(lines, cameraPos, through);
            }
            drawTexts(texts, cameraPos);
        } finally {
            viewStack.popMatrix();
            com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
        }
    }

    private static void drawFills(List<FillEntry> fills, Vec3 cam, boolean through) {
        BufferBuilder bb = null;
        for (FillEntry f : fills) {
            if (f.onTop()[0] != through) continue;
            if (bb == null) bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            emitBoxQuads(bb, f.box(), cam, f.colorARGB());
        }
        if (bb != null) Layers.placementQuads(through).draw(bb.buildOrThrow());
    }

    private static void drawQuads(List<QuadEntry> quads, Vec3 cam, boolean through) {
        BufferBuilder bb = null;
        for (QuadEntry q : quads) {
            if (q.onTop()[0] != through) continue;
            if (bb == null) bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            float r = ((q.colorARGB() >> 16) & 0xFF) / 255.0f;
            float g = ((q.colorARGB() >> 8) & 0xFF) / 255.0f;
            float b = (q.colorARGB() & 0xFF) / 255.0f;
            float a = ((q.colorARGB() >>> 24) & 0xFF) / 255.0f;
            bb.addVertex((float) (q.a().x - cam.x), (float) (q.a().y - cam.y), (float) (q.a().z - cam.z)).setColor(r, g, b, a);
            bb.addVertex((float) (q.b().x - cam.x), (float) (q.b().y - cam.y), (float) (q.b().z - cam.z)).setColor(r, g, b, a);
            bb.addVertex((float) (q.c().x - cam.x), (float) (q.c().y - cam.y), (float) (q.c().z - cam.z)).setColor(r, g, b, a);
            bb.addVertex((float) (q.d().x - cam.x), (float) (q.d().y - cam.y), (float) (q.d().z - cam.z)).setColor(r, g, b, a);
        }
        if (bb != null) Layers.placementQuads(through).draw(bb.buildOrThrow());
    }

    private static void drawStrokes(List<StrokeEntry> strokes, Vec3 cam, boolean through) {
        for (StrokeEntry s : strokes) {
            if (s.onTop()[0] != through) continue;
            BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
            emitBoxEdges(bb, s.box(), cam, s.colorARGB());
            Layers.lines(s.width(), through).draw(bb.buildOrThrow());
        }
    }

    private static void drawLines(List<LineEntry> lines, Vec3 cam, boolean through) {
        for (LineEntry l : lines) {
            if (l.onTop()[0] != through) continue;
            BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
            emitEdge(bb,
                    (float) (l.from().x - cam.x), (float) (l.from().y - cam.y), (float) (l.from().z - cam.z),
                    (float) (l.to().x - cam.x), (float) (l.to().y - cam.y), (float) (l.to().z - cam.z),
                    l.colorARGB());
            Layers.lines(l.width(), through).draw(bb.buildOrThrow());
        }
    }

    private static void drawTexts(List<TextEntry> texts, Vec3 cam) {
        if (texts.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        for (TextEntry t : texts) {
            // Review8/clarification order: translate to camera-relative billboard
            // anchor first, then rotate to face the camera, then scale with flipped Y
            // so text reads upright in the world (screen-space Y grows downward).
            PoseStack stack = new PoseStack();
            stack.translate(t.pos().x - cam.x, t.pos().y - cam.y, t.pos().z - cam.z);
            stack.mulPose(mc.gameRenderer.getMainCamera().rotation());
            float s = t.scale() / 16.0f;
            stack.scale(s, -s, s);
            Matrix4f matrix = stack.last().pose();
            float x = t.centered() ? -font.width(t.text()) / 2.0f : 0.0f;
            font.drawInBatch(t.text(), x, 0.0f, t.colorARGB(), false, matrix, buffers,
                    t.onTop()[0] ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL,
                    0, 0xF000F0);
        }
        buffers.endBatch();
    }

    private static void emitBoxQuads(BufferBuilder bb, AABB box, Vec3 cam, int colorARGB) {
        float r = ((colorARGB >> 16) & 0xFF) / 255.0f;
        float g = ((colorARGB >> 8) & 0xFF) / 255.0f;
        float b = (colorARGB & 0xFF) / 255.0f;
        float a = ((colorARGB >>> 24) & 0xFF) / 255.0f;
        float x0 = (float) (box.minX - cam.x), y0 = (float) (box.minY - cam.y), z0 = (float) (box.minZ - cam.z);
        float x1 = (float) (box.maxX - cam.x), y1 = (float) (box.maxY - cam.y), z1 = (float) (box.maxZ - cam.z);
        quad(bb, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, a);
        quad(bb, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g, b, a);
        quad(bb, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, b, a);
        quad(bb, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, a);
        quad(bb, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, b, a);
        quad(bb, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, b, a);
    }

    private static void quad(BufferBuilder bb, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float r, float g, float b, float a) {
        bb.addVertex(ax, ay, az).setColor(r, g, b, a);
        bb.addVertex(bx, by, bz).setColor(r, g, b, a);
        bb.addVertex(cx, cy, cz).setColor(r, g, b, a);
        bb.addVertex(dx, dy, dz).setColor(r, g, b, a);
    }

    private static void emitBoxEdges(BufferBuilder bb, AABB box, Vec3 cam, int colorARGB) {
        float r = ((colorARGB >> 16) & 0xFF) / 255.0f;
        float g = ((colorARGB >> 8) & 0xFF) / 255.0f;
        float b = (colorARGB & 0xFF) / 255.0f;
        float a = ((colorARGB >>> 24) & 0xFF) / 255.0f;
        float x0 = (float) (box.minX - cam.x), y0 = (float) (box.minY - cam.y), z0 = (float) (box.minZ - cam.z);
        float x1 = (float) (box.maxX - cam.x), y1 = (float) (box.maxY - cam.y), z1 = (float) (box.maxZ - cam.z);
        emitEdge(bb, x0, y0, z0, x1, y0, z0, r, g, b, a);
        emitEdge(bb, x1, y0, z0, x1, y0, z1, r, g, b, a);
        emitEdge(bb, x1, y0, z1, x0, y0, z1, r, g, b, a);
        emitEdge(bb, x0, y0, z1, x0, y0, z0, r, g, b, a);
        emitEdge(bb, x0, y1, z0, x1, y1, z0, r, g, b, a);
        emitEdge(bb, x1, y1, z0, x1, y1, z1, r, g, b, a);
        emitEdge(bb, x1, y1, z1, x0, y1, z1, r, g, b, a);
        emitEdge(bb, x0, y1, z1, x0, y1, z0, r, g, b, a);
        emitEdge(bb, x0, y0, z0, x0, y1, z0, r, g, b, a);
        emitEdge(bb, x1, y0, z0, x1, y1, z0, r, g, b, a);
        emitEdge(bb, x1, y0, z1, x1, y1, z1, r, g, b, a);
        emitEdge(bb, x0, y0, z1, x0, y1, z1, r, g, b, a);
    }

    private static void emitEdge(BufferBuilder vc, float ax, float ay, float az,
                                 float bx, float by, float bz,
                                 int colorARGB) {
        float r = ((colorARGB >> 16) & 0xFF) / 255.0f;
        float g = ((colorARGB >> 8) & 0xFF) / 255.0f;
        float b = (colorARGB & 0xFF) / 255.0f;
        float a = ((colorARGB >>> 24) & 0xFF) / 255.0f;
        emitEdge(vc, ax, ay, az, bx, by, bz, r, g, b, a);
    }

    private static void emitEdge(BufferBuilder vc, float ax, float ay, float az,
                                 float bx, float by, float bz,
                                 float r, float g, float b, float a) {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float nx = 0f, ny = 1f, nz = 0f;
        if (len > 1.0E-4f) { nx = dx / len; ny = dy / len; nz = dz / len; }
        vc.addVertex(ax, ay, az).setColor(r, g, b, a).setNormal(nx, ny, nz);
        vc.addVertex(bx, by, bz).setColor(r, g, b, a).setNormal(nx, ny, nz);
    }
}
