package dev.guizhou.util.combat;

public final class ProjectileSimTest {
    private static int checks;

    /** Open sky with a floor plane at {@code y = floor}. */
    private static ProjectileSim.Collider floor(double floor) {
        return (x0, y0, z0, x1, y1, z1) -> {
            if (y1 > floor || y0 < floor) return -1;
            return y0 == y1 ? 0 : (y0 - floor) / (y0 - y1);
        };
    }

    public static void main(String[] args) {
        double[] level = ProjectileSim.launch(0f, 0f, 0f, 1.5);
        near(level[0], 0, "Yaw 0 faces +Z: no X");
        near(level[1], 0, "Level throw has no vertical speed");
        near(level[2], 1.5, "Level throw keeps the full speed on Z");
        double[] east = ProjectileSim.launch(-90f, 0f, 0f, 1.0);
        near(east[0], 1.0, "Yaw -90 faces +X");
        double[] up = ProjectileSim.launch(0f, -90f, 0f, 2.0);
        near(up[1], 2.0, "Pitch -90 throws straight up");
        double[] potion = ProjectileSim.launch(0f, 0f, -20f, 0.5);
        check(potion[1] > 0, "Potions and XP bottles lob upward by 20 degrees");
        near(Math.sqrt(potion[0] * potion[0] + potion[1] * potion[1] + potion[2] * potion[2]), 0.5, "Launch is normalized");

        // Throwables: gravity, then drag, then move (first tick included).
        ProjectileSim.Result pearl = ProjectileSim.simulate(0, 100, 0, 0, 0, 1.5, ProjectileSim.THROWABLE, 3, -64, floor(-1000));
        double vy = (0 - 0.03) * 0.99, vz = 1.5 * 0.99;
        double y = 100 + vy, z = vz;
        near(pearl.points().get(1)[1], y, "Pearl tick 1 height");
        near(pearl.points().get(1)[2], z, "Pearl tick 1 distance");
        vy = (vy - 0.03) * 0.99; vz *= 0.99; y += vy; z += vz;
        near(pearl.points().get(2)[1], y, "Pearl tick 2 height");
        near(pearl.points().get(2)[2], z, "Pearl tick 2 distance");

        // Arrows: move with the launch velocity first, then drag, then gravity.
        ProjectileSim.Result arrow = ProjectileSim.simulate(0, 100, 0, 0, 0, 3, ProjectileSim.ARROW, 2, -64, floor(-1000));
        near(arrow.points().get(1)[1], 100, "Arrow's first move is straight");
        near(arrow.points().get(1)[2], 3, "Arrow's first move uses full speed");
        near(arrow.points().get(2)[1], 100 - 0.05, "Arrow drops after its first tick");
        near(arrow.points().get(2)[2], 3 + 3 * 0.99, "Arrow drag applies from the second move");

        ProjectileSim.Result landed = ProjectileSim.simulate(0, 70, 0, 0, 0, 1.5, ProjectileSim.THROWABLE, 400, -64, floor(64));
        check(landed.landed(), "Pearl hits the floor");
        near(landed.end()[1], 64, "Landing point lies on the floor");
        check(landed.end()[2] > 5 && landed.end()[2] < 60, "Landing is ahead of the thrower: " + landed.end()[2]);
        check(landed.ticks() > 1 && landed.ticks() < 400, "Landing tick is reported");

        ProjectileSim.Result lost = ProjectileSim.simulate(0, 70, 0, 0, 0, 1.5, ProjectileSim.THROWABLE, 400, -64, floor(-10000));
        check(!lost.landed() && lost.end()[1] < -64, "Falling out of the world stops without a hit");

        ProjectileSim.Collider water = new ProjectileSim.Collider() {
            @Override public double hit(double a, double b, double c, double d, double e, double f) { return -1; }
            @Override public boolean inWater(double x, double y2, double z2) { return true; }
        };
        ProjectileSim.Result wet = ProjectileSim.simulate(0, 100, 0, 0, 0, 1.5, ProjectileSim.THROWABLE, 1, -64, water);
        near(wet.points().get(1)[2], 1.5 * 0.8, "Water drag replaces air drag");

        near(ProjectileSim.bowPower(0), 0, "Undrawn bow");
        near(ProjectileSim.bowPower(10), (0.25 + 1.0) / 3.0, "Half draw");
        near(ProjectileSim.bowPower(20), 1, "Full draw");
        near(ProjectileSim.bowPower(80), 1, "Power caps at 1");
        System.out.println("ProjectileSimTest passed: " + checks + " checks");
    }

    private static void near(double actual, double expected, String message) {
        check(Math.abs(actual - expected) < 1.0e-6, message + ": " + actual + " != " + expected);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
