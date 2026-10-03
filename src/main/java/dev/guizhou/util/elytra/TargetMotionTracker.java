package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;

/** Observation-only model; equipment never identifies a target's client or flight implementation. */
public final class TargetMotionTracker {
    private static final int MAX_SAMPLE_GAP = 5;
    private static final double MAX_SAMPLE_SPEED = 20.0;

    public enum Equipment { ELYTRA, ARMOR, OTHER }
    public enum Mode {
        UNKNOWN("UNKNOWN"), GROUND("GROUND"), GLIDE("GLIDE"), GLIDE_ACCELERATING("GLIDE ACCEL"),
        ARMORED_AIR("ARMOR AIR"), AIRBORNE("AIRBORNE");
        private final String label;
        Mode(String label) { this.label = label; }
        public String label() { return label; }
    }

    /** Numeric immutable input safe to use in the background planner. */
    public record Snapshot(Mode mode, long revision, Vec3 velocity, Vec3 acceleration,
                           double turnRate, double confidence, double maxLeadTicks) {
        public Snapshot {
            mode = mode == null ? Mode.UNKNOWN : mode;
            velocity = isFinite(velocity) ? bounded(velocity, MAX_SAMPLE_SPEED) : Vec3.ZERO;
            acceleration = isFinite(acceleration) ? bounded(acceleration, 0.6) : Vec3.ZERO;
            turnRate = Double.isFinite(turnRate) ? Math.clamp(turnRate, -Math.PI / 6, Math.PI / 6) : 0;
            confidence = Double.isFinite(confidence) ? Math.clamp(confidence, 0, 1) : 0;
            maxLeadTicks = Double.isFinite(maxLeadTicks) ? Math.clamp(maxLeadTicks, 0, 4) : 0;
        }
        public Vec3 displacement(double ticks) {
            return forecast(ticks, false);
        }
        /** Keep threat lookahead independent of aim confidence; stop extrapolating derivatives after their horizon. */
        public Vec3 threatDisplacement(double ticks) {
            return forecast(ticks, true);
        }
        private Vec3 forecast(double ticks, boolean continueDrift) {
            if (!Double.isFinite(ticks) || ticks <= 0) return Vec3.ZERO;
            double horizon = Math.min(ticks, maxLeadTicks);
            Vec3 motion = velocity, offset = Vec3.ZERO;
            double speedAcceleration = velocity.horizontalDistance() < 1.0E-6 ? 0
                    : (acceleration.x * velocity.x + acceleration.z * velocity.z) / velocity.horizontalDistance();
            for (double elapsed = 0; elapsed < horizon; ) {
                double dt = Math.min(0.5, horizon - elapsed);
                double angle = turnRate * dt, cos = Math.cos(angle), sin = Math.sin(angle);
                double horizontal = motion.horizontalDistance();
                double nextSpeed = Math.max(0, horizontal + speedAcceleration * dt);
                double scale = horizontal < 1.0E-6 ? 0 : nextSpeed / horizontal;
                Vec3 next = bounded(new Vec3((motion.x * cos - motion.z * sin) * scale,
                        motion.y + acceleration.y * dt, (motion.x * sin + motion.z * cos) * scale), MAX_SAMPLE_SPEED);
                offset = offset.add(motion.add(next).scale(0.5 * dt));
                motion = next;
                elapsed += dt;
            }
            if (continueDrift) offset = offset.add(motion.scale(Math.max(0, Math.min(ticks, 10) - horizon)));
            return bounded(offset, continueDrift ? MAX_SAMPLE_SPEED * 10 : 12.0);
        }
    }

    private Vec3 position;
    private Vec3 velocity = Vec3.ZERO;
    private Vec3 previousVelocity = Vec3.ZERO;
    private Vec3 acceleration = Vec3.ZERO;
    private Vec3 previousAcceleration = Vec3.ZERO;
    private double turnRate, previousTurnRate;
    private int tick;
    private int sampleTicks = 1;
    private int velocityCount;
    private boolean grounded;
    private boolean gliding;
    private Equipment equipment = Equipment.OTHER;
    private int equipmentId;
    private Mode mode = Mode.UNKNOWN, candidate = Mode.UNKNOWN;
    private int candidateSamples, unstableTicks;
    private int modeSampleTick = Integer.MIN_VALUE;
    private boolean suppressFallback;
    private long revision;

    public void clear() {
        resetMeasurements();
        position = null;
        mode = candidate = Mode.UNKNOWN;
        candidateSamples = 0;
        modeSampleTick = Integer.MIN_VALUE;
        suppressFallback = true;
        revision++;
    }

