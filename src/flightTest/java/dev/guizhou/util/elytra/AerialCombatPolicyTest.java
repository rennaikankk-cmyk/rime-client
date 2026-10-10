package dev.guizhou.util.elytra;

import dev.guizhou.util.elytra.AerialCombatPolicy.Action;
import dev.guizhou.util.elytra.AerialCombatPolicy.ThreatInput;
import dev.guizhou.util.elytra.AerialCombatPolicy.Vector;

public final class AerialCombatPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        weaponAwareApproach();
        relativeEncounters();
        highThreatEncounters();
        pursuitIsNotIncomingThreat();
        weaponThreatsAndClearDelay();
        asynchronousThreatAndStrikePriority();
        healthRecoveryAndAbsorption();
        lifecycleAndInvalidInputs();
        targetHysteresis();
        boundedPrediction();
        System.out.println("AerialCombatPolicyTest passed: " + checks + " checks");
    }

    private static void weaponAwareApproach() {
        ThreatInput rising = input(0, v(0,-8,0), v(0,-1.7,0), v(0,2,0));
        expect(Action.EVADE, new AerialCombatPolicy().evaluate(rising), "Geometry alone sees the incoming pass");
        expect(Action.CHASE, new AerialCombatPolicy().evaluate(rising, false, false, false),
                "Known unavailable smash does not abort our descending attack");
        expect(Action.EVADE, new AerialCombatPolicy().evaluate(health(0,20,0,true,false,false), false,false,false),
                "Disabling geometric approach does not hide a ready spear");
        expect(Action.EVADE, new AerialCombatPolicy().evaluate(health(0,20,0,false,true,false), false,false,false),
                "A falling mace remains actionable");
        expect(Action.EVADE, new AerialCombatPolicy().evaluate(rising, true,false,false),
                "Predicted projectiles remain actionable");
    }

    private static void relativeEncounters() {
        expect(Action.EVADE, fresh(input(0, v(12, 0, 0), Vector.ZERO, v(-3, 0, 0))),
                "A fast head-on encounter requires evasion");
        expect(Action.CHASE, fresh(input(0, v(12, 0, 0), v(3, 0, 0), v(3, 0, 0))),
                "Equal-speed parallel motion is not an incoming threat");
        expect(Action.CHASE, fresh(input(0, v(12, 0, 0), v(3, 0, 0), Vector.ZERO)),
                "Pursuing a stationary target is not enemy rushing");
        expect(Action.CHASE, fresh(input(0, v(12, 0, 8), Vector.ZERO, v(-3, 0, 0))),
                "A distant flyby misses despite positive closing speed");
        expect(Action.CHASE, fresh(input(0, v(4, 0, 0), Vector.ZERO, v(3, 0, 0))),
                "A fast receding target is safe to pursue");
        expect(Action.CHASE, fresh(input(0, v(2, 0, 0), Vector.ZERO, v(-0.3, 0, 0))),
                "Ordinary slow movement keeps simple pursuit");
        expect(Action.CHASE, fresh(input(0, v(100, 0, 0), Vector.ZERO, v(-3, 0, 0))),
                "Distant future encounters do not trigger evasion");
        expect(Action.EVADE, fresh(input(0, v(6, 0, 0), v(-1, 0, 0), v(-3, 0, 0))),
                "Faster pursuit from the target remains a relative threat");
        expect(Action.CHASE, fresh(input(0, v(6, 0, 0), v(-4, 0, 0), v(-3, 0, 0))),
                "Moving away faster than the target removes closing threat");
        expect(Action.CHASE, fresh(input(0, Vector.ZERO, Vector.ZERO, v(-3, 0, 0))),
                "Coincident positions do not invent an undefined incoming direction");
    }

    private static void weaponThreatsAndClearDelay() {
        AerialCombatPolicy policy = new AerialCombatPolicy();
        expect(Action.EVADE, policy.evaluate(health(10, 20, 0, true, false, false)),
                "An actual spear threat overrides ordinary pursuit");
        expect(Action.EVADE, policy.evaluate(health(13, 20, 0, false, false, false)),
                "Brief loss of weapon threat does not oscillate the action");
        expect(Action.CHASE, policy.evaluate(health(14, 20, 0, false, false, false)),
                "Four clear ticks return to pursuit");
        expect(Action.EVADE, policy.evaluate(health(15, 20, 0, false, true, false)),
                "Observed mace descent is independently actionable");
        expect(Action.EVADE, policy.evaluate(health(18, 20, 0, false, true, false)),
                "Continuing danger refreshes the clear interval");
        expect(Action.EVADE, policy.evaluate(health(21, 20, 0, false, false, false)),
                "Clear interval begins at the final threat observation");
        expect(Action.CHASE, policy.evaluate(health(22, 20, 0, false, false, false)),
                "Evasion eventually ends after danger clears");
    }

    private static void highThreatEncounters() {
        ThreatInput slowApproach = input(0, v(5, 0, 0), Vector.ZERO, v(-0.4, 0, 0));
        expect(Action.CHASE, fresh(slowApproach), "Adaptive mode leaves ordinary slow approach alone");
        expect(Action.EVADE, fresh(highThreat(slowApproach)), "High-threat mode reacts earlier to a closing target");
        expect(Action.CHASE, fresh(highThreat(input(0, v(5, 0, 0), v(3, 0, 0), Vector.ZERO))),
                "High-threat mode still pursues a stationary target");
        expect(Action.CHASE, fresh(highThreat(input(0, v(5, 0, 0), v(3, 0, 0), v(3, 0, 0)))),
                "High-threat mode does not evade indefinitely during parallel flight");
        expect(Action.CHASE, fresh(highThreat(input(0, v(5, 0, 0), Vector.ZERO, v(0.4, 0, 0)))),
                "High-threat mode can pursue a departing target");
        expect(Action.CHASE, fresh(highThreat(input(0, v(5, 0, 5), Vector.ZERO, v(-0.4, 0, 0)))),
                "High-threat mode still requires an actual close pass");
        expect(Action.CHASE, fresh(highThreat(input(0, v(100, 0, 0), Vector.ZERO, v(-0.4, 0, 0)))),
                "High-threat mode has a finite warning horizon");
        ThreatInput marginalPass = input(0, v(8, 0, 2.5), Vector.ZERO, v(-2, 0, 0));
        expect(Action.CHASE, fresh(marginalPass), "Adaptive mode ignores a pass outside its safety margin");
        expect(Action.EVADE, fresh(highThreat(marginalPass)), "High-threat mode uses its wider encounter margin");
        AerialCombatPolicy policy = new AerialCombatPolicy();
        policy.evaluate(highThreat(slowApproach));
        expect(Action.CHASE, policy.evaluate(highThreat(input(4, v(5, 0, 0), Vector.ZERO, Vector.ZERO))),
                "High-threat mode returns to attacks after the observed approach stops");
    }

    private static void asynchronousThreatAndStrikePriority() {
        AerialCombatPolicy policy = new AerialCombatPolicy();
        expect(Action.EVADE, policy.evaluate(health(0, 20, 0, false, false, false), true, false),
                "A validated background threat can start evasion");
        expect(Action.CHASE, policy.evaluate(health(1, 20, 0, false, false, false), true, true),
                "An immediate strike opportunity wins over an ordinary predicted threat");
        expect(Action.CHASE, policy.evaluate(health(2, 20, 0, false, false, false)),
                "Suppressed threats must not leave a delayed evasion after the strike");
        expect(Action.EVADE, policy.evaluate(health(3, 20, 0, true, false, false), true, false),
                "An urgent threat remains actionable when the caller releases strike priority");
        expect(Action.CHASE, policy.evaluate(health(4, 7, 0, false, false, true), true, true),
                "Strike priority wins; low health alone no longer forces retreat");
    }

    private static void healthRecoveryAndAbsorption() {
        AerialCombatPolicy policy = new AerialCombatPolicy();
        expect(Action.CHASE, policy.evaluate(health(0, 20, 0, false, false, false)), "Healthy initial pursuit");
        expect(Action.CHASE, policy.evaluate(health(1, 8, 0, false, false, false)),
                "Low health without a threat keeps pursuing (retreat policy removed)");
        expect(Action.EVADE, policy.evaluate(health(2, 9, 0, true, true, true)),
                "Weapon threats are honored even at low health");
        expect(Action.EVADE, policy.evaluate(health(3, 9, 0, true, false, false)),
                "Low health still respects a spear threat");

        policy.reset();
        expect(Action.CHASE, policy.evaluate(health(0, 4, 6, false, false, false)),
                "Absorption no longer feeds a retreat gate");
        expect(Action.CHASE, policy.evaluate(health(1, 4, 3, false, false, false)),
                "Absorption loss does not change the action without a threat");
        expect(Action.CHASE, policy.evaluate(health(2, 12, 0, false, false, false)),
                "A hurt event alone does not retreat");

        policy.reset();
        expect(Action.CHASE, policy.evaluate(health(0, 8, 0, false, false, false)),
                "Starting a session wounded begins pursuit, not retreat");
        expect(Action.EVADE, policy.evaluate(health(1, 8, 0, true, false, false)),
                "A wounded player still respects a current spear threat");

        policy.reset();
        ThreatInput highMax = new ThreatInput(0, v(10, 0, 0), Vector.ZERO, Vector.ZERO,
                false, false, 16, 0, 40, false, 1, false);
        expect(Action.CHASE, policy.evaluate(highMax), "Low health ratio never retreats regardless of max health");
    }

    private static void pursuitIsNotIncomingThreat() {
        ThreatInput fleeing = highThreat(input(0, v(6, 0, 0), v(3, 0, 0), v(0.4, 0, 0)));
        expect(Action.CHASE, fresh(fleeing), "Chasing a slower fleeing target does not imply enemy rushing");
        expect(Action.CHASE, fresh(input(0, v(6, 0, 0), v(3, 0, 0), v(1, 0, 0))),
                "Adaptive mode also distinguishes own pursuit from target approach");
        expect(Action.CHASE, fresh(highThreat(input(0, v(6, 0, 0), v(3, 0, 0), v(0, 0, 0.4)))),
                "Closing on a strafing target does not imply enemy rushing");
        expect(Action.CHASE, fresh(highThreat(input(0, v(6, 0, 0), v(3, 0, 0), v(-0.05, 0, 0.4)))),
                "A nearly tangential target needs meaningful inward motion to count as incoming");
        expect(Action.EVADE, fresh(highThreat(input(0, v(6, 0, 0), v(3, 0, 0), v(-0.4, 0, 0)))),
                "An opposing head-on advance is still a threat while we move forward");
        expect(Action.EVADE, fresh(input(0, v(6, 0, 0), v(3, 0, 0), v(-1, 0, 0))),
                "Adaptive mode still detects a fast opposing advance");
        expect(Action.EVADE, fresh(new ThreatInput(fleeing.tick(), fleeing.offset(), fleeing.selfVelocity(),
                        fleeing.targetVelocity(), true, false, 20, 0, 20, false, 1, true)),
                "A real spear threat remains actionable even when the target moves away");
        expect(Action.EVADE, fresh(new ThreatInput(fleeing.tick(), fleeing.offset(), fleeing.selfVelocity(),
                        fleeing.targetVelocity(), false, true, 20, 0, 20, false, 1, true)),
                "A real mace threat remains independent of the generic motion classifier");
    }

    private static void lifecycleAndInvalidInputs() {
        AerialCombatPolicy policy = new AerialCombatPolicy();
        policy.evaluate(health(100, 5, 0, false, false, true));
        expect(Action.CHASE, policy.evaluate(health(0, 20, 0, false, false, false)),
                "A rewound tick counter clears old combat state");
        policy.evaluate(health(1, 5, 0, false, false, false));
        expect(Action.CHASE, policy.evaluate(null), "Missing input resets the policy");
        expect(Action.CHASE, fresh(input(0, v(Double.NaN, 0, 0), Vector.ZERO, v(-3, 0, 0))),
                "Invalid geometry does not invent a threat");
        expect(Action.CHASE, fresh(input(0, v(10, 0, 0), Vector.ZERO, v(-21, 0, 0))),
                "Teleport-like velocity is not classified as a rush");
        expect(Action.CHASE, fresh(input(0, v(10, 0, 0), null, v(-3, 0, 0))),
                "Missing self velocity is insufficient for relative classification");

        policy.reset();
        policy.evaluate(health(1, 8, 0, false, false, true));
        expect(Action.CHASE, policy.evaluate(health(60, Double.NaN, 0, false, false, false)),
                "Invalid health does not change the action without a threat");
        expect(Action.CHASE, policy.evaluate(health(61, 12, 0, false, false, false)),
                "Valid input keeps evaluating normally");

        policy.reset();
        policy.evaluate(health(1, 8, 0, false, false, true));
        policy.evaluate(health(1, 8, 0, false, false, true));
        expect(Action.CHASE, policy.evaluate(health(41, 12, 0, false, false, false)),
                "Duplicate same-tick damage signals do not extend the interval twice");
        policy.reset();
        expect(Action.CHASE, policy.evaluate(health(100, 20, 0, false, false, false)),
                "Explicit lifecycle reset clears retreat and threat history");
    }

    private static void targetHysteresis() {
        check(!AerialCombatPolicy.shouldSwitchTarget(true, 10, 9.8, 50), "Small nearest-target jitter is ignored");
        check(!AerialCombatPolicy.shouldSwitchTarget(true, 10, 7, 9), "The lock holds against a closer candidate");
        check(AerialCombatPolicy.shouldSwitchTarget(true, 10, 7, 10), "A substantially closer candidate can win later");
        check(AerialCombatPolicy.shouldSwitchTarget(true, 10, 8, 10), "Two-block improvement is inclusive");
        check(!AerialCombatPolicy.shouldSwitchTarget(true, 50, 41, 20), "Far targets require proportional improvement");
        check(AerialCombatPolicy.shouldSwitchTarget(true, 50, 40, 20), "Twenty-percent improvement can switch");
        check(AerialCombatPolicy.shouldSwitchTarget(false, 10, 11, 0), "Invalid target replacement skips the lock");
        check(!AerialCombatPolicy.shouldSwitchTarget(false, 10, Double.NaN, 0), "An invalid candidate never wins");
        check(!AerialCombatPolicy.shouldSwitchTarget(true, 10, 1, -1), "Invalid lock age does not cause churn");
        check(!AerialCombatPolicy.shouldSwitchTarget(true, 1, 0, 20), "Near-target jitter keeps the existing target");
    }

    private static void boundedPrediction() {
        check(AerialCombatPolicy.coherent(v(2, 0, 0), v(1, 0, 0)), "Aligned observations are coherent");
        check(!AerialCombatPolicy.coherent(v(2, 0, 0), v(-1, 0, 0)), "A reversal invalidates long prediction");
        check(!AerialCombatPolicy.coherent(v(2, 0, 0), Vector.ZERO), "Stationary history does not imply direction");
        vector(v(1.65, 0, 0), AerialCombatPolicy.predictionVelocity(v(2, 0, 0), v(1, 0, 0), true),
                "Consistent motion blends both measured velocities");
        vector(v(2, 0, 0), AerialCombatPolicy.predictionVelocity(v(2, 0, 0), v(-1, 0, 0), true),
                "Reversals do not mix incompatible directions");
        vector(v(2, 0, 0), AerialCombatPolicy.predictionVelocity(v(2, 0, 0), v(1, 0, 0), false),
                "The first velocity is not diluted by an unavailable sample");
        vector(Vector.ZERO, AerialCombatPolicy.predictionVelocity(v(Double.POSITIVE_INFINITY, 0, 0), null, true),
                "Nonfinite prediction input is rejected");
        vector(Vector.ZERO, AerialCombatPolicy.predictionVelocity(v(21, 0, 0), null, false),
                "Implausible sample speed does not extrapolate");
        equal(4.0, AerialCombatPolicy.leadTicks(100, 2, 3, true), "Prediction never exceeds four ticks");
        equal(1.0, AerialCombatPolicy.leadTicks(100, 2, 3, false), "Inconsistent motion gets one tick at most");
        equal(0.5, AerialCombatPolicy.leadTicks(1, 2, 0, true), "A nearby intercept reduces prediction time");
        equal(0.0, AerialCombatPolicy.leadTicks(Double.NaN, 2, 3, true), "Invalid distance produces no lead");
        vector(v(12, 0, 0), AerialCombatPolicy.leadOffset(v(20, 0, 0), 4), "Spatial lead is limited to twelve blocks");
        vector(v(4, 0, 0), AerialCombatPolicy.leadOffset(v(1, 0, 0), 100), "Caller tick excess is also bounded");
        vector(Vector.ZERO, AerialCombatPolicy.leadOffset(v(1, 0, 0), -1), "Negative lead is rejected");
        vector(Vector.ZERO, AerialCombatPolicy.leadOffset(v(1, 0, 0), Double.NaN), "Nonfinite lead is rejected");
        Vector diagonal = AerialCombatPolicy.leadOffset(v(10, 10, 10), 4);
        equal(12.0, diagonal.length(), "Diagonal spatial lead has the same bound");
        equal(diagonal.x(), diagonal.y(), "Spatial limiting preserves direction");
    }

    private static ThreatInput input(long tick, Vector offset, Vector self, Vector target) {
        return new ThreatInput(tick, offset, self, target, false, false, 20, 0, 20, false, 1, false);
    }

    private static ThreatInput health(long tick, double health, double absorption,
                                      boolean spear, boolean mace, boolean hurt) {
        return new ThreatInput(tick, v(10, 0, 0), Vector.ZERO, Vector.ZERO,
                spear, mace, health, absorption, 20, hurt, 1, false);
    }

    private static ThreatInput highThreat(ThreatInput input) {
        return new ThreatInput(input.tick(), input.offset(), input.selfVelocity(), input.targetVelocity(),
                input.spearThreat(), input.maceThreat(), input.health(), input.absorption(), input.maxHealth(),
                input.hurt(), input.latencyTicks(), true);
    }

    private static Action fresh(ThreatInput input) {
        return new AerialCombatPolicy().evaluate(input);
    }

    private static Vector v(double x, double y, double z) { return new Vector(x, y, z); }

    private static void expect(Action expected, Action actual, String message) {
        check(expected == actual, message + ": expected " + expected + ", got " + actual);
    }

    private static void vector(Vector expected, Vector actual, String message) {
        check(actual.isFinite() && expected.subtract(actual).length() < 1.0E-10, message);
    }

    private static void equal(double expected, double actual, String message) {
        check(Double.isFinite(actual) && Math.abs(expected - actual) < 1.0E-10, message);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
