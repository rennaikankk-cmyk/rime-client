package dev.guizhou.util.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class CrystalSelectionPolicyTest {
    private record Target(float health, boolean broken, boolean face) {}
    private record Candidate(float[] upper, float[] actual) {}

    public static void main(String[] args) {
        // Two nonlethal hits must not be mistaken for a kill on the primary target.
        boolean lethal = CrystalSelectionPolicy.isLethal(5, 8, 0)
                || CrystalSelectionPolicy.isLethal(5, 20, 0);
        check(!CrystalSelectionPolicy.baseBalanceAllows(10, 11, lethal), "aggregate damage is not a kill");
        check(CrystalSelectionPolicy.baseBalanceAllows(10, 10, lethal), "balanced trade allowed");
        check(CrystalSelectionPolicy.baseBalanceAllows(10, 11,
                CrystalSelectionPolicy.isLethal(8, 8, 0)), "actual individual kill permits trade");
        check(!CrystalSelectionPolicy.isLethal(8, 8, 2), "absorption prevents false kill");
        check(CrystalSelectionPolicy.isLethal(10, 8, 2), "absorption included in lethal threshold");
        check(!CrystalSelectionPolicy.isLethal(10, 0, 0), "dead entity is not a kill opportunity");
        check(!CrystalSelectionPolicy.isLethal(Float.NaN, 8, 0), "unknown target damage rejected");
        check(!CrystalSelectionPolicy.baseBalanceAllows(10, Float.NaN, true), "unknown self damage rejected");
        expect(4.0, threshold(new Target(20f, false, false)), "normal minimum");
        expect(1.0, threshold(new Target(7f, false, false)), "low health");
        expect(1.0, threshold(new Target(20f, true, false)), "broken armor");
        expect(0.25, threshold(new Target(7f, true, false)), "combined reduction");
        expect(0.5, threshold(new Target(8f, false, true)), "face threshold");
        expectSafe(true, 4.0F, 4.0, 20.0F, 1.5, "maximum self damage is inclusive");
        expectSafe(false, 4.1F, 4.0, 20.0F, 1.5, "maximum self damage exceeded");
        expectSafe(false, 4.0F, 4.0, 5.5F, 1.5, "reserve boundary is exclusive");
        expectSafe(true, 4.0F, 4.0, 5.6F, 1.5, "reserve remains after damage");
        expectSafe(false, 5.0F, 36.0, 5.0F, 0.0, "zero reserve still rejects lethal damage");
        expectSafe(false, Float.NaN, 4.0, 20.0F, 1.5, "unknown self damage rejected");
        expectSafe(false, Float.POSITIVE_INFINITY, 4.0, 20.0F, 1.5, "infinite self damage rejected");

        Target[] faceTarget = {new Target(8f, false, true)};
        Candidate finisher = new Candidate(new float[]{3f}, new float[]{2.5f});
        expect(3.0, score(finisher.upper(), faceTarget), "finisher survives upper-bound pass");
        expect(2.5, rankedBest(List.of(finisher), faceTarget), "finisher selected below normal minimum");

        Target[] targets = {new Target(20f, false, false), new Target(8f, false, true)};
        // The second candidate's ordinary-target damage is zero. Its face-place contribution
        // must still participate in its bound, or a sorted scan stops at the wrong winner.
        List<Candidate> candidates = List.of(
                new Candidate(new float[]{4.5f, 0f}, new float[]{4.5f, 0f}),
                new Candidate(new float[]{4f, 3f}, new float[]{4f, 2.5f}));
        expect(6.5, rankedBest(candidates, targets), "face damage participates in pruning bound");

        Target[] policies = {
                new Target(20f, false, false), new Target(7f, false, false),
                new Target(20f, true, false), new Target(7f, true, false),
                new Target(8f, false, true)
        };
        for (Target first : policies) {
            for (Target second : policies) {
                Target[] pair = {first, second};
                List<Candidate> samples = new ArrayList<>();
                for (int a = 0; a <= 32; a++) {
                    for (int b = 0; b <= 32; b++) {
                        float[] actual = {a / 4f, b / 4f};
                        float[] upper = {actual[0] + (b % 3) / 4f, actual[1] + (a % 3) / 4f};
                        Candidate c = new Candidate(upper, actual);
                        if (score(upper, pair) < score(actual, pair)) {
                            throw new AssertionError("upper bound underestimates accepted damage");
                        }
                        samples.add(c);
                    }
                }
                float exhaustive = 0f;
                for (Candidate sample : samples) exhaustive = Math.max(exhaustive, score(sample.actual(), pair));
                expect(exhaustive, rankedBest(samples, pair), "ranked pruning matches exhaustive search");
            }
        }
        System.out.println("CrystalSelectionPolicyTest: all checks passed");
    }

    private static double threshold(Target target) {
        return CrystalSelectionPolicy.minimumDamage(4.0, target.health(), target.broken(), target.face());
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void expectSafe(boolean expected, float damage, double maximum,
                                   float health, double reserve, String message) {
        boolean actual = CrystalSelectionPolicy.isSelfDamageSafe(damage, maximum, health, reserve);
        if (actual != expected) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }

    private static float score(float[] damage, Target[] targets) {
        float total = 0f;
        for (int i = 0; i < targets.length; i++) {
            if (damage[i] >= threshold(targets[i])) total += damage[i];
        }
        return total;
    }

    private static float rankedBest(List<Candidate> candidates, Target[] targets) {
        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble((Candidate c) -> score(c.upper(), targets)).reversed());
        float best = 0f;
        for (Candidate candidate : sorted) {
            if (score(candidate.upper(), targets) <= best) break;
            best = Math.max(best, score(candidate.actual(), targets));
        }
        return best;
    }

    private static void expect(double expected, double actual, String message) {
        if (Math.abs(expected - actual) > 1.0e-6) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
}
