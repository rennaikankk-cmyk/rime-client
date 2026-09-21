package dev.guizhou.util.elytra;

/** Small, observation-based combat decisions without game or world dependencies. */
public final class AerialCombatPolicy {
    private static final long EVADE_CLEAR_TICKS = 4;
    private static final double MAX_OBSERVED_SPEED = 20.0;
    private static final double MAX_LEAD_TICKS = 4.0;
    private static final double MAX_LEAD_DISTANCE = 12.0;

    public enum Action { CHASE, EVADE, RETREAT }

    public record Vector(double x, double y, double z) {
        public static final Vector ZERO = new Vector(0.0, 0.0, 0.0);

        public boolean isFinite() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
        }

        public double length() {
            return Math.hypot(Math.hypot(x, y), z);
        }

        public Vector add(Vector other) {
            return new Vector(x + other.x, y + other.y, z + other.z);
        }

        public Vector subtract(Vector other) {
            return new Vector(x - other.x, y - other.y, z - other.z);
        }

        public Vector scale(double amount) {
            return new Vector(x * amount, y * amount, z * amount);
        }

        public double dot(Vector other) {
            return x * other.x + y * other.y + z * other.z;
        }
    }

    /** Offset is target minus self; velocities are blocks per tick; health uses half-heart units. */
    public record ThreatInput(long tick, Vector offset, Vector selfVelocity, Vector targetVelocity,
                              boolean spearThreat, boolean maceThreat, double health, double absorption,
                              double maxHealth, boolean hurt, int latencyTicks, boolean highThreat) {
    }

    private Action action = Action.CHASE;
    private long lastTick = Long.MIN_VALUE;
    private long lastHurtTick = Long.MIN_VALUE;
    private long lastThreatTick = Long.MIN_VALUE;
    private double lastEffectiveHealth = Double.NaN;

    public Action evaluate(ThreatInput input) {
        return evaluate(input, false, false);
    }

    public Action evaluate(ThreatInput input, boolean predictedThreat, boolean preserveStrike) {
        if (input == null) {
            reset();
            return action;
        }
        if (lastTick != Long.MIN_VALUE && input.tick < lastTick) reset();
        lastTick = input.tick;

        boolean validHealth = validHealth(input);
        double effectiveHealth = validHealth ? Math.max(0.0, input.health) + Math.max(0.0, input.absorption)
                : Double.NaN;
        if (input.hurt || validHealth && Double.isFinite(lastEffectiveHealth)
                && effectiveHealth < lastEffectiveHealth - 0.01) {
            lastHurtTick = input.tick;
        }
        if (validHealth) lastEffectiveHealth = effectiveHealth;

        if (preserveStrike) {
            action = Action.CHASE;
            lastThreatTick = Long.MIN_VALUE;
            return action;
        }
        if (predictedThreat || input.spearThreat || input.maceThreat || incomingHighSpeed(input)) {
            lastThreatTick = input.tick;
            action = Action.EVADE;
        } else if (action != Action.EVADE || elapsed(input.tick, lastThreatTick) >= EVADE_CLEAR_TICKS) {
            action = Action.CHASE;
        }
        return action;
    }

    public void reset() {
        action = Action.CHASE;
        lastTick = Long.MIN_VALUE;
        lastHurtTick = Long.MIN_VALUE;
        lastThreatTick = Long.MIN_VALUE;
        lastEffectiveHealth = Double.NaN;
    }

    /** A fast target matters only if relative motion predicts a close encounter soon. */
    public static boolean incomingHighSpeed(ThreatInput input) {
        if (input == null || !finite(input.offset) || !validVelocity(input.selfVelocity)
                || !validVelocity(input.targetVelocity)
                || input.targetVelocity.length() < (input.highThreat ? 0.3 : 0.75)) return false;
        double distance = input.offset.length();
        if (!Double.isFinite(distance) || distance < 1.0E-6) return false;
        Vector towardTarget = input.offset.scale(1.0 / distance);
        double targetClosing = -towardTarget.dot(input.targetVelocity);
        if (targetClosing < (input.highThreat ? 0.2 : 0.35)) return false;
        Vector relative = input.targetVelocity.subtract(input.selfVelocity);
        double relativeSpeed = relative.length();
        if (relativeSpeed < 1.0E-6) return false;
        double closing = -towardTarget.dot(relative);
        if (closing < (input.highThreat ? 0.35 : 0.6)) return false;
        double horizon = (input.highThreat ? 6.0 : 4.0) + Math.clamp(input.latencyTicks, 0, 3);
        double closestTick = distance * closing / (relativeSpeed * relativeSpeed);
        double boundedTick = Math.min(closestTick, horizon);
        if (!Double.isFinite(boundedTick) || boundedTick <= 0.0) return false;
        return input.offset.add(relative.scale(boundedTick)).length() <= (input.highThreat ? 2.75 : 2.0);
    }

    /** Distances are linear blocks, not squared; invalid targets may be replaced immediately. */
    public static boolean shouldSwitchTarget(boolean currentValid, double currentDistance,
                                             double candidateDistance, long heldTicks) {
        if (!Double.isFinite(candidateDistance) || candidateDistance < 0.0) return false;
        if (!currentValid || !Double.isFinite(currentDistance) || currentDistance < 0.0) return true;
        if (heldTicks < 10) return false;
        double improvement = Math.max(2.0, currentDistance * 0.2);
        return candidateDistance <= currentDistance - improvement;
    }

    /** Search immediately on target loss, otherwise bound empty/dynamic scans to once per four ticks. */
    public static boolean shouldSearch(long tick, long lastSearch, boolean lostTarget,
                                       boolean hasTarget, boolean dynamic) {
        if (lostTarget) return true;
        if (hasTarget && !dynamic) return false;
        return tick < lastSearch || lastSearch <= Long.MIN_VALUE / 2 || tick - lastSearch >= 4;
    }

    /** Two nonstationary measurements within 60 degrees support longer prediction. */
    public static boolean coherent(Vector current, Vector previous) {
        if (!validVelocity(current) || !validVelocity(previous)) return false;
        double currentLength = current.length();
        double previousLength = previous.length();
        if (currentLength < 1.0E-6 || previousLength < 1.0E-6) return false;
        return current.scale(1.0 / currentLength).dot(previous.scale(1.0 / previousLength)) >= 0.5;
    }

    public static Vector predictionVelocity(Vector current, Vector previous, boolean hasPrevious) {
        if (!validVelocity(current)) return Vector.ZERO;
        if (!hasPrevious || !coherent(current, previous)) return current;
        return current.scale(0.65).add(previous.scale(0.35));
    }

    public static double leadTicks(double distance, double selfSpeed, int latencyTicks, boolean consistent) {
        if (!Double.isFinite(distance) || distance < 0.0 || !Double.isFinite(selfSpeed) || selfSpeed < 0.0) {
            return 0.0;
        }
        double horizon = Math.clamp(latencyTicks, 0, 3) + distance / Math.max(0.5, selfSpeed);
        return Math.min(consistent ? MAX_LEAD_TICKS : 1.0, horizon);
    }

    /** Bounds spatial extrapolation too, so one fast sample cannot lead dozens of blocks away. */
    public static Vector leadOffset(Vector velocity, double ticks) {
        if (!validVelocity(velocity) || !Double.isFinite(ticks) || ticks <= 0.0) return Vector.ZERO;
        Vector offset = velocity.scale(Math.min(ticks, MAX_LEAD_TICKS));
        double length = offset.length();
        return length > MAX_LEAD_DISTANCE ? offset.scale(MAX_LEAD_DISTANCE / length) : offset;
    }

    private static boolean validHealth(ThreatInput input) {
        return Double.isFinite(input.health) && Double.isFinite(input.absorption)
                && Double.isFinite(input.maxHealth) && input.maxHealth > 0.0
                && Double.isFinite(Math.max(0.0, input.health) + Math.max(0.0, input.absorption));
    }

    private static boolean validVelocity(Vector velocity) {
        return finite(velocity) && velocity.length() <= MAX_OBSERVED_SPEED;
    }

    private static boolean finite(Vector vector) {
        return vector != null && vector.isFinite();
    }

    private static long elapsed(long now, long then) {
        if (then == Long.MIN_VALUE) return Long.MAX_VALUE;
        if (now < then) return 0;
        long difference = now - then;
        return difference < 0 ? Long.MAX_VALUE : difference;
    }
}
