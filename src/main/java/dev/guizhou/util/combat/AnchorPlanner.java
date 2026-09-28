package dev.guizhou.util.combat;

import java.util.List;
import java.util.Map;

/** Real off-thread power-five candidate scoring, including collision rays and self damage. */
public final class AnchorPlanner {
    public record Candidate(int x, int y, int z, int predictionTicks) {
        public Candidate(int x, int y, int z) { this(x, y, z, 0); }
        public Candidate { if (predictionTicks < 0 || predictionTicks > 20) throw new IllegalArgumentException("Prediction bound"); }
    }
    public record Input(long generation, int tick, int difficulty, boolean assumeArmor, double minDamage,
                        double maxSelfDamage, CombatantSnapshot self, List<CombatantSnapshot> targets,
                        List<Candidate> candidates, CombatCollisionSnapshot terrain,
                        Map<Integer, List<CombatantSnapshot>> predictions, Map<Integer, CombatantSnapshot> selfPredictions) {
        public Input(long generation, int tick, int difficulty, boolean assumeArmor, double minDamage,
                     double maxSelfDamage, CombatantSnapshot self, List<CombatantSnapshot> targets,
                     List<Candidate> candidates, CombatCollisionSnapshot terrain) {
            this(generation, tick, difficulty, assumeArmor, minDamage, maxSelfDamage, self, targets, candidates, terrain,
                    Map.of(), Map.of());
        }
        public Input {
            targets = List.copyOf(targets); candidates = List.copyOf(candidates);
            if (targets.size() > 32 || candidates.size() > 4096) throw new IllegalArgumentException("Unbounded anchor input");
            var copy = new java.util.HashMap<Integer, List<CombatantSnapshot>>();
            predictions.forEach((ticks, values) -> {
                if (ticks < 0 || ticks > 20 || values.size() > 32) throw new IllegalArgumentException("Unbounded prediction");
                copy.put(ticks, List.copyOf(values));
            });
            predictions = Map.copyOf(copy);
            selfPredictions = Map.copyOf(selfPredictions);
            if (predictions.size() > 3 || selfPredictions.size() > 3) throw new IllegalArgumentException("Too many prediction stages");
        }
    }
    public record Result(Candidate candidate, float damage, float selfDamage, long rays, int evaluated) {}

    private AnchorPlanner() {}

    public static Result plan(Input input) {
        Candidate best = null;
        float bestDamage = 0, bestSelf = 0;
        long[] rays = {0};
        int evaluated = 0, visited = 0;
        long deadline = System.nanoTime() + 50_000_000L;
        for (Candidate candidate : input.candidates) {
            if (Thread.currentThread().isInterrupted() || (++visited & 7) == 0 && System.nanoTime() > deadline)
                throw new IllegalStateException("Anchor planning budget expired");
            float upper = 0;
            List<CombatantSnapshot> targets = input.predictions.getOrDefault(candidate.predictionTicks, input.targets);
            for (CombatantSnapshot target : targets)
                upper += damage(target, candidate, 1, input.difficulty, input.assumeArmor);
            if (upper < input.minDamage || upper <= bestDamage) continue;
            float total = 0;
            for (CombatantSnapshot target : targets) {
                if (distance(target, candidate) > 10) continue;
                total += damage(target, candidate, exposure(input.terrain, target, candidate, rays), input.difficulty, input.assumeArmor);
            }
            evaluated++;
            if (total <= bestDamage || total < input.minDamage) continue;
            float self = damage(input.self, candidate, exposure(input.terrain, input.self, candidate, rays), input.difficulty, false);
            CombatantSnapshot predictedSelf = input.selfPredictions.get(candidate.predictionTicks);
            if (predictedSelf != null) self = Math.max(self, damage(predictedSelf, candidate,
                    exposure(input.terrain, predictedSelf, candidate, rays), input.difficulty, false));
            if (self > input.maxSelfDamage || self > 0 && self + 1.5f >= input.self.effectiveHealth()) continue;
            best = candidate; bestDamage = total; bestSelf = self;
        }
        return new Result(best, bestDamage, bestSelf, rays[0], evaluated);
    }

    private static double distance(CombatantSnapshot target, Candidate candidate) {
        double dx = target.x() - candidate.x - .5, dy = target.y() - candidate.y - .5, dz = target.z() - candidate.z - .5;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static float damage(CombatantSnapshot target, Candidate candidate, double exposure, int difficulty, boolean assume) {
        double distance = distance(target, candidate);
        if (distance > 10 || difficulty == 0 || !target.alive()) return 0;
        double impact = (1 - distance / 10) * exposure;
        float raw = (float)((impact * impact + impact) / 2 * 70 + 1);
        if (difficulty == 1) raw = Math.min(raw / 2 + 1, raw);
        if (difficulty == 3) raw *= 1.5f;
        return ExplosionDamageModel.reduce(raw, target.armor(), target.toughness(), target.resistanceMultiplier(),
                assume ? target.assumedProtection() : target.protection());
    }

    private static double exposure(CombatCollisionSnapshot terrain, CombatantSnapshot target, Candidate candidate, long[] rays) {
        double dx = target.maxX() - target.minX(), dy = target.maxY() - target.minY(), dz = target.maxZ() - target.minZ();
        double sx = 1 / (dx * 2 + 1), sy = 1 / (dy * 2 + 1), sz = 1 / (dz * 2 + 1);
        if (sx <= 0 || sy <= 0 || sz <= 0) return 0;
        double ox = (1 - Math.floor(1 / sx) * sx) / 2, oz = (1 - Math.floor(1 / sz) * sz) / 2;
        int count = 0, visible = 0;
        for (double x = 0; x <= 1; x += sx) for (double y = 0; y <= 1; y += sy) for (double z = 0; z <= 1; z += sz) {
            if (terrain.clear(target.minX() + dx * x + ox, target.minY() + dy * y, target.minZ() + dz * z + oz,
                    candidate.x + .5, candidate.y + .5, candidate.z + .5, candidate.x, candidate.y, candidate.z)) visible++;
            count++;
        }
        rays[0] += count;
        return count == 0 ? 0 : (double)visible / count;
    }
}
