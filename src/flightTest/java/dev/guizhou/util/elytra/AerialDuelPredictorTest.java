package dev.guizhou.util.elytra;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static dev.guizhou.util.elytra.AerialDuelPredictor.ThreatKind;
import static dev.guizhou.util.elytra.AerialDuelPredictor.Weapon;

public final class AerialDuelPredictorTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        leadsAndValidation();
        meleeAndSpear();
        mace();
        projectiles();
        sideClearance();
        attackPriority();
        immutableParallelInputs();
        observedMovementModels();
        fallingMaceEnvelope();
        System.out.println("AerialDuelPredictorTest passed: " + checks + " checks");
    }

    private static void leadsAndValidation() {
        Fixture fixture = new Fixture();
        var output = fixture.predict();
        kind(ThreatKind.NONE, output, "Unarmed target is not a threat");
        close(4.0, output.leadTicks(), "Stationary distant target caps lead");
        check(output.interceptOffset().equals(Vec3.ZERO), "Stationary target has no lead offset");
        check(output.recommendedDirection().equals(Vec3.ZERO), "No needless sidestep without a threat");
        check(Double.isInfinite(output.impactTicks()), "No threat has no impact time");
        fixture.selfVelocity = new Vec3(2, 0, 0);
        fixture.targetVelocity = new Vec3(0, 0, 1);
        close(Math.sqrt(12.0), fixture.predict().leadTicks(), "Coherent transverse motion uses intercept");
        fixture.consistent = false;
        fixture.latency = 3;
        check(fixture.predict().leadTicks() <= 1.0, "Uncertain motion has a short horizon");
        fixture.consistent = true;
        fixture.selfVelocity = new Vec3(10, 0, 0);
        fixture.targetVelocity = new Vec3(-8, 0, 0);
        fixture.targetPosition = new Vec3(100, 0, 0);
        close(12.0, fixture.predict().interceptOffset().length(), "Fast lead stays spatially bounded");
        neutral(AerialDuelPredictor.predict(null), "Null input");
        fixture.selfPosition = new Vec3(Double.NaN, 0, 0);
        neutral(fixture.predict(), "NaN position");
        fixture.selfPosition = Vec3.ZERO;
        fixture.selfVelocity = new Vec3(33, 0, 0);
        neutral(fixture.predict(), "Excessive velocity");
        fixture.selfVelocity = Vec3.ZERO;
        fixture.width = 0.0;
        neutral(fixture.predict(), "Invalid body width");
        fixture.width = 0.6;
        fixture.height = Double.NaN;
        neutral(fixture.predict(), "Invalid body height");
        fixture.height = 1.8;
        fixture.reach = Double.POSITIVE_INFINITY;
        neutral(fixture.predict(), "Invalid reach");
        fixture.reach = 3.0;
        fixture.look = null;
        neutral(fixture.predict(), "Missing look vector");
    }

    private static void meleeAndSpear() {
        Fixture fixture = new Fixture();
        fixture.weapon = Weapon.MELEE;
        fixture.targetPosition = new Vec3(2, 0, 0);
        kind(ThreatKind.NONE, fixture.predict(), "Facing alone is not an attack");
        fixture.active = true;
        var melee = fixture.predict();
        kind(ThreatKind.MELEE, melee, "Active in-range melee");
        close(0.0, melee.impactTicks(), "Already in melee reach");
        fixture.targetPosition = new Vec3(8, 0, 0);
        kind(ThreatKind.NONE, fixture.predict(), "Stationary out-of-range melee");
        fixture.targetVelocity = new Vec3(-2, 0, 0);
        melee = fixture.predict();
        kind(ThreatKind.MELEE, melee, "Closing melee reaches predicted body");
        check(melee.impactTicks() > 0.0, "Closing melee is a future threat");
        fixture.targetVelocity = new Vec3(2, 0, 0);
        kind(ThreatKind.NONE, fixture.predict(), "Departing melee does not evade continually");

        fixture.weapon = Weapon.SPEAR;
        fixture.targetPosition = new Vec3(6, 0, 0);
        fixture.targetVelocity = new Vec3(-1, 0, 0);
        var spear = fixture.predict();
        kind(ThreatKind.SPEAR, spear, "Approaching active spear");
        check(spear.impactTicks() <= 3.0 && spear.severity() >= 0.65, "Imminent spear is urgent");
        fixture.active = false;
        kind(ThreatKind.NONE, fixture.predict(), "Inactive approaching spear");
        fixture.active = true;
        fixture.targetVelocity = Vec3.ZERO;
        fixture.targetPosition = new Vec3(2, 0, 0);
        kind(ThreatKind.NONE, fixture.predict(), "Stationary spear without closing speed");
        fixture.targetVelocity = new Vec3(1, 0, 0);
        kind(ThreatKind.NONE, fixture.predict(), "Receding spear");
        fixture.targetPosition = new Vec3(6, 0, 2);
        fixture.targetVelocity = new Vec3(-1, 0, 0);
        fixture.look = new Vec3(-1, 0, 0);
        kind(ThreatKind.NONE, fixture.predict(), "Spear trajectory misses to the side");
        fixture.targetPosition = new Vec3(6, 0, 0);
        fixture.targetVelocity = new Vec3(-0.5, 0, 0);
        fixture.look = new Vec3(0, 0, 1);
        kind(ThreatKind.NONE, fixture.predict(), "Misaligned spear ray");
        fixture.look = Vec3.ZERO;
        kind(ThreatKind.NONE, fixture.predict(), "Missing facing does not invent a melee ray");
    }

    private static void mace() {
        Fixture fixture = new Fixture();
        fixture.weapon = Weapon.MACE;
        fixture.active = true;
        fixture.targetPosition = new Vec3(0, 6, 0);
        fixture.targetVelocity = new Vec3(0, -1, 0);
        fixture.onGround = false;
        var descending = fixture.predict();
        kind(ThreatKind.MACE, descending, "Aligned descending mace");
        check(descending.impactTicks() >= 1.0 && descending.impactTicks() <= 4.0,
                "Mace estimates approach in a bounded time");
        check(descending.severity() >= 0.75, "Descending mace is a heavy threat");
        fixture.gliding = true;
        kind(ThreatKind.NONE, fixture.predict(), "Gliding mace is not a smash");
        fixture.gliding = false;
        fixture.onGround = true;
        kind(ThreatKind.NONE, fixture.predict(), "Grounded mace is not a smash");
        fixture.onGround = false;
        fixture.targetVelocity = new Vec3(0, 1, 0);
        kind(ThreatKind.NONE, fixture.predict(), "Climbing mace is not a descending threat");
        fixture.targetVelocity = new Vec3(0, -1, 0);
        fixture.active = false;
        kind(ThreatKind.NONE, fixture.predict(), "Inactive mace");
        fixture.active = true;
        fixture.targetPosition = new Vec3(8, 6, 0);
        kind(ThreatKind.NONE, fixture.predict(), "Displaced descending mace misses");
        fixture.targetPosition = new Vec3(0, 6, 0);
        fixture.selfVelocity = new Vec3(3, 0, 0);
        kind(ThreatKind.NONE, fixture.predict(), "Escaping self leaves mace trajectory");
    }

    private static void projectiles() {
        Fixture fixture = new Fixture();
        fixture.projectiles = List.of(projectile(new Vec3(10, 1, 0), new Vec3(-20, 0, 0), 0, 1));
        var crossing = fixture.predict();
        kind(ThreatKind.PROJECTILE, crossing, "Fast projectile cannot tunnel through player");
        check(crossing.impactTicks() > 0.4 && crossing.impactTicks() < 0.6, "Subtick projectile impact");
        fixture.projectiles = List.of(projectile(new Vec3(10, 1, 1), new Vec3(-20, 0, 0), 0, 1));
        kind(ThreatKind.NONE, fixture.predict(), "Fast projectile near miss");
        fixture.projectiles = List.of(projectile(new Vec3(3, 1, 0), Vec3.ZERO, 0, 1));
        fixture.selfVelocity = new Vec3(1, 0, 0);
        kind(ThreatKind.PROJECTILE, fixture.predict(), "Moving self can intersect stationary projectile");
        fixture.selfVelocity = new Vec3(-1, 0, 0);
        kind(ThreatKind.NONE, fixture.predict(), "Relative motion allows self to depart");
        fixture.selfVelocity = Vec3.ZERO;
        fixture.projectiles = List.of(projectile(new Vec3(3, 4, 0), new Vec3(-0.5, 0, 0), 0.2, 1));
        kind(ThreatKind.PROJECTILE, fixture.predict(), "Gravity bends projectile into body");
        fixture.projectiles = List.of(projectile(new Vec3(3, 4, 0), new Vec3(-0.5, 0, 0), 0, 1));
        kind(ThreatKind.NONE, fixture.predict(), "Level trajectory stays above body");
        fixture.projectiles = List.of(projectile(new Vec3(4, 1, 0), new Vec3(-1, 0, 0), 0, 1));
        kind(ThreatKind.PROJECTILE, fixture.predict(), "Unslowed projectile reaches player");
        fixture.projectiles = List.of(projectile(new Vec3(4, 1, 0), new Vec3(-1, 0, 0), 0, 0.5));
        kind(ThreatKind.NONE, fixture.predict(), "Strong drag prevents intersection");
        fixture.projectiles = List.of(projectile(new Vec3(0, 1, 0), Vec3.ZERO, 0, 1));
        close(0.0, fixture.predict().impactTicks(), "Existing projectile overlap is immediate");
        fixture.projectiles = List.of(
                projectile(new Vec3(Double.NaN, 1, 0), Vec3.ZERO, 0, 1),
                projectile(new Vec3(0, 1, 0), new Vec3(33, 0, 0), 0, 1),
                projectile(new Vec3(0, 1, 0), Vec3.ZERO, -1, 1),
                projectile(new Vec3(0, 1, 0), Vec3.ZERO, 0, Double.NaN));
        kind(ThreatKind.NONE, fixture.predict(), "Malformed projectiles are discarded");
        fixture.weapon = Weapon.SPEAR;
        fixture.active = true;
        fixture.targetVelocity = new Vec3(-1, 0, 0);
        fixture.projectiles = List.of(projectile(new Vec3(0, 1, 0), Vec3.ZERO, 0, 1));
        kind(ThreatKind.SPEAR, fixture.predict(), "Immediate projectile does not obscure imminent heavy attack");
    }

    private static void sideClearance() {
        Fixture fixture = new Fixture();
        fixture.weapon = Weapon.SPEAR;
        fixture.active = true;
        fixture.targetVelocity = new Vec3(-1, 0, 0);
        var output = fixture.predict();
        check(output.leftClear() && output.rightClear(), "No snapshot leaves both candidates unruled out");
        close(1.0, output.leftDirection().length(), "Unit sidestep candidate");
        close(-1.0, output.leftDirection().dot(output.rightDirection()), "Opposite sidestep candidates");
        AABB leftWall = new AABB(-1, -1, -1.5, 1, 3, -0.5);
        AABB rightWall = new AABB(-1, -1, 0.5, 1, 3, 1.5);
        fixture.snapshot = snapshot(List.of(leftWall));
        output = fixture.predict();
        check(!output.leftClear() && output.rightClear(), "Snapshot blocks left candidate");
        check(output.recommendedDirection().equals(output.rightDirection()), "Select unblocked side");
        fixture.snapshot = snapshot(List.of(leftWall, rightWall));
        output = fixture.predict();
        check(!output.leftClear() && !output.rightClear(), "Both sides blocked");
        check(output.recommendedDirection().equals(Vec3.ZERO), "No forced sidestep into walls");
    }

    private static void attackPriority() {
        for (ThreatKind kind : List.of(ThreatKind.MELEE, ThreatKind.PROJECTILE)) {
            var output = threat(kind, 0.9, 0.0);
            check(!interrupt(output, true, 20, 0), "Ordinary pressure preserves a real smash window");
            check(interrupt(output, false, 20, 0), "Ordinary pressure can interrupt travel");
        }
        for (ThreatKind kind : List.of(ThreatKind.MACE, ThreatKind.SPEAR)) {
            check(interrupt(threat(kind, 0.7, 3.0), true, 20, 0), "Imminent heavy attack can interrupt smash");
            check(!interrupt(threat(kind, 0.7, 3.1), true, 20, 0), "Distant heavy attack preserves smash");
            check(!interrupt(threat(kind, 0.64, 1.0), true, 20, 0), "Weak heavy estimate preserves smash");
        }
        check(interrupt(null, true, 8, 0), "Low health can interrupt even without a threat");
        check(!interrupt(null, true, 8, 1), "Absorption counts toward available health");
        check(!interrupt(threat(ThreatKind.MELEE, 0.34, 1), false, 20, 0), "Weak ordinary estimate does not interrupt");
        check(!interrupt(threat(ThreatKind.NONE, 1, 0), false, 20, 0), "NONE never interrupts healthy player");
        check(!interrupt(threat(ThreatKind.MACE, Double.NaN, 0), false, 20, 0), "NaN urgency rejected");
        check(!interrupt(threat(ThreatKind.MACE, 0.9, -1), false, 20, 0), "Negative impact rejected");
        check(!interrupt(threat(ThreatKind.MACE, 0.9, Double.POSITIVE_INFINITY), false, 20, 0),
                "Nonfinite impact rejected");
        check(!interrupt(threat(null, 0.9, 0), false, 20, 0), "Missing threat kind rejected");
    }

    private static void immutableParallelInputs() throws Exception {
        Fixture fixture = new Fixture();
        var missiles = new ArrayList<AerialDuelPredictor.Projectile>();
        missiles.add(null);
        missiles.add(projectile(new Vec3(10, 1, 0), new Vec3(-20, 0, 0), 0, 1));
        fixture.projectiles = missiles;
        var input = fixture.build();
        missiles.clear();
        check(input.projectiles().size() == 1, "Input copies projectiles and discards nulls");
        kind(ThreatKind.PROJECTILE, AerialDuelPredictor.predict(input), "Source list mutation cannot alter snapshot");
        boolean immutable = false;
        try {
            input.projectiles().clear();
        } catch (UnsupportedOperationException expected) {
            immutable = true;
        }
        check(immutable, "Input list cannot be mutated by a worker");
        for (int i = 0; i < AerialDuelPredictor.MAX_PROJECTILES; i++) {
            missiles.add(projectile(new Vec3(10, 1, 2), new Vec3(-20, 0, 0), 0, 1));
        }
        missiles.add(projectile(new Vec3(0, 1, 0), Vec3.ZERO, 0, 1));
        check(fixture.build().projectiles().size() == AerialDuelPredictor.MAX_PROJECTILES, "Projectile work is bounded");
        kind(ThreatKind.NONE, fixture.predict(), "Overflow projectile lies outside bounded snapshot");

        var expected = AerialDuelPredictor.predict(input);
        List<Callable<Boolean>> jobs = new ArrayList<>();
        for (int worker = 0; worker < 4; worker++) {
            jobs.add(() -> {
                for (int i = 0; i < 256; i++) {
                    if (!expected.equals(AerialDuelPredictor.predict(input))) return false;
                }
                return true;
            });
        }
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (var result : pool.invokeAll(jobs)) check(result.get(), "Parallel predictions remain deterministic");
        }
        check(input.selfPosition().equals(Vec3.ZERO), "Prediction leaves input position unchanged");
    }

    private static boolean interrupt(AerialDuelPredictor.Output output, boolean smash, double health, double absorption) {
        return AerialDuelPredictor.shouldInterruptAttack(output, smash, health, absorption, 20);
    }

    private static void observedMovementModels() {
        Fixture fixture = new Fixture();
        fixture.onGround = false;
        fixture.gliding = true;
        fixture.selfVelocity = new Vec3(2, 0, 0);
        fixture.targetPosition = new Vec3(20, 0, 0);
        fixture.targetVelocity = new Vec3(0.8, -0.1, 0);
        fixture.motion = new TargetMotionTracker.Snapshot(TargetMotionTracker.Mode.GLIDE, 10,
                fixture.targetVelocity, Vec3.ZERO, 0, 1, 4);
        var normal = fixture.predict();
        close(3.2, normal.interceptOffset().x, "ordinary gliding uses its observed speed");
        close(-0.4, normal.interceptOffset().y, "ordinary glide follows measured descent");
        fixture.targetVelocity = new Vec3(1.3, 0, 0);
        fixture.motion = new TargetMotionTracker.Snapshot(TargetMotionTracker.Mode.GLIDE_ACCELERATING, 11,
                fixture.targetVelocity, new Vec3(0.2, 0, 0), 0, 1, 3);
        var boosted = fixture.predict();
        check(boosted.leadTicks() <= 3 && boosted.interceptOffset().x > fixture.targetVelocity.x * boosted.leadTicks(),
                "asynchronous predictor consumes measured acceleration and its reliability horizon");
        fixture.targetVelocity = new Vec3(0.4, 0, 0.2);
        fixture.gliding = false;
        fixture.motion = new TargetMotionTracker.Snapshot(TargetMotionTracker.Mode.ARMORED_AIR, 12,
                fixture.targetVelocity, Vec3.ZERO, 0, 1, 2.5);
        var armor = fixture.predict();
        close(0, armor.interceptOffset().y, "armor/non-gliding flight has no invented gravity");
        close(1.0, armor.interceptOffset().x, "armor observation is not a fixed fast-flight speed");
        fixture.motion = new TargetMotionTracker.Snapshot(TargetMotionTracker.Mode.GROUND, 13,
                Vec3.ZERO, Vec3.ZERO, 0, 0, 0);
        check(fixture.predict().interceptOffset().equals(Vec3.ZERO), "landing/reset snapshot suppresses stale lead");

        fixture.selfVelocity = Vec3.ZERO;
        fixture.targetPosition = new Vec3(8, 0, 0);
        fixture.targetVelocity = new Vec3(-1, 0, 0);
        fixture.weapon = Weapon.SPEAR;
        fixture.active = true;
        fixture.motion = new TargetMotionTracker.Snapshot(TargetMotionTracker.Mode.ARMORED_AIR, 14,
                fixture.targetVelocity, Vec3.ZERO, 0, 0.3, 1);
        var approaching = fixture.predict();
        kind(ThreatKind.SPEAR, approaching, "uncertain aim keeps the existing distant spear warning window");
        check(approaching.leadTicks() <= 1 && approaching.impactTicks() > 1,
                "aim horizon does not truncate protection lookahead");
        fixture.weapon = Weapon.MACE;
        fixture.targetPosition = new Vec3(0, 8, 0);
        fixture.targetVelocity = new Vec3(0, -1, 0);
        fixture.motion = new TargetMotionTracker.Snapshot(TargetMotionTracker.Mode.ARMORED_AIR, 15,
                fixture.targetVelocity, Vec3.ZERO, 0, 0.3, 1);
        kind(ThreatKind.MACE, fixture.predict(), "descending observed flight retains long-range mace warning");
        close(-8, fixture.motion.threatDisplacement(8).y, "threat drift does not invent gravity for plugin flight");
        close(-1, fixture.motion.displacement(8).y, "attack aim remains bounded to measured reliability");
    }

    private static AerialDuelPredictor.Output threat(ThreatKind kind, double urgency, double ticks) {
        return new AerialDuelPredictor.Output(0, Vec3.ZERO, kind, urgency, ticks,
                Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, true, true);
    }

    private static void fallingMaceEnvelope() {
        Fixture fixture = new Fixture();
        fixture.targetPosition = new Vec3(0, 5, 0);
        fixture.targetVelocity = new Vec3(0, -0.2, 0);
        fixture.weapon = Weapon.MACE;
        fixture.active = true;
        fixture.onGround = false;
        fixture.latency = 2;
        var falling = fixture.predict();
        kind(ThreatKind.MACE, falling, "ordinary gravity predicts a slowly descending mace before linear drift does");
        close(6, falling.impactTicks(), "ordinary falling trajectory reaches threat range at tick six");
        fixture.motion = new TargetMotionTracker.Snapshot(TargetMotionTracker.Mode.ARMORED_AIR, 20,
                fixture.targetVelocity, Vec3.ZERO, 0, 0.3, 1);
        var observed = fixture.predict();
        kind(ThreatKind.MACE, observed, "sparse armored observations retain the ordinary falling risk envelope");
        close(falling.impactTicks(), observed.impactTicks(), "low-confidence motion cannot delay the existing mace warning");
        close(-0.2, observed.interceptOffset().y, "defensive gravity never leaks into aim prediction");
        close(-1.6, fixture.motion.threatDisplacement(8).y, "plugin trajectory remains an independent observed path");
        fixture.motion = new TargetMotionTracker.Snapshot(TargetMotionTracker.Mode.ARMORED_AIR, 21,
                fixture.targetVelocity, new Vec3(0, -0.2, 0), 0, 1, 3);
        check(fixture.predict().impactTicks() < falling.impactTicks(),
                "a more urgent observed descent also wins over the ordinary falling path");
        fixture.gliding = true;
        kind(ThreatKind.MACE, fixture.predict(), "observed armor flight may shed wings before its remote glide flag clears");
        fixture.motion = new TargetMotionTracker.Snapshot(TargetMotionTracker.Mode.GLIDE, 22,
                fixture.targetVelocity, Vec3.ZERO, 0, 1, 3);
        kind(ThreatKind.NONE, fixture.predict(), "ordinary glide still does not predict a smash");
        fixture.gliding = false;
        fixture.onGround = true;
        kind(ThreatKind.NONE, fixture.predict(), "landing still immediately removes falling mace risk");
    }

    private static FlightCollisionSnapshot snapshot(List<AABB> obstacles) {
        return new FlightCollisionSnapshot(Vec3.ZERO, new AABB(-0.3, 0, -0.3, 0.3, 1.8, 0.3),
                new AABB(-10, -10, -10, 10, 10, 10), obstacles);
    }

    private static AerialDuelPredictor.Projectile projectile(Vec3 position, Vec3 velocity, double gravity, double drag) {
        return new AerialDuelPredictor.Projectile(position, velocity, gravity, drag, 0.05);
    }

    private static void neutral(AerialDuelPredictor.Output output, String message) {
        kind(ThreatKind.NONE, output, message);
        close(0.0, output.leadTicks(), message + " does not lead");
        check(output.recommendedDirection().equals(Vec3.ZERO), message + " does not steer");
    }

    private static void kind(ThreatKind expected, AerialDuelPredictor.Output output, String message) {
        check(output.threatKind() == expected, message + ": " + output.threatKind());
        check(Double.isFinite(output.severity()) && output.severity() >= 0 && output.severity() <= 1,
                message + " has bounded urgency");
    }

    private static void close(double expected, double actual, String message) {
        check(Math.abs(expected - actual) < 1.0E-7, message + ": " + actual);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class Fixture {
        Vec3 selfPosition = Vec3.ZERO;
        Vec3 selfVelocity = Vec3.ZERO;
        double width = 0.6;
        double height = 1.8;
        Vec3 targetPosition = new Vec3(6, 0, 0);
        Vec3 targetVelocity = Vec3.ZERO;
        Vec3 look = new Vec3(-1, 0, 0);
        double reach = 3;
        Weapon weapon = Weapon.NONE;
        boolean onGround = true;
        boolean gliding;
        boolean active;
        int latency;
        boolean consistent = true;
        List<AerialDuelPredictor.Projectile> projectiles = List.of();
        FlightCollisionSnapshot snapshot;
        TargetMotionTracker.Snapshot motion;

        AerialDuelPredictor.Input build() {
            return new AerialDuelPredictor.Input(selfPosition, selfVelocity, width, height, targetPosition,
                    targetVelocity, targetPosition.add(0, 1.62, 0), look, reach, weapon, onGround, gliding,
                    active, latency, consistent, projectiles, snapshot, motion);
        }

        AerialDuelPredictor.Output predict() {
            return AerialDuelPredictor.predict(build());
        }
    }
}