    private void resetMeasurements() {
        velocity = previousVelocity = acceleration = previousAcceleration = Vec3.ZERO;
        turnRate = previousTurnRate = 0;
        velocityCount = 0;
        unstableTicks = 2;
    }

    public void sample(Vec3 nextPosition, int nextTick, boolean onGround) {
        sample(nextPosition, nextTick, onGround, false, Equipment.OTHER, 0);
    }

    public void sample(Vec3 nextPosition, int nextTick, boolean onGround, boolean isGliding,
                       Equipment observedEquipment, int observedEquipmentId) {
        sample(nextPosition, nextTick, onGround, isGliding, observedEquipment, observedEquipmentId, false);
    }

    /** Packet endpoints can arrive in bursts; never classify a single burst as rocket acceleration. */
    public void sampleServer(Vec3 nextPosition, int nextTick, boolean onGround, boolean isGliding,
                             Equipment observedEquipment, int observedEquipmentId) {
        sample(nextPosition, nextTick, onGround, isGliding, observedEquipment, observedEquipmentId, true);
    }

    private void sample(Vec3 nextPosition, int nextTick, boolean onGround, boolean isGliding,
                        Equipment observedEquipment, int observedEquipmentId, boolean serverSample) {
        if (!isFinite(nextPosition) || observedEquipment == null) { clear(); return; }
        long elapsed = (long) nextTick - tick;
        boolean flagsChanged = position != null && (grounded != onGround || gliding != isGliding
                || equipment != observedEquipment || equipmentId != observedEquipmentId);
        if (position != null && elapsed == 0 && !flagsChanged) return;
        if (position != null && elapsed == 0) {
            if (grounded != onGround) { resetMeasurements(); suppressFallback = true; }
            // Equipment packets can arrive between movement samples; do not invent a zero-duration velocity.
            grounded = onGround;
            gliding = isGliding;
            equipment = observedEquipment;
            equipmentId = observedEquipmentId;
            unstableTicks = Math.max(unstableTicks, 1);
            revision++;
            updateMode();
            return;
        }
        boolean reset = position == null || elapsed < 0 || elapsed > MAX_SAMPLE_GAP || grounded != onGround;
        if (!reset) {
            Vec3 nextVelocity = nextPosition.subtract(position).scale(1.0 / elapsed);
            Vec3 residual = nextPosition.subtract(position.add(velocity.scale(elapsed)));
            boolean discontinuity = velocityCount > 0 && residual.length() > Math.max(4.0, velocity.length() * elapsed * 2.5);
            if (!isFinite(nextVelocity) || nextVelocity.lengthSqr() > MAX_SAMPLE_SPEED * MAX_SAMPLE_SPEED || discontinuity) {
                reset = true;
                mode = candidate = Mode.UNKNOWN;
                candidateSamples = 0;
            } else {
                previousVelocity = velocity;
                // Same-direction packet bursts need smoothing; a real reversal must
                // invalidate the old plan immediately, including a vertical turn.
                boolean sharpTurn = serverSample && velocityCount > 0 && previousVelocity.lengthSqr() > .0025
                        && nextVelocity.lengthSqr() > .0025
                        && previousVelocity.dot(nextVelocity) <= .5 * previousVelocity.length() * nextVelocity.length();
                velocity = serverSample && velocityCount > 0 && !sharpTurn
                        ? previousVelocity.scale(.65).add(nextVelocity.scale(.35)) : nextVelocity;
                sampleTicks = (int) elapsed;
                previousAcceleration = acceleration;
                acceleration = velocityCount > 0 ? velocity.subtract(previousVelocity).scale(1.0 / elapsed) : Vec3.ZERO;
                previousTurnRate = turnRate;
                double turn = previousVelocity.horizontalDistance() > 0.05 && velocity.horizontalDistance() > 0.05
                        ? Math.atan2(previousVelocity.x * velocity.z - previousVelocity.z * velocity.x,
                        previousVelocity.x * velocity.x + previousVelocity.z * velocity.z) : 0;
                turnRate = turn / elapsed;
                velocityCount = Math.min(4, velocityCount + 1);
                suppressFallback = false;
                if (unstableTicks > 0) unstableTicks--;
                if (sharpTurn || Math.abs(turn) > Math.PI / 3 || acceleration.lengthSqr() > 2.25) {
                    unstableTicks = 2;
                    revision++;
                }
            }
        }
        if (reset) {
            boolean hadPosition = position != null;
            resetMeasurements();
            if (hadPosition) suppressFallback = true;
            if (hadPosition && !flagsChanged) {
                mode = candidate = Mode.UNKNOWN;
                candidateSamples = 0;
            }
            revision++;
        } else if (flagsChanged) {
            unstableTicks = Math.max(unstableTicks, 1);
            revision++;
        }
        position = nextPosition;
        tick = nextTick;
        grounded = onGround;
        gliding = isGliding;
        equipment = observedEquipment;
        equipmentId = observedEquipmentId;
        updateMode();
    }

