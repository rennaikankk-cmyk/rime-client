package dev.guizhou.util.elytra;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Elytra steering and an approximate glide/firework velocity envelope.
 * The envelope is a local heuristic, not proof of server acceptance: server
 * state, protocol version, active rockets and collision handling also matter.
 */
public final class ElytraPhysics {

    private ElytraPhysics() {
    }

    public static final double DEFAULT_GRAVITY = 0.08;
    private static final float DEG_TO_RAD = 0.017453292F;
    private static final double CORNER_EPS = 0.01;

    public static Vec3 pitchYawToRotation(float pitchDeg, float yawDeg) {
        if (!Float.isFinite(pitchDeg) || !Float.isFinite(yawDeg)) return Vec3.ZERO;
        float f = pitchDeg * DEG_TO_RAD;
        float g = yawDeg * DEG_TO_RAD;
        float cosP = Mth.cos(f);
        float sinP = Mth.sin(f);
        float cosY = Mth.cos(g);
        float sinY = Mth.sin(g);
        return new Vec3(-sinY * cosP, -sinP, cosY * cosP);
    }

    /** pitch (positive down) of a (ideally normalized) look vector, degrees */
    public static float rotationToPitch(Vec3 look) {
        if (!isFinite(look)) return 0.0F;
        Vec3 n = look.lengthSqr() > 1.0E-6 && Math.abs(look.length() - 1.0) > 1.0E-4
                ? look.normalize()
                : look;
        return (float) Math.toDegrees(-Math.asin(Mth.clamp(n.y, -1.0, 1.0)));
    }

    /** yaw (MC convention) of a look vector with a horizontal component, degrees */
    public static float rotationToYaw(Vec3 look) {
        if (!isFinite(look)) return 0.0F;
        return (float) Math.toDegrees(Math.atan2(-look.x, look.z));
    }

    /** vanilla 1.21 elytra travel() simulation: one tick of glide from oldVelocity */
    public static Vec3 simulateGlide(Vec3 oldVelocity, Vec3 look, double gravity) {
        if (!isFinite(oldVelocity) || !isFinite(look) || !Double.isFinite(gravity)) return Vec3.ZERO;
        float pitch = rotationToPitch(look);
        float pitchRad = pitch * DEG_TO_RAD;
        double lookHorizLen = Math.sqrt(look.x * look.x + look.z * look.z);
        double initialHorizSpeed = oldVelocity.horizontalDistance();
        double cosPitchSq = square(Math.cos(pitchRad));

        // Pitch is positive down; level flight receives the most lift.
        double newY = oldVelocity.y + gravity * (cosPitchSq * 0.75 - 1.0);
        Vec3 vel = new Vec3(oldVelocity.x, newY, oldVelocity.z);

        // lift while descending with a look direction
        if (newY < 0.0 && lookHorizLen > 0.0) {
            double lift = newY * -0.1 * cosPitchSq;
            vel = vel.add(look.x * lift / lookHorizLen, lift, look.z * lift / lookHorizLen);
        }

        // Convert horizontal speed into lift while looking up.
        if (pitchRad < 0.0F && lookHorizLen > 0.0) {
            double dive = initialHorizSpeed * (-Mth.sin(pitchRad)) * 0.04;
            vel = vel.add(-look.x * dive / lookHorizLen, dive * 3.2, -look.z * dive / lookHorizLen);
        }

        // horizontal velocity bends toward the look direction
        if (lookHorizLen > 0.0) {
            double targetScale = initialHorizSpeed / lookHorizLen;
            vel = vel.add((look.x * targetScale - vel.x) * 0.1, 0.0, (look.z * targetScale - vel.z) * 0.1);
        }

        // air drag: horizontal 0.99, vertical 0.98
        return finiteOrZero(vel.multiply(0.99, 0.98, 0.99));
    }

    /** WASD-style (strafe, up, forward) input turned into a world-space vector */
    public static Vec3 movementInputToVelocity(Vec3 movementInput, float speed, float yawDeg) {
        if (!isFinite(movementInput) || !Float.isFinite(speed) || !Float.isFinite(yawDeg)) return Vec3.ZERO;
        double d = movementInput.lengthSqr();
        if (d < 1.0E-7) {
            return Vec3.ZERO;
        }
        Vec3 scaled = (d > 1.0 ? movementInput.normalize() : movementInput).scale(speed);
        float sin = Mth.sin(yawDeg * DEG_TO_RAD);
        float cos = Mth.cos(yawDeg * DEG_TO_RAD);
        return finiteOrZero(new Vec3(scaled.x * cos - scaled.z * sin, scaled.y, scaled.z * cos + scaled.x * sin));
    }

