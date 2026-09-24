package dev.guizhou.util.world;

/**
 * Crystal-PvP hole rules over an abstract block grid (no Minecraft types, unit-testable).
 * A hole is standing room (two passable cells) whose floor and every horizontal side are
 * blast resistant; bedrock holes are fully bedrock, double holes span two cells.
 */
public final class HoleClassifier {
    public enum Kind { PASSABLE, BEDROCK, RESISTANT, OTHER }

    public enum Type { BEDROCK, OBSIDIAN, DOUBLE }

    @FunctionalInterface
    public interface Grid {
        Kind at(int x, int y, int z);
    }

    /** {@code (x, y, z)} is the feet cell; for a double hole {@code (dx, dz)} points at the second cell. */
    public record Hole(int x, int y, int z, Type type, int dx, int dz) {
        public boolean isDouble() { return type == Type.DOUBLE; }
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private HoleClassifier() {}

    private static boolean safe(Kind kind) {
        return kind == Kind.BEDROCK || kind == Kind.RESISTANT;
    }

    private static boolean standingRoom(Grid grid, int x, int y, int z) {
        return grid.at(x, y, z) == Kind.PASSABLE && grid.at(x, y + 1, z) == Kind.PASSABLE;
    }

    /** Single 1x1 hole, or null. */
    public static Hole single(Grid grid, int x, int y, int z) {
        if (!standingRoom(grid, x, y, z)) return null;
        Kind floor = grid.at(x, y - 1, z);
        if (!safe(floor)) return null;
        boolean bedrock = floor == Kind.BEDROCK;
        for (int[] side : SIDES) {
            Kind wall = grid.at(x + side[0], y, z + side[1]);
            if (!safe(wall)) return null;
            bedrock &= wall == Kind.BEDROCK;
        }
        return new Hole(x, y, z, bedrock ? Type.BEDROCK : Type.OBSIDIAN, 0, 0);
    }

    /**
     * Double hole extending east or south from {@code (x, y, z)}; checking only these two
     * directions reports each double hole exactly once when every cell is scanned.
     */
    public static Hole doubleHole(Grid grid, int x, int y, int z) {
        for (int[] dir : new int[][]{{1, 0}, {0, 1}}) {
            int ox = x + dir[0], oz = z + dir[1];
            if (!standingRoom(grid, x, y, z) || !standingRoom(grid, ox, y, oz)) continue;
            if (!safe(grid.at(x, y - 1, z)) || !safe(grid.at(ox, y - 1, oz))) continue;
            if (enclosed(grid, x, y, z, ox, oz) && enclosed(grid, ox, y, oz, x, z)) {
                return new Hole(x, y, z, Type.DOUBLE, dir[0], dir[1]);
            }
        }
        return null;
    }

    /** Every side of one cell except the one facing its partner is blast resistant. */
    private static boolean enclosed(Grid grid, int x, int y, int z, int partnerX, int partnerZ) {
        for (int[] side : SIDES) {
            int sx = x + side[0], sz = z + side[1];
            if (sx == partnerX && sz == partnerZ) continue;
            if (!safe(grid.at(sx, y, sz))) return false;
        }
        return true;
    }
}
