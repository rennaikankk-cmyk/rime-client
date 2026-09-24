package dev.guizhou.util.world;

import java.util.HashMap;
import java.util.Map;

/** Waypoint coordinate conversion/file keys and hole classification. */
public final class WorldUtilityTest {
    private static int checks;

    public static void main(String[] args) {
        waypoints();
        holes();
        System.out.println("WorldUtilityTest passed: " + checks + " checks");
    }

    private static void waypoints() {
        double[] nether = WaypointMath.convert(800, -1600, WaypointMath.OVERWORLD, WaypointMath.NETHER);
        check(nether[0] == 100 && nether[1] == -200, "Overworld -> nether divides by 8");
        double[] back = WaypointMath.convert(100.5, -200, WaypointMath.NETHER, WaypointMath.OVERWORLD);
        check(back[0] == 804 && back[1] == -1600, "Nether -> overworld multiplies by 8");
        double[] same = WaypointMath.convert(7, 9, WaypointMath.END, WaypointMath.END);
        check(same[0] == 7 && same[1] == 9, "Same dimension is unchanged");
        check(WaypointMath.convert(1, 1, WaypointMath.END, WaypointMath.OVERWORLD) == null, "The End has no mapping");
        check(WaypointMath.convert(1, 1, null, WaypointMath.OVERWORLD) == null, "Unknown dimension has no mapping");
        check(WaypointMath.serverKey("Play.Example.com:25565").equals("play.example.com"), "Default port and case fold");
        check(WaypointMath.serverKey("play.example.com").equals("play.example.com"), "Same server, same file");
        check(WaypointMath.serverKey("1.2.3.4:25570").equals("1.2.3.4_25570"), "Custom port stays distinct");
        check(WaypointMath.serverKey("../../etc/passwd").equals("_.._etc_passwd"), "Path separators cannot escape the folder");
        check(WaypointMath.serverKey("  ").equals("unknown") && WaypointMath.serverKey(null).equals("unknown"), "Blank keys");
        check(WaypointMath.serverKey("a".repeat(200)).length() == 64, "Keys are length-limited");
        check(WaypointMath.shortDimension(WaypointMath.NETHER).equals("下界"), "Dimension label");
    }

    private static void holes() {
        // Bedrock single hole at (0, 64, 0).
        Grid grid = new Grid();
        grid.set(0, 63, 0, HoleClassifier.Kind.BEDROCK);
        for (int[] side : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) grid.set(side[0], 64, side[1], HoleClassifier.Kind.BEDROCK);
        HoleClassifier.Hole bedrock = HoleClassifier.single(grid, 0, 64, 0);
        check(bedrock != null && bedrock.type() == HoleClassifier.Type.BEDROCK, "All-bedrock hole");

        grid.set(1, 64, 0, HoleClassifier.Kind.RESISTANT);
        check(HoleClassifier.single(grid, 0, 64, 0).type() == HoleClassifier.Type.OBSIDIAN, "One obsidian wall makes it an obsidian hole");

        grid.set(0, 65, 0, HoleClassifier.Kind.OTHER);
        check(HoleClassifier.single(grid, 0, 64, 0) == null, "No head room, no hole");
        grid.set(0, 65, 0, HoleClassifier.Kind.PASSABLE);

        grid.set(0, 64, 1, HoleClassifier.Kind.OTHER);
        check(HoleClassifier.single(grid, 0, 64, 0) == null, "A dirt wall is not blast safe");
        grid.set(0, 64, 1, HoleClassifier.Kind.BEDROCK);

        grid.set(0, 63, 0, HoleClassifier.Kind.PASSABLE);
        check(HoleClassifier.single(grid, 0, 64, 0) == null, "A hole needs a floor");
        grid.set(0, 63, 0, HoleClassifier.Kind.BEDROCK);

        // Double hole: (10,64,0) and (11,64,0).
        Grid pair = new Grid();
        for (int x = 10; x <= 11; x++) {
            pair.set(x, 63, 0, HoleClassifier.Kind.RESISTANT);
            pair.set(x, 64, 1, HoleClassifier.Kind.RESISTANT);
            pair.set(x, 64, -1, HoleClassifier.Kind.RESISTANT);
        }
        pair.set(9, 64, 0, HoleClassifier.Kind.RESISTANT);
        pair.set(12, 64, 0, HoleClassifier.Kind.RESISTANT);
        check(HoleClassifier.single(pair, 10, 64, 0) == null, "Open side: not a single hole");
        HoleClassifier.Hole twin = HoleClassifier.doubleHole(pair, 10, 64, 0);
        check(twin != null && twin.isDouble() && twin.dx() == 1 && twin.dz() == 0, "East double hole found");
        check(HoleClassifier.doubleHole(pair, 11, 64, 0) == null, "Each double hole is reported once (from its west cell)");
        pair.set(12, 64, 0, HoleClassifier.Kind.PASSABLE);
        check(HoleClassifier.doubleHole(pair, 10, 64, 0) == null, "An open end breaks the double hole");
    }

    private static final class Grid implements HoleClassifier.Grid {
        private final Map<String, HoleClassifier.Kind> cells = new HashMap<>();
        void set(int x, int y, int z, HoleClassifier.Kind kind) { cells.put(x + "," + y + "," + z, kind); }
        @Override public HoleClassifier.Kind at(int x, int y, int z) {
            return cells.getOrDefault(x + "," + y + "," + z, HoleClassifier.Kind.PASSABLE);
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
