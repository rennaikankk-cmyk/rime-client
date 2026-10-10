package dev.guizhou.util.combat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable bounded collision geometry. This class has no Minecraft dependency. */
public final class CombatCollisionSnapshot {
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        private boolean intersects(double x, double y, double z, double dx, double dy, double dz) {
            double low = 0.0, high = 1.0;
            if (Math.abs(dx) < 1.0e-12) { if (x < minX || x > maxX) return false; }
            else {
                double a = (minX - x) / dx, b = (maxX - x) / dx;
                low = Math.max(low, Math.min(a, b)); high = Math.min(high, Math.max(a, b));
                if (high < low) return false;
            }
            if (Math.abs(dy) < 1.0e-12) { if (y < minY || y > maxY) return false; }
            else {
                double a = (minY - y) / dy, b = (maxY - y) / dy;
                low = Math.max(low, Math.min(a, b)); high = Math.min(high, Math.max(a, b));
                if (high < low) return false;
            }
            if (Math.abs(dz) < 1.0e-12) { if (z < minZ || z > maxZ) return false; }
            else {
                double a = (minZ - z) / dz, b = (maxZ - z) / dz;
                low = Math.max(low, Math.min(a, b)); high = Math.min(high, Math.max(a, b));
                if (high < low) return false;
            }
            return high >= 0 && low <= 1;
        }
    }

    private final int minX, minY, minZ, sizeX, sizeY, sizeZ;
    private final Map<Integer, List<Box>> geometry;

    public CombatCollisionSnapshot(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ,
                                   Map<Integer, List<Box>> geometry) {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || (long) sizeX * sizeY * sizeZ > 65536)
            throw new IllegalArgumentException("Unbounded combat snapshot");
        this.minX = minX; this.minY = minY; this.minZ = minZ;
        this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
        Map<Integer, List<Box>> copy = new HashMap<>();
        geometry.forEach((key, boxes) -> copy.put(key, List.copyOf(boxes)));
        this.geometry = Map.copyOf(copy);
    }

    public int index(int x, int y, int z) {
        return ((x - minX) * sizeY + y - minY) * sizeZ + z - minZ;
    }

    /** Out-of-snapshot rays are blocked rather than silently assuming missing terrain is air. */
    public boolean clear(double fromX, double fromY, double fromZ, double toX, double toY, double toZ,
                         int ignoredX, int ignoredY, int ignoredZ) {
        int x = floor(fromX), y = floor(fromY), z = floor(fromZ);
        int endX = floor(toX), endY = floor(toY), endZ = floor(toZ);
        double dx = toX - fromX, dy = toY - fromY, dz = toZ - fromZ;
        int sx = Double.compare(dx, 0), sy = Double.compare(dy, 0), sz = Double.compare(dz, 0);
        double deltaX = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double deltaY = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double deltaZ = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        double tx = initial(fromX, x, dx, sx), ty = initial(fromY, y, dy, sy), tz = initial(fromZ, z, dz, sz);
        int steps = Math.abs(endX - x) + Math.abs(endY - y) + Math.abs(endZ - z) + 4;
        for (int step = 0; step < steps; step++) {
            if (x < minX || y < minY || z < minZ || x >= minX + sizeX || y >= minY + sizeY || z >= minZ + sizeZ) return false;
            if (x != ignoredX || y != ignoredY || z != ignoredZ) {
                List<Box> boxes = geometry.get(index(x, y, z));
                if (boxes != null) for (Box box : boxes) if (box.intersects(fromX, fromY, fromZ, dx, dy, dz)) return false;
            }
            if (x == endX && y == endY && z == endZ) return true;
            if (tx <= ty && tx <= tz) { x += sx; tx += deltaX; }
            else if (ty <= tz) { y += sy; ty += deltaY; }
            else { z += sz; tz += deltaZ; }
        }
        return false;
    }

    private static double initial(double value, int cell, double delta, int step) {
        return step == 0 ? Double.POSITIVE_INFINITY : ((step > 0 ? cell + 1.0 : cell) - value) / delta;
    }
    private static int floor(double value) { return (int)Math.floor(value); }
}