    /** Shape a climb request at 54.5 degrees without inventing a horizontal heading. */
    public static Vec3 bestPullupSpeed(Vec3 motion) {
        if (!isFinite(motion)) return Vec3.ZERO;
        double horizontal = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        if (horizontal < 1.0E-6) {
            return motion;
        }
        return finiteOrZero(new Vec3(motion.x, horizontal * Math.tan(Math.toRadians(54.5)), motion.z));
    }

    /** Shape a shallow downward request at 30.5 degrees. */
    public static Vec3 bestDownForwardSpeed(Vec3 motion) {
        if (!isFinite(motion)) return Vec3.ZERO;
        double horizontal = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        if (horizontal < 1.0E-6) {
            return motion;
        }
        return finiteOrZero(new Vec3(motion.x, -horizontal * Math.tan(Math.toRadians(30.5)), motion.z));
    }

    /**
     * Project the request onto the local glide/firework envelope. Preserve its
     * direction when a nonnegative ray intersects the box; otherwise project
     * each component. Small requests may expand to available envelope headroom.
     *
     * @param lastVel     previous local velocity sample
     * @param lastSimVel  completed glide calculation of lastVel for this tick
     * @param lookNow     current normalized look vector
     * @param lookLast    previous tick normalized look vector
     */
    public static Vec3 clampToGrimEnvelope(Vec3 motion, Vec3 lastVel, Vec3 lastSimVel,
            Vec3 lookNow, Vec3 lookLast, double rescaleAmount, boolean cornerClimb, double yCap) {
        return clampToGrimEnvelope(motion, lastVel, lastSimVel, lookNow, lookLast,
                rescaleAmount, cornerClimb, yCap, 0.3);
    }

    public static Vec3 clampToGrimEnvelope(Vec3 motion, Vec3 lastVel, Vec3 lastSimVel,
            Vec3 lookNow, Vec3 lookLast, double rescaleAmount, boolean cornerClimb,
            double yCap, double zeroPointThree) {
        return clampToGrimEnvelope(motion, lastVel, lastSimVel, lookNow, lookLast,
                rescaleAmount, cornerClimb, yCap, zeroPointThree, false);
    }

    public static Vec3 clampToGrimEnvelope(Vec3 motion, Vec3 lastVel, Vec3 lastSimVel,
            Vec3 lookNow, Vec3 lookLast, double rescaleAmount, boolean cornerClimb,
            double yCap, double zeroPointThree, boolean precision) {
        if (!isFinite(motion) || !isFinite(lastVel) || !isFinite(lastSimVel)
                || !isFinite(lookNow) || !isFinite(lookLast)) return Vec3.ZERO;
        if (motion.lengthSqr() < 1.0E-6) return capVertical(motion, yCap);
        if (!Double.isFinite(rescaleAmount) || rescaleAmount < 0.0) rescaleAmount = 0.0;
        if (!Double.isFinite(zeroPointThree) || zeroPointThree < 0.0) zeroPointThree = 0.0;

        // Approximate firework expansion around the two sampled look directions.
        double antiTickSkipping = 0.05;
        double minX = Math.min(-antiTickSkipping, lookNow.x) + Math.min(-antiTickSkipping, lookLast.x);
        double minY = Math.min(-antiTickSkipping, lookNow.y) + Math.min(-antiTickSkipping, lookLast.y);
        double minZ = Math.min(-antiTickSkipping, lookNow.z) + Math.min(-antiTickSkipping, lookLast.z);
        double maxX = Math.max(antiTickSkipping, lookNow.x) + Math.max(antiTickSkipping, lookLast.x);
        double maxY = Math.max(antiTickSkipping, lookNow.y) + Math.max(antiTickSkipping, lookLast.y);
        double maxZ = Math.max(antiTickSkipping, lookNow.z) + Math.max(antiTickSkipping, lookLast.z);

        double threshold = Math.min(rescaleAmount, motion.length());
        minX = Math.max(-threshold, minX * threshold);
        maxX = Math.min(threshold, maxX * threshold);
        minY = Math.max(-threshold, minY * threshold);
        maxY = Math.min(threshold, maxY * threshold);
        minZ = Math.max(-threshold, minZ * threshold);
        maxZ = Math.min(threshold, maxZ * threshold);

        // Offset the expansion by the local glide prediction.
        double uMinX = lastSimVel.x + Math.min(0, minX - lastVel.x);
        double uMaxX = lastSimVel.x + Math.max(0, maxX - lastVel.x);
        double uMinY = lastSimVel.y + Math.min(0, minY - lastVel.y);
        double uMaxY = lastSimVel.y + Math.max(0, maxY - lastVel.y);
        double uMinZ = lastSimVel.z + Math.min(0, minZ - lastVel.z);
        double uMaxZ = lastSimVel.z + Math.max(0, maxZ - lastVel.z);

        // Retain the existing near-vertical headroom heuristic. Its additional
        // allowance is configurable and remains subject to the final Y cap.
        if (zeroPointThree > 0.0 && uMaxY > 1.0E-6 && motion.horizontalDistance() < 0.04 * motion.y) {
            double len = motion.length();
            if (len > 1.0E-6) {
                Vec3 boosted = motion.scale((len + zeroPointThree) / len);
                double simY = simulateGlide(boosted, lookNow, DEFAULT_GRAVITY).y;
                uMaxY = Math.max(uMaxY, simY);
            }
        }

        return projectToEnvelope(motion, new Vec3(uMinX, uMinY, uMinZ),
                new Vec3(uMaxX, uMaxY, uMaxZ), cornerClimb, yCap, precision);
    }

