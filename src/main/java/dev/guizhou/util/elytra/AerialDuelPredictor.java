package dev.guizhou.util.elytra;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Stateless estimates from immutable numeric snapshots; never reads entities, input, or a live world. */
public final class AerialDuelPredictor {
    public static final int MAX_PROJECTILES = 16;
    private static final double MAX_SPEED = 32.0;
    private static final double MAX_OFFSET = 12.0;

    public enum Weapon { NONE, MELEE, SPEAR, MACE }
    public enum ThreatKind { NONE, MELEE, SPEAR, MACE, PROJECTILE }

    /** Velocity is blocks/tick. After movement, velocity becomes velocity*drag + (0,-gravity,0). */
    public record Projectile(Vec3 position, Vec3 velocity, double gravity, double drag, double radius) {
    }

    /** Positions are world coordinates; body dimensions describe the current self pose. */
    public record Input(Vec3 selfPosition, Vec3 selfVelocity, double selfWidth, double selfHeight,
                        Vec3 targetPosition, Vec3 targetVelocity, Vec3 targetEye, Vec3 targetLook,
                        double targetReach, Weapon targetWeapon, boolean targetOnGround, boolean targetGliding,
                        boolean targetAttackActive, int latencyTicks, boolean velocityConsistent,
                        List<Projectile> projectiles, FlightCollisionSnapshot snapshot) {
        public Input {
            projectiles = projectiles == null ? List.of()
                    : projectiles.stream().filter(projectile -> projectile != null).limit(MAX_PROJECTILES).toList();
        }
    }

    /** Severity is an urgency score in [0,1], not a hit probability. No threat has infinite impactTicks. */
    public record Output(double leadTicks, Vec3 interceptOffset, ThreatKind threatKind, double severity,
                         double impactTicks, Vec3 leftDirection, Vec3 rightDirection, Vec3 recommendedDirection,
                         boolean leftClear, boolean rightClear) {
    }

    private record Threat(ThreatKind kind, double severity, double tick, Vec3 point, Vec3 relativeVelocity) {
    }

    private AerialDuelPredictor() {
    }

    public static Output predict(Input input) {
        if (!valid(input)) return empty();
        Vec3 offset = input.targetPosition.subtract(input.selfPosition);
        double lead = AerialIntercept.leadTicks(offset, input.targetVelocity, input.selfVelocity.length(),
                Math.clamp(input.latencyTicks, 0, 3), input.velocityConsistent);
        Vec3 intercept = bounded(input.targetVelocity.scale(lead), MAX_OFFSET);
        AABB body = body(input);
        int horizon = 6 + Math.clamp(input.latencyTicks, 0, 4);
        Threat threat = meleeThreat(input, body, horizon);
        threat = stronger(threat, maceThreat(input, body, horizon));
        for (Projectile projectile : input.projectiles) {
            threat = stronger(threat, projectileThreat(input, body, projectile, horizon));
        }

        Vec3 axis = threat == null ? offset : threat.relativeVelocity;
        Vec3 horizontal = new Vec3(axis.x, 0.0, axis.z);
        if (horizontal.lengthSqr() < 1.0E-9) horizontal = new Vec3(offset.x, 0.0, offset.z);
        if (horizontal.lengthSqr() < 1.0E-9) horizontal = new Vec3(1.0, 0.0, 0.0);
        horizontal = horizontal.normalize();
        Vec3 left = new Vec3(-horizontal.z, 0.0, horizontal.x);
        Vec3 right = left.scale(-1.0);
        double probe = Math.clamp(input.selfVelocity.length() * 2.0, 1.5, 4.0);
        Vec3 leftEnd = input.selfPosition.add(left.scale(probe));
        Vec3 rightEnd = input.selfPosition.add(right.scale(probe));
        // Without a snapshot these are candidates only; the client thread must validate actual movement.
        boolean leftClear = input.snapshot == null || input.snapshot.clear(input.selfPosition, leftEnd);
        boolean rightClear = input.snapshot == null || input.snapshot.clear(input.selfPosition, rightEnd);
        Vec3 recommended = Vec3.ZERO;
        if (threat != null) {
            if (leftClear && rightClear) {
                recommended = leftEnd.distanceToSqr(threat.point) >= rightEnd.distanceToSqr(threat.point) ? left : right;
            } else if (leftClear) recommended = left;
            else if (rightClear) recommended = right;
        }
        return new Output(lead, intercept, threat == null ? ThreatKind.NONE : threat.kind,
                threat == null ? 0.0 : threat.severity, threat == null ? Double.POSITIVE_INFINITY : threat.tick,
                left, right, recommended, leftClear, rightClear);
    }

