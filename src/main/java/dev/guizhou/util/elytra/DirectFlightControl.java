package dev.guizhou.util.elytra;
import net.minecraft.world.phys.Vec3;

/** NoAC speed settings are independent horizontal and vertical budgets. */
public final class DirectFlightControl {
    private DirectFlightControl() {}
    /** Ordinary pressure hits must not indefinitely starve the mixed weapon phase. */
    public static boolean shouldChargeSpear(long phaseAge, long pressureAge, double heightAdvantage, boolean diving) {
        return !diving && phaseAge >= 80 && pressureAge >= 8 && Double.isFinite(heightAdvantage)
                && heightAdvantage >= 0;
    }
    /** Only a sustained rising stalemate may use an ordinary, fully cooled mace hit. */
    public static boolean macePressure(int climbingTicks, double selfY, double targetY, double cooldown, boolean diving) {
        return !diving && climbingTicks >= 40 && Double.isFinite(selfY) && Double.isFinite(targetY)
                && selfY > .2 && selfY <= 32 && targetY > .2 && targetY <= 32
                && Double.isFinite(cooldown) && cooldown > .95;
    }
    public static double budget(double horizontal, double vertical) {
        return valid(horizontal) && valid(vertical) ? Math.hypot(horizontal, vertical) : 0;
    }
    public static Vec3 limit(Vec3 request, double horizontal, double vertical) {
        if (request == null || !Double.isFinite(request.lengthSqr()) || !valid(horizontal) || !valid(vertical)) return Vec3.ZERO;
        double flat = request.horizontalDistance();
        double scale = flat > horizontal ? horizontal / flat : 1;
        return new Vec3(request.x * scale, Math.clamp(request.y, -vertical, vertical), request.z * scale);
    }
    /** Keep a small height advantage through a MXS charge; callers enforce axis caps. */
    public static Vec3 spearLane(Vec3 request, double feetOffset, double targetVertical) {
        return spearLane(request, feetOffset, targetVertical, 2);
    }
    /** Height tracking owns charge/recovery only; a ready pass keeps its kinetic attack direction. */
    public static Vec3 mxsSpearStep(Vec3 request, double feetOffset, double targetVertical,
                                    boolean ready, boolean retreating) {
        if (request == null || !Double.isFinite(request.lengthSqr())) return Vec3.ZERO;
        return ready && !retreating ? request : spearLane(request, feetOffset, targetVertical, ready ? 0 : 2);
    }
    /** A ready spear tracks the attack plane instead of repeatedly diving below a rising target. */
    public static Vec3 spearLane(Vec3 request, double feetOffset, double targetVertical, double height) {
        if (request == null || !Double.isFinite(request.lengthSqr()) || !Double.isFinite(feetOffset)
                || !Double.isFinite(targetVertical) || Math.abs(targetVertical) > 32
                || !Double.isFinite(height) || Math.abs(height) > 8) return Vec3.ZERO;
        return new Vec3(request.x, targetVertical + Math.clamp((feetOffset + height) * .5, -2, 2), request.z);
    }
    private static boolean valid(double value) { return Double.isFinite(value) && value >= 0 && value <= 100; }
}
