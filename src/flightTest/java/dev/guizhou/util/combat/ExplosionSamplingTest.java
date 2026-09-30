package dev.guizhou.util.combat;

/** Geometric invariants of standing/crouching/swimming blast samples; no game bootstrap. */
public final class ExplosionSamplingTest {
    private static int checks;
    public static void main(String[] args) {
        double[] standing = ExplosionSampling.points(-.3, 0, -.3, .3, 1.8, .3);
        check(standing.length == 45 * 3, "standing player uses 3 x 5 x 3 samples");
        double[] crouching = ExplosionSampling.points(-.3, 0, -.3, .3, 1.5, .3);
        check(crouching.length == 45 * 3, "crouching uses the bounding-box-dependent vertical grid");
        double[] swimming = ExplosionSampling.points(-.3, 0, -.3, .3, .6, .3);
        check(swimming.length == 27 * 3, "swimming uses 3 x 3 x 3 samples");
        for (int i = 0; i < standing.length; i += 3) {
            check(standing[i] >= -.3 && standing[i] <= .3, "sample x inside player box");
            check(standing[i + 1] >= 0 && standing[i + 1] <= 1.8, "sample y inside player box");
            check(standing[i + 2] >= -.3 && standing[i + 2] <= .3, "sample z inside player box");
        }
        double[] translated = ExplosionSampling.points(9.7, 64, -20.3, 10.3, 65.8, -19.7);
        check(translated.length == standing.length, "translation preserves sample count");
        for (int i = 0; i < standing.length; i += 3) {
            near(translated[i] - 10, standing[i]);
            near(translated[i + 1] - 64, standing[i + 1]);
            near(translated[i + 2] + 20, standing[i + 2]);
        }
        double[] point = ExplosionSampling.points(1, 2, 3, 1, 2, 3);
        check(point.length == 24, "zero-size vanilla grid retains endpoint duplicates");
        for (int i = 0; i < point.length; i += 3) { near(point[i], 1); near(point[i + 1], 2); near(point[i + 2], 3); }
        check(ExplosionSampling.points(0, 0, 0, -1, 1, 1).length == 0, "negative dimensions rejected");
        check(ExplosionSampling.points(0, 0, 0, Double.NaN, 1, 1).length == 0, "invalid geometry rejected");
        System.out.println("ExplosionSamplingTest passed: " + checks + " geometry checks");
    }
    private static void near(double a, double b) { check(Math.abs(a - b) < 1e-12, "sample translation and endpoints"); }
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
}
