package dev.guizhou.util.render.targethud;

/**
 * Pure math for the two-layer air-combat HUD: bearings, radar projection,
 * closure/intercept/energy metrics and per-frame easing. No Minecraft types —
 * unit-testable, mirroring the shadcn prototype formulas.
 */
public final class TargetHudMath {
    private TargetHudMath() {}

    /** MC yaw convention: 0 = +Z (south), 90 = -X (west). atan2(-dx, dz). */
    public static double yawTo(double dx, double dz) {
        // -0.0 vs 0.0 flips atan2's sign at the -Z branch; normalize fixes it.
        return normalizeAngle(Math.toDegrees(Math.atan2(-dx, dz)));
    }

    /** Shortest signed angular difference from a to b, in (-180, 180]. */
    public static double wrapDelta(double a, double b) {
        return ((b - a + 540.0) % 360.0 + 360.0) % 360.0 - 180.0;
    }

    /**
     * Radar disc projection. Bearing 0 places the target straight "up" on the
     * disc (ahead of you); positive bearing rotates clockwise. Distance uses a
     * sqrt scale so 5/10/20 blocks stay readable.
     */
    public static double[] radarProject(double bearingDeg, double distance, double radius, double maxDistance) {
        double scaled = distance <= 0.0 ? 0.0 : Math.min(1.0, Math.sqrt(distance / maxDistance));
        double r = radius * scaled;
        double rad = Math.toRadians(bearingDeg);
        return new double[]{r * Math.sin(rad), -r * Math.cos(rad)};
    }

    /** Block-per-tick closure rate from consecutive distances; positive = closing. */
    public static double closure(double previousDistance, double distance) {
        return previousDistance - distance;
    }

    /** Ticks until contact at the current closure; negative when not solvable. */
    public static double interceptTicks(double distance, double closureRate, double epsilon) {
        if (closureRate <= epsilon) return -1.0;
        return distance / closureRate;
    }

    /** Energy advantage: speed edge minus the altitude debt, clamped to [-100, 100]. */
    public static double energyAdvantage(double myVelocity, double targetVelocity, double relativeAltitude) {
        double raw = myVelocity * 10.0 - targetVelocity * 10.0 - relativeAltitude * 2.2;
        return Math.max(-100.0, Math.min(100.0, raw));
    }

    /** Exponential easing step for continuous HUD values (dt ms, halfLife ms). */
    public static double ease(double current, double target, double deltaMs, double halfLifeMs) {
        if (halfLifeMs <= 0.0) return target;
        double k = Math.log(2.0) / halfLifeMs;
        double dt = Math.min(64.0, Math.max(0.0, deltaMs));
        return current + (target - current) * (1.0 - Math.exp(-k * dt));
    }

    /** Angular easing that always walks the short way around. */
    public static double easeAngle(double current, double target, double deltaMs, double halfLifeMs) {
        if (halfLifeMs <= 0.0) return normalizeAngle(target);
        double stepped = current + wrapDelta(current, target) * (1.0 - Math.exp(-Math.log(2.0) / halfLifeMs * Math.min(64.0, Math.max(0.0, deltaMs))));
        return normalizeAngle(stepped);
    }

    public static double normalizeAngle(double deg) {
        return ((deg % 360.0) + 360.0) % 360.0;
    }

    /** Alpha ramp for the layer cross-fade (0..1). */
    public static float ramp(float value, float step) {
        return Math.max(0.0f, Math.min(1.0f, value + step));
    }
}
