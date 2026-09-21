package dev.guizhou.util.elytra;

/** Decides when to end a retreat; flight limits and collision checks still own movement. */
public final class SpearRecovery {
    private SpearRecovery() {}

    public record Input(double distance, double boxDistance, double clearance, double desiredDistance,
                        double reach, double ownSpeed, double targetRadialSpeed, int chargeTicks,
                        int elapsedTicks, int maxTicks, double confidence) {}

    public static boolean resume(Input in) {
        // Preserve the existing bounded recovery even when observations are unavailable.
        if (in.elapsedTicks > in.maxTicks) return true;
        if (!finite(in.distance, in.boxDistance, in.clearance, in.desiredDistance, in.reach,
                in.ownSpeed, in.targetRadialSpeed, in.confidence)
                || in.distance < 0 || in.boxDistance < 0 || in.clearance < 0 || in.reach <= 0
                || in.ownSpeed < 0 || in.ownSpeed > 20 || Math.abs(in.targetRadialSpeed) > 20
                || in.chargeTicks < 0 || in.elapsedTicks < 2
                || in.boxDistance <= in.clearance) return false;

        if (in.chargeTicks == 0 && in.distance >= in.desiredDistance) return true;
        // A fleeing target already makes room to charge. Do not keep flying the other way
        // just to consume a fixed cooldown. Unknown, turning or discontinuous samples
        // cannot justify this early re-entry.
        if (in.confidence < 0.6 || in.targetRadialSpeed <= 0.15 || in.ownSpeed < 0.05) return false;
        double closingSpeed = in.ownSpeed - in.targetRadialSpeed;
        if (closingSpeed <= 0) return true;
        double travelTicks = Math.max(0, in.distance - in.reach) / closingSpeed;
        return travelTicks >= in.chargeTicks;
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
}
