package dev.guizhou.util.elytra;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Elytra flight math ported from rennaikanksmp (LegalMovementManager family):
 *
 * - simulateGlide: vanilla elytra travel() simulation (the Grim prediction base)
 * - clampToGrimEnvelope: auto-rescale V2 — uniformly shrinks a requested motion
 *   into the acceptance box u = glide-sim(last velocity) + firework expansion
 * - cornerFill: the yclimb corner law — after the uniform shrink, push each
 *   axis with a desired component to its box edge (minus 0.01 safety EPS) so
 *   Y ratchets up with the simulation instead of hugging the request direction
 * - bestPullupSpeed / bestDownForwardSpeed: the -54.5° / -30.5° glide
 *   equilibrium angles that maximise sustained climb / shallow descent
 */
public final class ElytraPhysics {

    private ElytraPhysics() {
    }

    public static final double DEFAULT_GRAVITY = 0.08;
    private static final float DEG_TO_RAD = 0.017453292F;
    private static final double CORNER_EPS = 0.01;

    public static Vec3 pitchYawToRotation(float pitchDeg, float yawDeg) {
        float f = pitchDeg * DEG_TO_RAD;
        float g = yawDeg * DEG_TO_RAD;
        float cosP = Mth.cos(f);
        float sinP = Mth.sin(f);
        float cosY = Mth.cos(g);
        float sinY = Mth.sin(g);
        return new Vec3(sinY * cosP, -sinP, cosY * cosP);
    }

    /** pitch (positive down) of a (ideally normalized) look vector, degrees */
    public static float rotationToPitch(Vec3 look) {
        Vec3 n = look.lengthSqr() > 1.0E-6 && Math.abs(look.length() - 1.0) > 1.0E-4
                ? look.normalize()
                : look;
        return (float) Math.toDegrees(-Math.asin(Mth.clamp(n.y, -1.0, 1.0)));
    }

    /** yaw (MC convention) of a look vector with a horizontal component, degrees */
    public static float rotationToYaw(Vec3 look) {
        return (float) Math.toDegrees(Math.atan2(-look.x, look.z));
    }

    /** vanilla 1.21 elytra travel() simulation: one tick of glide from oldVelocity */
    public static Vec3 simulateGlide(Vec3 oldVelocity, Vec3 look, double gravity) {
        float pitch = rotationToPitch(look);
        float pitchRad = pitch * DEG_TO_RAD;
        double lookHorizLen = Math.sqrt(look.x * look.x + look.z * look.z);
        double initialHorizSpeed = oldVelocity.horizontalDistance();
        double cosPitchSq = square(Math.cos(pitchRad));

        // gravity term: steeper dive (pitch toward -90 up... cosine of pitch) — keep
        // the rennaikanksmp form exactly, it matches vanilla
        double newY = oldVelocity.y + gravity * (cosPitchSq * 0.75 - 1.0);
        Vec3 vel = new Vec3(oldVelocity.x, newY, oldVelocity.z);

        // lift while descending with a look direction
        if (newY < 0.0 && lookHorizLen > 0.0) {
            double lift = newY * -0.1 * cosPitchSq;
            vel = vel.add(look.x * lift / lookHorizLen, lift, look.z * lift / lookHorizLen);
        }

        // dive acceleration while looking down
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
        return vel.multiply(0.99, 0.98, 0.99);
    }

    /** WASD-style (strafe, up, forward) input turned into a world-space vector */
    public static Vec3 movementInputToVelocity(Vec3 movementInput, float speed, float yawDeg) {
        double d = movementInput.lengthSqr();
        if (d < 1.0E-7) {
            return Vec3.ZERO;
        }
        Vec3 scaled = (d > 1.0 ? movementInput.normalize() : movementInput).scale(speed);
        float sin = Mth.sin(yawDeg * DEG_TO_RAD);
        float cos = Mth.cos(yawDeg * DEG_TO_RAD);
        return new Vec3(scaled.x * cos - scaled.z * sin, scaled.y, scaled.z * cos + scaled.x * sin);
    }

    /** steep-glide climb equilibrium (~54.5°): max sustained Y gain for this horizontal speed */
    public static Vec3 bestPullupSpeed(Vec3 motion) {
        double horizontal = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        if (horizontal < 1.0E-6) {
            motion = new Vec3(5.0, motion.y, motion.z);
            horizontal = 5.0;
        }
        return new Vec3(motion.x, horizontal * Math.tan(Math.toRadians(54.5)), motion.z);
    }

    /** shallow-dive equilibrium (~30.5°): keeps altitude loss minimal while diving */
    public static Vec3 bestDownForwardSpeed(Vec3 motion) {
        double horizontal = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        if (horizontal < 1.0E-6) {
            return motion;
        }
        return new Vec3(motion.x, -horizontal * Math.tan(Math.toRadians(30.5)), motion.z);
    }