    /** Keep a real ready smash against ordinary pressure; only imminent heavy attacks or low health override it. */
    public static boolean shouldInterruptAttack(Output output, boolean realMaceHitWindow,
                                                double health, double absorption, double maxHealth) {
        if (Double.isFinite(health) && Double.isFinite(absorption) && Double.isFinite(maxHealth)
                && maxHealth > 0.0 && Math.max(0.0, health) + Math.max(0.0, absorption) <= maxHealth * 0.4) return true;
        if (output == null || output.threatKind == null || output.threatKind == ThreatKind.NONE
                || !Double.isFinite(output.severity) || output.severity < 0.35
                || !Double.isFinite(output.impactTicks) || output.impactTicks < 0.0) return false;
        if (!realMaceHitWindow) return true;
        return (output.threatKind == ThreatKind.MACE || output.threatKind == ThreatKind.SPEAR)
                && output.severity >= 0.65 && output.impactTicks <= 3.0;
    }

    private static Threat meleeThreat(Input input, AABB body, int horizon) {
        if (!input.targetAttackActive || input.targetReach <= 0.0
                || input.targetWeapon != Weapon.MELEE && input.targetWeapon != Weapon.SPEAR
                || input.targetLook.lengthSqr() < 1.0E-9) return null;
        Vec3 separation = input.targetPosition.subtract(input.selfPosition);
        Vec3 relative = input.targetVelocity.subtract(input.selfVelocity);
        double closing = separation.lengthSqr() < 1.0E-9 ? relative.length()
                : -relative.dot(separation.normalize());
        boolean spear = input.targetWeapon == Weapon.SPEAR;
        if (spear && closing <= 0.15) return null;
        if (!spear && closing <= 0.05 && body.distanceToSqr(input.targetEye) > square(input.targetReach)) return null;
        Vec3 attackRay = input.targetLook.normalize().scale(input.targetReach);
        for (double tick = 0.0; tick <= Math.min(spear ? horizon : 4, horizon); tick += 0.5) {
            Vec3 eye = input.targetEye.add(relative.scale(tick));
            if (segmentHit(body.inflate(0.1), eye, eye.add(attackRay)) >= 0.0) {
                double severity = spear ? 0.7 + 0.2 * urgency(tick, horizon) : 0.4 + 0.15 * urgency(tick, horizon);
                return new Threat(spear ? ThreatKind.SPEAR : ThreatKind.MELEE, severity, tick,
                        input.targetEye.add(input.targetVelocity.scale(tick)), relative);
            }
        }
        return null;
    }

    private static Threat maceThreat(Input input, AABB body, int horizon) {
        if (input.targetWeapon != Weapon.MACE || !input.targetAttackActive || input.targetOnGround
                || input.targetGliding || input.targetVelocity.y >= -0.15
                || input.targetPosition.y <= input.selfPosition.y + 0.5 || input.targetReach <= 0.0) return null;
        Vec3 position = input.targetEye;
        Vec3 velocity = input.targetVelocity;
        double previousDistance = body.distanceToSqr(position);
        for (int tick = 1; tick <= horizon; tick++) {
            position = position.add(velocity);
            AABB futureBody = body.move(input.selfVelocity.scale(tick));
            double distance = futureBody.distanceToSqr(position);
            if (distance < previousDistance && distance <= square(input.targetReach)) {
                return new Threat(ThreatKind.MACE, 0.75 + 0.2 * urgency(tick, horizon), tick, position,
                        velocity.subtract(input.selfVelocity));
            }
            previousDistance = distance;
            velocity = new Vec3(velocity.x * 0.91, (velocity.y - 0.08) * 0.98, velocity.z * 0.91);
        }
        return null;
    }

