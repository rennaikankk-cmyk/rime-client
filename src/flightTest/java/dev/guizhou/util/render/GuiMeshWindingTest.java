package dev.guizhou.util.render;

import java.lang.reflect.Method;

/** Regression for back-face culled border edges and rounded fill corners in the GUI pipeline. */
public final class GuiMeshWindingTest {
    public static void main(String[] args) throws Exception {
        Class<?> mesh = Class.forName("dev.guizhou.util.render.ShadcnDraw$Mesh");
        var constructor = mesh.getDeclaredConstructor(); constructor.setAccessible(true);
        var coordinates = mesh.getDeclaredField("xy"); coordinates.setAccessible(true);
        var count = mesh.getDeclaredField("n"); count.setAccessible(true);
        Method fill = ShadcnDraw.class.getDeclaredMethod("fill", mesh, float.class, float.class, float.class,
                float.class, float.class, int.class, int.class);
        Method ring = ShadcnDraw.class.getDeclaredMethod("ring", mesh, float.class, float.class, float.class,
                float.class, float.class, float.class, float.class, float.class, float.class, float.class, int.class, int.class);
        fill.setAccessible(true); ring.setAccessible(true);
        int checks = 0;
        for (float radius : new float[]{0, 1.5f, 3, 8}) {
            for (boolean outline : new boolean[]{false, true}) {
                Object output = constructor.newInstance();
                if (outline) ring.invoke(null, output, 10f, 20f, 100f, 40f, radius,
                        11f, 21f, 98f, 38f, Math.max(0, radius - 1), 0xFF45443F, 0xFF45443F);
                else fill.invoke(null, output, 10f, 20f, 100f, 40f, radius, 0xFF242320, 0xFF242320);
                float[] xy = (float[]) coordinates.get(output);
                int n = count.getInt(output);
                if (n == 0 || n % 4 != 0) throw new AssertionError("Missing GUI quads");
                for (int i = 0; i < n; i += 4) {
                    float a = triangle(xy, i, i + 1, i + 2), b = triangle(xy, i + 2, i + 3, i);
                    if (a > .001f || b > .001f) throw new AssertionError("Culled GUI face: radius=" + radius + ", outline=" + outline);
                    checks += 2;
                }
            }
        }
        System.out.println("GuiMeshWindingTest passed: " + checks + " checks");
    }
    private static float triangle(float[] xy, int a, int b, int c) {
        return (xy[b * 2] - xy[a * 2]) * (xy[c * 2 + 1] - xy[a * 2 + 1])
                - (xy[b * 2 + 1] - xy[a * 2 + 1]) * (xy[c * 2] - xy[a * 2]);
    }
}
