package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;

/** Teleport arrival hazard, deliberately separate from projectile damage. */
public final class PearlLandingThreat {
    public record Threat(int pearlId, int ownerId, Vec3 landing, int arrivalTicks,
                         double radius, double severity) {
        public boolean urgent() { return arrivalTicks <= 3; }
    }

    public static Threat assess(int pearlId, int ownerId, Vec3 landing, int ticks,
                                Vec3 self, Vec3 velocity, double reach, boolean hostile) {
        if (!hostile || !finite(landing) || !finite(self) || !finite(velocity)
                || ticks < 1 || ticks > 12 || !Double.isFinite(reach) || reach <= 0) return null;
        // Long extrapolation is unreliable during turns; only imminent arrivals take control.
        Vec3 future = self.add(velocity.scale(ticks));
        double radius = Math.clamp(reach, 2.0, 12.0) + 1.0;
        double distance = future.distanceTo(landing);
        if (distance > radius) return null;
        double severity = 0.5 + 0.25 * (1.0 - distance / radius) + 0.25 * (1.0 - ticks / 12.0);
        return new Threat(pearlId, ownerId, landing, ticks, radius, severity);
    }

    /** Score by the worst arrival hazard; callers still check actual terrain. */
    public static double clearance(Vec3 self, Vec3 direction, double speed, Threat threat) {
        Vec3 future = self.add(direction.scale(speed * Math.min(3, threat.arrivalTicks())));
        return future.distanceTo(threat.landing()) / threat.radius();
    }

    private static boolean finite(Vec3 v) { return v != null && Double.isFinite(v.lengthSqr()); }
    private PearlLandingThreat() {}
}
