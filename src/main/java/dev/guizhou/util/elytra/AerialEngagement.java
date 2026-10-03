package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

/** Short movement windows for steering, never an extension of a weapon's attack range. */
public final class AerialEngagement {
    private AerialEngagement() {}

    public record Step(Vec3 velocity, boolean precision) {}

    /** Reserve charge time at relative closing speed, including opposing passes. */
    public static double chargeRange(Vec3 offset, Vec3 selfVelocity, Vec3 targetVelocity,
                                      double reach, int chargeTicks, int latencyTicks) {
        if (!finite(offset) || !motion(selfVelocity) || !motion(targetVelocity)
                || !Double.isFinite(reach) || reach < 0 || reach > 16) return 0;
        double closing = offset.lengthSqr() < 1e-9 ? 0
                : selfVelocity.subtract(targetVelocity).dot(offset.normalize());
        double approach = Math.max(selfVelocity.length(), Math.max(0, closing));
        return Math.min(256, reach + approach * (Math.clamp(chargeTicks, 0, 40)
                + Math.clamp(latencyTicks, 0, 3) + 2));
    }

    /** Applied motion is useful only in the same uninterrupted glide, for at most one tick. */
    public static Vec3 observedVelocity(Vec3 fallback, Vec3 applied, long age, boolean uninterrupted) {
        Vec3 safe = motion(fallback) ? fallback : Vec3.ZERO;
        return uninterrupted && age >= 0 && age <= 1 && motion(applied) ? applied : safe;
    }

    /** Does relative movement enter the setup sphere before the controller can react? */
    public static boolean closingWindow(Vec3 offset, Vec3 selfVelocity, Vec3 targetVelocity,
                                        double boxDistance, double setupRange, int responseTicks) {
        if (!finite(offset) || !motion(selfVelocity) || !motion(targetVelocity)
                || !Double.isFinite(boxDistance) || boxDistance < 0
                || !Double.isFinite(setupRange) || setupRange < 0 || setupRange > 64) return false;
        if (boxDistance <= setupRange) return true;
        Vec3 relative = targetVelocity.subtract(selfVelocity);
        double squareSpeed = relative.lengthSqr();
        if (squareSpeed < 1e-9 || offset.dot(relative) >= 0) return false;
        double ticks = Math.clamp(-offset.dot(relative) / squareSpeed, 0, Math.clamp(responseTicks, 1, 4));
        // Use the observed distance to the box, rather than counting its radius twice.
        double radius = Math.clamp(offset.length() - boxDistance, 0, 4);
        return offset.add(relative.scale(ticks)).length() <= setupRange + radius;
    }

    /** Near a level/falling apex, close the error while retaining the target's drift.
     * A rising opponent still needs climb thrust: braking before buying the height
     * advantage can lock both players into the same endless upward pursuit. */
    public static Step climbStep(Vec3 offset, Vec3 targetVelocity, double speed) {
        if (!finite(offset) || !motion(targetVelocity) || !Double.isFinite(speed) || speed <= 0 || speed > 20
                || targetVelocity.y > .15 && offset.y > 0
                || offset.length() > Math.max(3, speed * 2)) return new Step(offset, false);
        Vec3 request = targetVelocity.add(offset.scale(.5));
        if (request.length() > speed) request = request.normalize().scale(speed);
        return new Step(request, true);
    }

    /** Approach the drop setup altitude while matching horizontal drift.
     * Used only when a natural drop is not yet reachable: keeping Y=0 at a high,
     * aligned position otherwise hovers forever above a target that has fallen away. */
    public static Vec3 maceStagingStep(Vec3 feetOffset, Vec3 targetVelocity, double preferredHeight, double speed) {
        if (!finite(feetOffset) || !motion(targetVelocity) || !Double.isFinite(preferredHeight)
                || !Double.isFinite(speed) || speed <= 0 || speed > 20) return Vec3.ZERO;
        double height = Math.clamp(preferredHeight + 2, 4, 64);
        double verticalError = feetOffset.y + height;
        // Keep an acquired, nearby height advantage against a rising target.
        // Closing that reserve prematurely shortens the natural fall; only a
        // substantial excess height (or a falling target) needs altitude correction.
        boolean retainRise = targetVelocity.y > .15 && verticalError >= -Math.max(12, height);
        Vec3 result = new Vec3(targetVelocity.x + feetOffset.x * .5,
                retainRise ? targetVelocity.y : targetVelocity.y + Math.clamp(verticalError * .25, -3, 3),
                targetVelocity.z + feetOffset.z * .5);
        return result.length() > speed ? result.normalize().scale(speed) : result;
    }

    /** A falling mace can reach upward too; compare its standing eye with the victim's box.
     * The short margin predicts danger only and never authorizes an attack. */
    public static boolean maceThreatWindow(Vec3 attackerEye, Vec3 attackerVelocity,
                                            AABB victimBox, Vec3 victimVelocity,
                                            double reach, int responseTicks) {
        if (!finite(attackerEye) || !motion(attackerVelocity) || attackerVelocity.y >= -.15
                || victimBox == null || !Double.isFinite(reach) || reach <= 0 || reach > 16) return false;
        return closingWindow(victimBox.getCenter().subtract(attackerEye), attackerVelocity,
                victimVelocity, Math.sqrt(victimBox.distanceToSqr(attackerEye)),
                reach + .5, responseTicks);
    }

    /** Anticipate a powered step that would cross the spear band before a second query. */
    public static Step spearStep(Vec3 request, Vec3 offset, Vec3 targetVelocity, Vec3 appliedVelocity,
                                  double boxDistance, double minRange, double maxRange) {
        if (!finite(request) || !finite(offset) || !motion(targetVelocity) || !motion(appliedVelocity)
                || !Double.isFinite(minRange) || minRange < 0 || !Double.isFinite(maxRange)
                || maxRange <= minRange || maxRange > 16 || !Double.isFinite(boxDistance)
                || boxDistance < minRange + .35 || request.lengthSqr() < 1e-9
                || offset.lengthSqr() < 1e-9 || request.normalize().dot(offset.normalize()) < .8)
            return new Step(request, false);
        boolean near = closingWindow(offset, appliedVelocity, targetVelocity, boxDistance, maxRange + 3, 2)
                || closingWindow(offset, request, targetVelocity, boxDistance, maxRange + 3, 2);
        if (!near) return new Step(request, false);
        Vec3 limited = SpearApproach.limitStep(request, offset, targetVelocity,
                boxDistance, minRange, maxRange, true);
        return new Step(limited, true);
    }

    private static boolean motion(Vec3 v) { return finite(v) && v.lengthSqr() <= 32 * 32; }
    private static boolean finite(Vec3 v) {
        return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z)
                && Double.isFinite(v.lengthSqr());
    }
}