    /**
     * Fill requested axes toward the local box edges, with a small inset.
     * YCap uses blocks/tick; a nonpositive or nonfinite cap disables the cap.
     */
    public static Vec3 cornerFill(Vec3 clamped, Vec3 desired, Vec3 envelopeMin, Vec3 envelopeMax, double yCap) {
        if (!isFinite(clamped) || !isFinite(desired) || !validBounds(envelopeMin, envelopeMax)) return Vec3.ZERO;
        Vec3 min = capVertical(envelopeMin, yCap);
        Vec3 max = capVertical(envelopeMax, yCap);
        return new Vec3(
                pick(desired.x, min.x, max.x, clamped.x),
                pick(desired.y, min.y, max.y, clamped.y),
                pick(desired.z, min.z, max.z, clamped.z));
    }

    static Vec3 projectToEnvelope(Vec3 motion, Vec3 envelopeMin, Vec3 envelopeMax, boolean cornerClimb, double yCap) {
        return projectToEnvelope(motion, envelopeMin, envelopeMax, cornerClimb, yCap, false);
    }

    static Vec3 projectToEnvelope(Vec3 motion, Vec3 envelopeMin, Vec3 envelopeMax,
                                  boolean cornerClimb, double yCap, boolean precision) {
        if (!isFinite(motion) || !validBounds(envelopeMin, envelopeMax)) return Vec3.ZERO;
        Vec3 min = capVertical(envelopeMin, yCap);
        Vec3 max = capVertical(envelopeMax, yCap);
        double enter = 0.0;
        double exit = Double.POSITIVE_INFINITY;
        for (int axis = 0; axis < 3; axis++) {
            double direction = component(motion, axis);
            double lo = component(min, axis);
            double hi = component(max, axis);
            if (direction == 0.0) {
                if (lo > 0.0 || hi < 0.0) {
                    exit = -1.0;
                    break;
                }
            } else {
                double first = lo / direction;
                double second = hi / direction;
                enter = Math.max(enter, Math.min(first, second));
                exit = Math.min(exit, Math.max(first, second));
            }
        }
        Vec3 projected = motion;
        if (enter <= exit && Double.isFinite(exit)) {
            // An intercept may intentionally request a short vector. Keep it when the
            // box permits; if momentum requires more speed, stay on the nearest edge.
            Vec3 scaled = motion.scale(precision ? Math.clamp(1.0, enter, exit) : exit);
            if (isFinite(scaled)) projected = scaled;
        }
        projected = clampComponents(projected, min, max);
        return cornerClimb && !precision ? cornerFill(projected, motion, min, max, yCap) : projected;
    }

    private static double pick(double d, double lo, double hi, double keep) {
        keep = Mth.clamp(keep, lo, hi);
        if (d > 0.0) {
            return Math.max(keep, Math.max(lo, hi - CORNER_EPS));
        }
        if (d < 0.0) {
            return Math.min(keep, Math.min(hi, lo + CORNER_EPS));
        }
        return keep;
    }

    private static Vec3 clampComponents(Vec3 value, Vec3 min, Vec3 max) {
        return new Vec3(Mth.clamp(value.x, min.x, max.x),
                Mth.clamp(value.y, min.y, max.y), Mth.clamp(value.z, min.z, max.z));
    }

    private static Vec3 capVertical(Vec3 value, double yCap) {
        return yCap > 0.0 && Double.isFinite(yCap)
                ? new Vec3(value.x, Mth.clamp(value.y, -yCap, yCap), value.z) : value;
    }

    private static boolean validBounds(Vec3 min, Vec3 max) {
        return isFinite(min) && isFinite(max) && min.x <= max.x && min.y <= max.y && min.z <= max.z;
    }

    private static boolean isFinite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private static Vec3 finiteOrZero(Vec3 value) {
        return isFinite(value) ? value : Vec3.ZERO;
    }

    private static double component(Vec3 value, int axis) {
        return switch (axis) {
            case 0 -> value.x;
            case 1 -> value.y;
            default -> value.z;
        };
    }

    private static double square(double v) {
        return v * v;
    }
}