    private void updateMode() {
        boolean distinctTick = modeSampleTick != tick;
        modeSampleTick = tick;
        Mode observed = grounded ? Mode.GROUND : equipment == Equipment.ARMOR ? Mode.ARMORED_AIR
                : gliding ? accelerating() ? Mode.GLIDE_ACCELERATING : Mode.GLIDE : Mode.AIRBORNE;
        if (grounded) {
            if (mode != Mode.GROUND) revision++;
            mode = candidate = Mode.GROUND;
            candidateSamples = 0;
            return;
        }
        if (candidate != observed) { candidate = observed; candidateSamples = distinctTick ? 1 : 0; }
        else if (distinctTick) candidateSamples++;
        if (candidateSamples >= 2 && mode != observed) { mode = observed; revision++; }
    }

    private boolean accelerating() {
        return velocityCount >= 2 && velocity.length() > previousVelocity.length() + 0.04
                && velocity.dot(previousVelocity) > 0;
    }

    public boolean hasTwoVelocities() {
        return velocityCount >= 2;
    }

    public Vec3 previousVelocity() {
        return previousVelocity;
    }

    public Vec3 velocity() {
        return velocity;
    }
    public long revision() { return revision; }
    public Mode mode() { return mode; }

    public Vec3 predictionVelocity(Vec3 fallback) {
        if (velocityCount > 0) return velocity;
        if (suppressFallback || !isFinite(fallback) || fallback.lengthSqr() > MAX_SAMPLE_SPEED * MAX_SAMPLE_SPEED) return Vec3.ZERO;
        return fallback;
    }

    public Snapshot snapshot(Vec3 fallback) {
        Vec3 measured = predictionVelocity(fallback);
        double confidence = velocityCount == 0 ? 0 : Math.min(1, velocityCount / 3.0);
        boolean stable = velocityCount >= 3 && unstableTicks == 0;
        if (unstableTicks > 0) confidence = Math.min(confidence, 0.3);
        boolean derivativesStable = acceleration.distanceToSqr(previousAcceleration) <= 0.16
                && Math.abs(turnRate - previousTurnRate) < 0.2;
        if (velocityCount >= 3 && !derivativesStable) confidence = Math.min(confidence, 0.4);
        Vec3 estimateAcceleration = Vec3.ZERO;
        double estimateTurn = 0;
        if (stable) {
            // Only use derivatives corroborated by another observation; reversals get a short horizon.
            if (acceleration.distanceToSqr(previousAcceleration) <= 0.16) {
                double horizontal = measured.horizontalDistance();
                double tangent = (horizontal - previousVelocity.horizontalDistance()) / sampleTicks;
                tangent = Math.clamp(tangent, -0.5, 0.5);
                double scale = horizontal < 1.0E-6 ? 0 : tangent / horizontal;
                estimateAcceleration = new Vec3(measured.x * scale, Math.clamp(acceleration.y, -0.2, 0.2), measured.z * scale);
            }
            if (Math.signum(turnRate) == Math.signum(previousTurnRate) && Math.abs(turnRate - previousTurnRate) < 0.2)
                estimateTurn = Math.clamp((turnRate + previousTurnRate) * 0.5, -Math.PI / 6, Math.PI / 6);
        }
        double maximum = velocityCount == 0 ? 0 : unstableTicks > 0 || candidate != mode || !derivativesStable ? 1
                : switch (mode) {
                    case GLIDE -> 4;
                    case GLIDE_ACCELERATING -> 3;
                    case GROUND -> 2;
                    case ARMORED_AIR, AIRBORNE -> 2.5;
                    case UNKNOWN -> 1;
                };
        return new Snapshot(mode, revision, measured, estimateAcceleration, estimateTurn, confidence, maximum);
    }

    private static Vec3 bounded(Vec3 vector, double maximum) {
        double length = vector.length();
        return length > maximum ? vector.scale(maximum / length) : vector;
    }

    private static boolean isFinite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z)
                && Double.isFinite(value.lengthSqr());
    }
}