    private static Threat projectileThreat(Input input, AABB body, Projectile projectile, int horizon) {
        if (!valid(projectile)) return null;
        Vec3 position = projectile.position;
        Vec3 velocity = projectile.velocity;
        AABB hitbox = body.inflate(projectile.radius + 0.05);
        for (int tick = 0; tick < horizon; tick++) {
            Vec3 next = position.add(velocity);
            Vec3 relativeFrom = position.subtract(input.selfVelocity.scale(tick));
            Vec3 relativeTo = next.subtract(input.selfVelocity.scale(tick + 1.0));
            double fraction = segmentHit(hitbox, relativeFrom, relativeTo);
            if (fraction >= 0.0) {
                double impact = tick + fraction;
                return new Threat(ThreatKind.PROJECTILE, 0.55 + 0.35 * urgency(impact, horizon), impact,
                        position.lerp(next, fraction), velocity.subtract(input.selfVelocity));
            }
            position = next;
            velocity = velocity.scale(projectile.drag).add(0.0, -projectile.gravity, 0.0);
        }
        return null;
    }

    private static Threat stronger(Threat first, Threat second) {
        if (first == null) return second;
        if (second == null) return first;
        double firstScore = priority(first);
        double secondScore = priority(second);
        return secondScore > firstScore || secondScore == firstScore && second.tick < first.tick ? second : first;
    }

    private static double priority(Threat threat) {
        boolean imminentHeavy = (threat.kind == ThreatKind.MACE || threat.kind == ThreatKind.SPEAR) && threat.tick <= 3.0;
        return threat.severity + (imminentHeavy ? 0.5 : 0.0);
    }

    private static double segmentHit(AABB box, Vec3 from, Vec3 to) {
        if (box.contains(from)) return 0.0;
        double length = from.distanceTo(to);
        if (length < 1.0E-9) return -1.0;
        return box.clip(from, to).map(point -> Math.clamp(from.distanceTo(point) / length, 0.0, 1.0)).orElse(-1.0);
    }

    private static AABB body(Input input) {
        double half = input.selfWidth * 0.5;
        Vec3 feet = input.selfPosition;
        return new AABB(feet.x - half, feet.y, feet.z - half,
                feet.x + half, feet.y + input.selfHeight, feet.z + half);
    }

    private static Vec3 bounded(Vec3 vector, double maximum) {
        double length = vector.length();
        return length > maximum ? vector.scale(maximum / length) : vector;
    }

    private static double urgency(double tick, int horizon) {
        return Math.clamp(1.0 - tick / horizon, 0.0, 1.0);
    }

    private static double square(double value) {
        return value * value;
    }

    private static boolean valid(Input input) {
        return input != null && finite(input.selfPosition) && velocity(input.selfVelocity)
                && finite(input.targetPosition) && velocity(input.targetVelocity)
                && finite(input.targetEye) && finite(input.targetLook) && input.targetWeapon != null
                && Double.isFinite(input.selfWidth) && input.selfWidth >= 0.1 && input.selfWidth <= 4.0
                && Double.isFinite(input.selfHeight) && input.selfHeight >= 0.1 && input.selfHeight <= 5.0
                && Double.isFinite(input.targetReach) && input.targetReach >= 0.0 && input.targetReach <= 16.0;
    }

    private static boolean valid(Projectile projectile) {
        return projectile != null && finite(projectile.position) && velocity(projectile.velocity)
                && Double.isFinite(projectile.gravity) && projectile.gravity >= 0.0 && projectile.gravity <= 1.0
                && Double.isFinite(projectile.drag) && projectile.drag >= 0.0 && projectile.drag <= 1.0
                && Double.isFinite(projectile.radius) && projectile.radius >= 0.0 && projectile.radius <= 2.0;
    }

    private static boolean velocity(Vec3 vector) {
        return finite(vector) && vector.lengthSqr() <= MAX_SPEED * MAX_SPEED;
    }

    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y)
                && Double.isFinite(vector.z) && Double.isFinite(vector.lengthSqr());
    }

    private static Output empty() {
        return new Output(0.0, Vec3.ZERO, ThreatKind.NONE, 0.0, Double.POSITIVE_INFINITY,
                Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, false, false);
    }
}