    /**
     * auto-rescale V2: shrink {@code motion} into the Grim acceptance box
     * u = glideSimulation(lastVelocity) + fireworkExpansionBox, keeping its
     * direction (uniform scale). Returns the original vector when it already
     * fits or when shrinking would move it outside the next-tick glide
     * simulation (i.e. it is reachable by real elytra physics anyway).
     *
     * @param lastVel     velocity the server last saw (previous tick end)
     * @param lastSimVel  glide simulation of lastVel toward the last look
     * @param lookNow     current normalized look vector
     * @param lookLast    previous tick normalized look vector
     */
    public static Vec3 clampToGrimEnvelope(Vec3 motion, Vec3 lastVel, Vec3 lastSimVel,
            Vec3 lookNow, Vec3 lookLast, double rescaleAmount) {
        return clampToGrimEnvelope(motion, lastVel, lastSimVel, lookNow, lookLast, rescaleAmount, false, 0.0);
    }

    public static Vec3 clampToGrimEnvelope(Vec3 motion, Vec3 lastVel, Vec3 lastSimVel,
            Vec3 lookNow, Vec3 lookLast, double rescaleAmount, boolean cornerClimb, double yCap) {
        if (motion.lengthSqr() < 1.0E-6) {
            return motion;
        }

        // firework expansion box around both recent look directions: a boosted
        // player could have steered anywhere inside this span
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

        // u = simulation + the reach a firework boost could have added this tick
        double uMinX = lastSimVel.x + Math.min(0, minX - lastVel.x);
        double uMaxX = lastSimVel.x + Math.max(0, maxX - lastVel.x);
        double uMinY = lastSimVel.y + Math.min(0, minY - lastVel.y);
        double uMaxY = lastSimVel.y + Math.max(0, maxY - lastVel.y);
        double uMinZ = lastSimVel.z + Math.min(0, minZ - lastVel.z);
        double uMaxZ = lastSimVel.z + Math.max(0, maxZ - lastVel.z);

        double dx = motion.x, dy = motion.y, dz = motion.z;
        double exceedX = 0.0, exceedY = 0.0, exceedZ = 0.0;
        if (dx > 0) exceedX = dx / uMaxX;
        else if (dx < 0) exceedX = dx / uMinX;
        if (dy > 0) exceedY = dy / uMaxY;
        else if (dy < 0) exceedY = dy / uMinY;
        if (dz > 0) exceedZ = dz / uMaxZ;
        else if (dz < 0) exceedZ = dz / uMinZ;

        double maxScale = Math.max(exceedX, Math.max(exceedY, exceedZ));
        if (maxScale < 1.0E-6) {
            return motion;
        }

        // if the glide simulation of the requested motion already outruns it,
        // real elytra physics can produce this speed — no clamp needed
        Vec3 predicted = simulateGlide(motion, lookNow, DEFAULT_GRAVITY);
        if (predicted.lengthSqr() < 1.0E-9) {
            return motion;
        }
        double limitScale = motion.length() / predicted.length();
        if (maxScale >= limitScale) {
            return motion;
        }

        Vec3 clamped = motion.scale(1.0 / maxScale);
        if (cornerClimb) {
            Vec3 envelopeMin = new Vec3(uMinX, uMinY, uMinZ);
            Vec3 envelopeMax = new Vec3(uMaxX, uMaxY, uMaxZ);
            clamped = cornerFill(clamped, motion, envelopeMin, envelopeMax, yCap);
        }
        return clamped;
    }

    /**
     * yclimb corner law: after the uniform shrink, move each axis that has a
     * desired component out to its acceptance-box edge (EPS inside). Y is
     * additionally capped by {@code yCap} (blocks/tick; 8 = 160 m/s). Uniform
     * scaling only lands on the request-direction surface point; corner
     * filling lets Y ratchet up with the simulation every tick.
     */
    public static Vec3 cornerFill(Vec3 clamped, Vec3 desired, Vec3 envelopeMin, Vec3 envelopeMax, double yCap) {
        double cap = (yCap > 0.0 && Double.isFinite(yCap)) ? yCap : Double.MAX_VALUE;
        return new Vec3(
                pick(desired.x, envelopeMin.x, envelopeMax.x, clamped.x, Double.MAX_VALUE),
                pick(desired.y, envelopeMin.y, envelopeMax.y, clamped.y, cap),
                pick(desired.z, envelopeMin.z, envelopeMax.z, clamped.z, Double.MAX_VALUE));
    }

    private static double pick(double d, double lo, double hi, double keep, double cap) {
        if (d > 0.0) {
            return Math.max(keep, Math.min(hi - CORNER_EPS, cap));
        }
        if (d < 0.0) {
            return Math.min(keep, Math.max(lo + CORNER_EPS, -cap));
        }
        return keep;
    }

    private static double square(double v) {
        return v * v;
    }
}
