package dev.guizhou.util.elytra;

public final class SpearRecoveryTest {
    private static int checks;
    public static void main(String[] args) {
        check(!resume(5, 4, 2, 8, 4.5, 2, 0, 0, 3, 1), "Stationary target retains retreat distance");
        check(resume(9, 8, 2, 8, 4.5, 2, 0, 0, 3, 1), "Charged pass reopens after spacing");
        check(resume(6, 5, 2, 8, 4.5, 2, 3, 6, 3, 1), "Faster escaping target supplies charge room");
        check(!resume(6, 5, 2, 8, 4.5, 4, 1, 6, 3, 1), "Do not arrive before charge completes");
        check(resume(12, 11, 2, 16, 4.5, 2, 1, 6, 3, 1), "Charge completes during interception");
        check(!resume(6, 5, 2, 8, 4.5, 2, 3, 0, 3, .3), "Unstable samples cannot shorten recovery");
        check(!resume(3, 1, 2, 8, 4.5, 2, 3, 0, 3, 1), "Inner dead zone still escapes");
        check(!resume(6, 5, 2, 8, 4.5, 2, 3, 0, 1, 1), "At least two retreat ticks before re-entry");
        check(!resume(6, 5, 2, 8, 4.5, 2, -3, 0, 3, 1), "Incoming opponent does not count as escaping");
        check(!resume(6, 5, 2, 8, 4.5, 2, Double.NaN, 0, 3, 1), "Invalid speed cannot steer");
        check(!resume(6, 5, 2, 8, 4.5, 2, 30, 0, 3, 1), "Teleport sample rejected");
        check(resume(6, 5, 2, 8, 4.5, 0, 0, 8, 21, 0), "Recovery always has a deadline");
        check(SpearCombat.remainingCharge(true, 7, 8, 40) == 1, "One tick of charge remains");
        check(SpearCombat.remainingCharge(true, 48, 8, 40) == 0, "Last damage tick is usable");
        check(SpearCombat.remainingCharge(true, 49, 8, 40) == 9, "Expired use needs release and full recharge");
        check(SpearCombat.remainingCharge(false, 0, 12, 40) == 13, "Weapon-specific delay plus start tick");
        check(SpearCombat.remainingCharge(true, 9, 12, 40) == 3, "Delay is not fixed to eight");
        check(SpearCombat.remainingCharge(false, 0, Integer.MAX_VALUE, 1) == Integer.MAX_VALUE,
                "Untrusted component durations cannot overflow into ready");
        System.out.println("SpearRecoveryTest passed: " + checks + " checks");
    }
    private static boolean resume(double d, double box, double min, double desired, double reach,
                                  double self, double target, int charge, int ticks, double confidence) {
        return SpearRecovery.resume(new SpearRecovery.Input(d, box, min, desired, reach, self, target,
                charge, ticks, 20, confidence));
    }
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
