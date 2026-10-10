package dev.guizhou.util.elytra;

import java.util.Optional;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Immutable worker input. A suggested heading still needs a full live trajectory check. */
public record FlightRecoveryPlan(long generation, long tick, Vec3 origin, Vec3 velocity, Vec3 preferred,
        double gravity, boolean rocket, FlightCollisionSnapshot snapshot) {
    public Optional<Vec3> calculate() {
        if (!finite(origin) || !finite(velocity) || !finite(preferred) || snapshot == null) return Optional.empty();
        AABB bounds = snapshot.bounds();
        double clearance = Math.min(Math.min(origin.x - bounds.minX, bounds.maxX - origin.x),
                Math.min(Math.min(origin.y - bounds.minY, bounds.maxY - origin.y),
                        Math.min(origin.z - bounds.minZ, bounds.maxZ - origin.z))) - 1.0;
        int horizon = (int) Math.clamp(clearance / Math.max(1.5, velocity.length() + (rocket ? 1.7 : 0.0)), 2.0, 6.0);
        return FlightTerrainSafety.recoveryLook(velocity, preferred, gravity, horizon, rocket,
                (from, to) -> snapshot.clear(origin.add(from), origin.add(to)));
    }

    public boolean matches(long currentGeneration, long currentTick, Vec3 position,
            Vec3 currentVelocity, Vec3 currentPreferred) {
        if (generation != currentGeneration || !finite(origin) || !finite(velocity) || !finite(preferred)
                || !finite(position) || !finite(currentVelocity) || !finite(currentPreferred)) return false;
        long age = currentTick - tick;
        double tolerance = Math.max(0.25, currentVelocity.length() * 0.35);
        return age >= 0 && age <= 4 && origin.distanceToSqr(position) <= 16.0
                && velocity.distanceToSqr(currentVelocity) <= tolerance * tolerance
                && preferred.normalize().dot(currentPreferred.normalize()) >= 0.9;
    }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y)
                && Double.isFinite(value.z) && Double.isFinite(value.lengthSqr());
    }
}
