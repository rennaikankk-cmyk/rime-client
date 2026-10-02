package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Bounded separation and height planning; actual propulsion remains owned by flight. */
public final class EatingRetreat {
    public record Threat(Vec3 position, Vec3 velocity, double reach) {}
    private record Predicted(Vec3 position, double distance) {}
    private EatingRetreat() {}

    public static Vec3 direction(Vec3 self, Vec3 selfVelocity, List<Threat> threats,
                                 Vec3 fallback, double distance, double height, int latency,
                                 Predicate<Vec3> clear) {
        if (!finite(self) || !finite(selfVelocity) || threats == null || clear == null) return Vec3.ZERO;
        distance = bounded(distance, 6, 64, 16);
        height = bounded(height, 1, 24, 6);
        double lead = Math.clamp(latency, 1, 3);
        List<Predicted> observed = new ArrayList<>();
        Predicted nearest = null;
        double nearestSq = Double.POSITIVE_INFINITY;
        for (Threat threat : threats) {
            if (observed.size() == 8) break;
            if (threat == null || !finite(threat.position())) continue;
            Vec3 velocity = finite(threat.velocity()) ? threat.velocity() : Vec3.ZERO;
            Vec3 offset = velocity.scale(lead);
            if (offset.lengthSqr() > 144) offset = offset.normalize().scale(12);
            Vec3 position = threat.position().add(offset);
            Vec3 toward = position.subtract(self).normalize();
            double closing = Math.max(0, selfVelocity.subtract(velocity).dot(toward));
            double safe = Math.max(distance, bounded(threat.reach(), 0, 32, 3) + 4 + Math.min(8, closing * lead));
            Predicted prediction = new Predicted(position, safe);
            observed.add(prediction);
            double squared = self.distanceToSqr(position) / (safe * safe);
            if (squared < nearestSq) { nearestSq = squared; nearest = prediction; }
        }
        if (nearest == null) return Vec3.ZERO;
        Vec3 away = self.subtract(nearest.position()).multiply(1, 0, 1);
        if (away.lengthSqr() < 1e-9) away = finite(fallback) ? fallback.multiply(1, 0, 1) : Vec3.ZERO;
        if (away.lengthSqr() < 1e-9) away = new Vec3(1, 0, 0);
        away = away.normalize();
        double lift = Math.clamp((nearest.position().y + height - self.y) / Math.max(8, distance), 0, .6);
        // Without enough observed momentum, a steep request would stall rather than buy height.
        if (selfVelocity.horizontalDistance() < .5) lift = Math.min(lift, .12);
        boolean nearDanger = nearestSq < 1.15 * 1.15;
        double probe = Math.clamp(selfVelocity.length(), 1, 10);
        Vec3 best = Vec3.ZERO;
        double bestScore = -Double.MAX_VALUE;
        for (double angle : new double[]{0, 30, -30, 60, -60, 90, -90, 180}) {
            double radians = Math.toRadians(angle);
            Vec3 horizontal = new Vec3(away.x * Math.cos(radians) - away.z * Math.sin(radians), 0,
                    away.x * Math.sin(radians) + away.z * Math.cos(radians));
            for (double y : new double[]{lift, 0}) {
                Vec3 candidate = horizontal.add(0, y, 0).normalize();
                if (!clear.test(candidate.scale(probe))) continue;
                double worst = Double.POSITIVE_INFINITY;
                for (Predicted enemy : observed) {
                    double before = self.distanceTo(enemy.position());
                    double after = self.add(candidate.scale(probe * 2)).distanceTo(enemy.position());
                    Vec3 offset = self.subtract(enemy.position());
                    double closestAt = Math.clamp(-offset.dot(candidate), 0, probe * 2);
                    double closest = offset.add(candidate.scale(closestAt)).length();
                    if (closest < Math.min(before, enemy.distance()) - .01) { worst = -Double.MAX_VALUE; break; }
                    worst = Math.min(worst, after / enemy.distance());
                }
                if (worst == -Double.MAX_VALUE) continue;
                // Once clear, retain a safe lane around the enemy instead of fleeing
                // outside target range for the remainder of a long boosted meal.
                double spacing = nearDanger ? Math.min(2, worst) : 1 - Math.abs(worst - 1.3) * .3;
                double score = spacing + candidate.dot(away) * .03 + candidate.y * .45
                        + candidate.dot(selfVelocity.normalize()) * .02;
                if (score > bestScore) { bestScore = score; best = candidate; }
            }
        }
        return best;
    }

    private static boolean finite(Vec3 value) { return value != null && Double.isFinite(value.lengthSqr()); }
    private static double bounded(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
}
