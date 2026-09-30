package dev.guizhou.util.combat;

import java.util.Arrays;

/** Vanilla explosion sampling order and offsets, without loading Minecraft classes. */
public final class ExplosionSampling {
    private ExplosionSampling() {}

    public static double[] points(double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ) {
        double dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
        if (!Double.isFinite(dx + dy + dz) || dx < 0 || dy < 0 || dz < 0) return new double[0];
        double sx = 1 / (dx * 2 + 1), sy = 1 / (dy * 2 + 1), sz = 1 / (dz * 2 + 1);
        double ox = (1 - Math.floor(1 / sx) * sx) / 2;
        double oz = (1 - Math.floor(1 / sz) * sz) / 2;
        double[] out = new double[192];
        int size = 0;
        for (double x = 0; x <= 1; x += sx) {
            for (double y = 0; y <= 1; y += sy) {
                for (double z = 0; z <= 1; z += sz) {
                    if (size + 3 > out.length) out = Arrays.copyOf(out, out.length * 2);
                    out[size++] = minX + dx * x + ox;
                    out[size++] = minY + dy * y;
                    out[size++] = minZ + dz * z + oz;
                }
            }
        }
        return Arrays.copyOf(out, size);
    }
}
