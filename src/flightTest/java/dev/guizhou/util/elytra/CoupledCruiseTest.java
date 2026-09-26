package dev.guizhou.util.elytra;

import dev.guizhou.util.elytra.CoupledCruise.*;
import net.minecraft.world.phys.Vec3;
import java.util.Random;

public final class CoupledCruiseTest {
    private static int checks;
    private static final Vec3 NORTH = new Vec3(0, 0, 1);

    public static void main(String[] args) {
        inputAndHeading();
        verticalAndResidualMomentum();
        heightCycleAndReset();
        ballIntersection();
        numericalBoundaries();
        realGlideCycle();
        randomizedConstraints();
        dualViewSelection();
        levelSpeedCap();
        System.out.println("CoupledCruiseTest passed: " + checks + " checks");
    }

    private static void dualViewSelection() {
        CoupledCruise c = new CoupledCruise();
        Config cfg = Config.defaults();
        check(!c.select(cfg, new Intent(NORTH, 1, false), 0, 200, false).dualActive(),
                "dualView off keeps the single view");
        check(c.select(cfg, new Intent(NORTH, 1, false), 0, 200, false).boxLook() == null,
                "single view carries no box look");
        check(!c.select(cfg, new Intent(NORTH, 0, false), 0, 200, true).dualActive(),
                "level cruise never goes dual");
        check(!c.select(cfg, new Intent(Vec3.ZERO, 1, false), 0, 200, true).dualActive(),
                "pure vertical never goes dual");

        // climb: auto angle formula (reference parity), level box look on the cruise yaw
        Selection axial = c.select(cfg, new Intent(NORTH, 1, false), 0, 200, true);
        check(axial.dualActive() && axial.boxLook() != null, "cardinal climb goes dual");
        near(pitch(axial.look()), -89.25, "cardinal auto dual climb angle");
        near(axial.boxLook().y, 0, "climb box look is level");
        check(axial.boxLook().z > 0.99, "climb box look keeps the cruise yaw");
        Selection diagonal = c.select(cfg, new Intent(new Vec3(1, 0, 1), 1, false), 0, 200, true);
        near(pitch(diagonal.look()), -(Math.toDegrees(Math.acos(Math.min(1, Math.sqrt(2) - 1))) - 0.75),
                "diagonal auto dual climb angle");
        Config fixed = new Config(15, -45, 30, true, -1.7, false, 60, 15, 1.7, .002, 70, 0);
        near(pitch(c.select(fixed, new Intent(NORTH, 1, false), 0, 200, true).look()), -70,
                "fixed dual climb angle overrides the formula");
        for (double deg = 0; deg <= 360; deg += 17) {
            Vec3 wish = new Vec3(-Math.sin(Math.toRadians(deg)), 0, Math.cos(Math.toRadians(deg)));
            Selection climb = c.select(cfg, new Intent(wish, 1, false), 0, 200, true);
            double p = pitch(climb.look());
            check(climb.dualActive() && p >= -89.5 - 1e-9 && p <= -30 + 1e-9,
                    "climb dual pitch in range @ " + deg);
            check(climb.dualActive() && Math.abs(climb.boxLook().y) < 1e-9,
                    "climb box look level @ " + deg);
        }

        // dive: solved pair, both pitches positive-down, box look steeper than motion
        Selection dive = c.select(cfg, new Intent(NORTH, -1, false), 0, 200, true);
        check(dive.dualActive(), "cardinal dive goes dual");
        double[] pair = CoupledCruise.solveDivePair(0, 1.0);
        near(pitch(dive.look()), pair[0], "dive motion pitch from solver");
        near(pitch(dive.boxLook()), pair[1], "dive box pitch from solver");
        check(pair[1] > pair[0] && pair[0] > 0, "dive pair spans downward looks");
        for (double deg = 0; deg <= 360; deg += 23) {
            Vec3 wish = new Vec3(-Math.sin(Math.toRadians(deg)), 0, Math.cos(Math.toRadians(deg)));
            Selection d = c.select(cfg, new Intent(wish, -1, false), 0, 200, true);
            check(d.dualActive() && pitch(d.look()) > 0 && pitch(d.boxLook()) > pitch(d.look())
                    && pitch(d.boxLook()) <= 90 + 1e-9, "dive pair bounds @ " + deg);
        }

        // the box look is the envelope's second view: steep climb keeps horizontal headroom
        Vec3 prev = new Vec3(0, 0, 0.3), sim = new Vec3(0.01, 0.4, 0.305);
        Envelope dualBox = CoupledCruise.envelope(cfg, prev, sim, axial.look(), axial.boxLook());
        Envelope singleBox = CoupledCruise.envelope(cfg, prev, sim, axial.look(), axial.look());
        check(dualBox.max().z > singleBox.max().z + 1.0, "dual view widens the horizontal envelope");
        Result dual = c.apply(cfg, axial, prev, sim, axial.look(), axial.look());
        check(dual.feasible() && dual.velocity().z > 1.0, "dual apply grows horizontal speed on a steep climb");
        Config steepSingle = new Config(15, -89.25, 30, false, -1.7, false, 60, 15, 1.7, .002);
        Selection steep = c.select(steepSingle, new Intent(NORTH, 1, false), 0, 200, false);
        check(!steep.dualActive(), "unsupported selection stays single view");
        Result throttled = c.apply(steepSingle, steep, prev, sim, steep.look(), steep.look());
        check(throttled.feasible() && throttled.velocity().z < 0.5,
                "single-view steep climb throttles horizontal growth");
    }

    private static void inputAndHeading() {
        CoupledCruise c = new CoupledCruise();
        Config cfg = Config.defaults();
        check(!c.select(cfg, new Intent(Vec3.ZERO, 0, false), 0, 200).active(), "no keys + AutoFly off is inactive");
        check(!c.select(cfg, new Intent(Vec3.ZERO, 0, true), Double.NaN, 200).active(), "missing heading is not guessed");
        for (int tick = 0; tick < 20; tick++) {
            Selection east = c.select(cfg, new Intent(Vec3.ZERO, 0, true), -90, 200);
            check(east.active() && east.look().x > .96 && Math.abs(east.look().z) < 1e-9, "AutoFly retains east heading");
        }
        Selection axial = c.select(cfg, new Intent(NORTH, 1, false), 90, 200);
        near(pitch(axial.look()), -60, "smart cardinal climb");
        Selection diagonal = c.select(cfg, new Intent(new Vec3(1, 0, 1), 1, false), 90, 200);
        near(pitch(diagonal.look()), -45, "smart diagonal climb");
        Result overridden = c.apply(cfg, axial, Vec3.ZERO, Vec3.ZERO, new Vec3(1,0,0), NORTH);
        check(!overridden.feasible(), "competing rotation must not use a mismatched glide sample");
    }

    private static void verticalAndResidualMomentum() {
        CoupledCruise c = new CoupledCruise();
        Config cfg = Config.defaults();
        for (double heading : new double[]{0, -90, 45, Double.NaN}) {
            Selection up = c.select(cfg, new Intent(Vec3.ZERO, 1, false), heading, 200);
            check(up.pureVertical() && up.look().equals(new Vec3(0, 1, 0)), "pure vertical survives caller heading");
            Result r = c.apply(cfg, up, Vec3.ZERO, Vec3.ZERO, up.look(), NORTH);
            check(r.feasible() && r.velocity().horizontalDistance() == 0, "up adds no horizontal drive");
            Result inertia = c.apply(cfg, up, Vec3.ZERO, new Vec3(4, 0, 0), up.look(), NORTH);
            near(inertia.velocity().x, 3.832, "pure vertical preserves forced residual momentum");
            check(inertia.feasible() && inertia.horizontalUnmet(), "inertia is reported, not discarded");
        }
        Selection down = c.select(cfg, new Intent(Vec3.ZERO, -1, false), 0, 200);
        Result drop = c.apply(cfg, down, Vec3.ZERO, new Vec3(0,-5,0), down.look(), NORTH);
        near(drop.velocity().y, -6.698, "pure vertical descent bypasses glide throttle");
        check(drop.velocity().horizontalDistance() == 0 && !drop.descentUnmet(), "pure descent flags");
        Selection controlled = c.select(cfg, new Intent(NORTH, -1, false), 0, 200);
        Result slow = c.apply(cfg, controlled, Vec3.ZERO, new Vec3(0,-1,0), controlled.look(), NORTH);
        near(slow.velocity().y, -1.7, "controlled descent crosses the soft target");
        Result fast = c.apply(cfg, controlled, Vec3.ZERO, new Vec3(0,-5,0), controlled.look(), NORTH);
        check(fast.feasible() && fast.descentUnmet() && fast.velocity().y < -1.7, "cannot brake within box reported honestly");
        Selection straight = c.select(cfg, new Intent(NORTH, 0, false), 0, 200);
        Result sideways = c.apply(cfg, straight, Vec3.ZERO, new Vec3(4,0,0), straight.look(), NORTH);
        check(sideways.feasible(), "perpendicular inertia does not falsely empty box/ball intersection");
        near(sideways.velocity().x, 3.832, "unused axis clamps zero into box");
    }

    private static void heightCycleAndReset() {
        CoupledCruise c = new CoupledCruise();
        Config cfg = pulse();
        Intent forward = new Intent(NORTH, 0, false);
        Selection start = c.select(cfg, forward, 0, 200);
        check(start.phase() == Phase.DESCEND && start.vertical() == -1, "cycle starts descending");
        check(c.select(cfg, forward, 0, 140).phase() == Phase.CLIMB, "bottom switches to climb");
        check(c.select(cfg, forward, 0, 199).phase() == Phase.CLIMB, "climb persists to top");
        check(c.select(cfg, forward, 0, 201).phase() == Phase.DESCEND, "top resumes descent");
        check(c.select(cfg, forward, 0, 140.5).phase() == Phase.DESCEND,
                "one-block apex overshoot must not shift the bottom from 140 to 141");
        c.select(cfg, forward, 0, 140);
        check(c.phase() == Phase.CLIMB, "second bottom");
        check(!c.select(cfg, new Intent(new Vec3(Double.NaN,0,0), 0, false), 0, 140).active()
                && c.phase() == Phase.LEVEL, "invalid input clears phase");
        check(c.select(cfg, forward, 0, 140).phase() == Phase.DESCEND, "invalidated cycle starts fresh");
        c.select(cfg, forward, 0, 70);
        check(c.select(Config.defaults(), forward, 0, 70).phase() == Phase.LEVEL, "toggle off clears climb");
        c.select(cfg, forward, 0, 200);
        c.select(cfg, forward, 0, 139);
        check(c.select(cfg, new Intent(NORTH, 1, false), 0, 139).phase() == Phase.LEVEL, "manual vertical resets cycle");
        c.reset();
        check(c.select(cfg, forward, 0, -40).phase() == Phase.DESCEND, "world/disable reset uses fresh height");
    }

    private static void ballIntersection() {
        CoupledCruise c = new CoupledCruise();
        Config cfg = cap(3);
        Selection s = c.select(cfg, new Intent(new Vec3(1,0,1), 0, false), 0, 200);
        Result r = c.apply(cfg, s, Vec3.ZERO, new Vec3(3.068,0,0), s.look(), NORTH);
        check(r.feasible() && r.speedLimited() && r.velocity().length() <= 3 + 1e-9, "review nonempty box/ball case");
        check(r.velocity().x >= 2.9 - 1e-9, "momentum lower bound respected");
        Config small = cap(1);
        Selection f = c.select(small, new Intent(NORTH,0,false), 0, 200);
        Result empty = c.apply(small, f, new Vec3(0,0,.5), new Vec3(0,-.04,2), f.look(), NORTH);
        check(!empty.feasible() && empty.velocity().z > 1, "true empty intersection explicitly rejected");
    }

    private static void numericalBoundaries() {
        CoupledCruise c = new CoupledCruise();
        Config cfg = new Config(15,-45,30,true,-1.7,false,60,15,.01,.1);
        Selection s = c.select(cfg, new Intent(NORTH,0,false), 0, 200);
        Envelope box = CoupledCruise.envelope(cfg, Vec3.ZERO, Vec3.ZERO, s.look(), NORTH);
        check(box.contains(Vec3.ZERO, 0), "inset cannot consume a narrow perpendicular axis");
        near(box.min().x, 0, "symmetric tiny axis lower");
        near(box.max().x, 0, "symmetric tiny axis upper");
        Result normal = c.apply(cfg,s,Vec3.ZERO,Vec3.ZERO,s.look(),NORTH);
        Result scaled = c.apply(cfg,s,Vec3.ZERO,Vec3.ZERO,s.look().scale(1e300),NORTH.scale(1e300));
        check(normal.feasible() && scaled.feasible(), "huge non-unit looks normalized without overflow");
        near(normal.velocity().distanceTo(scaled.velocity()), 0, "look magnitude does not change envelope");
        for (Vec3 v : new Vec3[]{new Vec3(1e300,1e300,1e300), new Vec3(Double.MAX_VALUE,0,0),
                new Vec3(Double.NaN,0,0), new Vec3(Double.POSITIVE_INFINITY,0,0)}) {
            Result result = c.apply(cfg,s,v,v,s.look(),NORTH);
            check(Double.isFinite(result.velocity().x) && Double.isFinite(result.velocity().y)
                    && Double.isFinite(result.velocity().z), "all extreme results remain finite");
            check(!result.feasible(), "out-of-cap or invalid extreme is not applied");
        }
    }

    private static void realGlideCycle() {
        CoupledCruise c = new CoupledCruise();
        Config cfg = pulse();
        Vec3 velocity = NORTH.scale(1.5), lastLook = NORTH;
        double height = 200, lowest = height, highest = height;
        boolean climbed = false, returned = false;
        for (int tick = 0; tick < 1000; tick++) {
            Selection s = c.select(cfg, new Intent(NORTH,0,false), 0, height);
            Vec3 sim = ElytraPhysics.simulateGlide(velocity, s.look(), .08);
            Result r = c.apply(cfg,s,velocity,sim,s.look(),lastLook);
            check(r.feasible(), "closed-loop glide result feasible");
            velocity = r.velocity();
            height += velocity.y;
            lowest = Math.min(lowest, height);
            highest = Math.max(highest, height);
            if (s.phase() == Phase.CLIMB) climbed = true;
            if (climbed && s.phase() == Phase.DESCEND) returned = true;
            lastLook = s.look();
        }
        check(lowest <= 140 && climbed && returned, "height cycle actually descends, climbs and repeats using real glide");
        // A glide-only integration can climb faster than boxCap; the box is an
        // acceleration envelope, not an absolute component cap. One discrete step
        // may overshoot by up to speedCap, but those steps must not accumulate.
        check(highest <= 200 + cfg.speedCap(), "apex overshoots must not accumulate: " + highest);
    }

    private static void randomizedConstraints() {
        Random random = new Random(20260919);
        for (int i = 0; i < 2000; i++) {
            Config cfg = cap(.05 + random.nextDouble() * 14.95);
            CoupledCruise c = new CoupledCruise();
            Vec3 previous = vector(random, 8), lastLook = vector(random, 1).normalize();
            Selection s = c.select(cfg, new Intent(vector(random,1), random.nextInt(3)-1, false), 0, 200);
            Vec3 sim = ElytraPhysics.simulateGlide(previous, s.look(), .08);
            Result r = c.apply(cfg,s,previous,sim,s.look(),lastLook);
            Envelope box = CoupledCruise.envelope(cfg,previous,sim,s.look(),lastLook);
            check(box.contains(r.velocity(), 1e-8), "result lies in every box axis, including rejected nearest point");
            Vec3 nearest = new Vec3(Math.clamp(0,box.min().x,box.max().x), Math.clamp(0,box.min().y,box.max().y),
                    Math.clamp(0,box.min().z,box.max().z));
            check(r.feasible() == (nearest.length() <= cfg.speedCap()), "empty/nonempty decided by exact closest point");
            if (r.feasible()) check(r.velocity().length() <= cfg.speedCap() + 1e-9, "feasible result respects total cap");
            if (s.vertical() < 0 && !s.pureVertical()) check(r.descentUnmet() == (r.velocity().y < cfg.descentFloor()-1e-9), "post-projection descent flag");
        }
    }

    private static void levelSpeedCap() {
        Config fast = Config.defaults().withBoxCap(Config.defaults().speedCap());
        check(fast.boxCap() == 15, "level axis allowance can match the aggressive total cap");
        check(new Config(15, -45, 30, true, -1.7, false, 60, 15, 100, .002).boxCap() == 15,
                "axis allowance does not exceed the total-cap bound");
        CoupledCruise c = new CoupledCruise();
        Selection level = c.select(fast, new Intent(NORTH, 0, false), 0, 200);
        check(level.active() && level.vertical() == 0 && !level.dualActive(), "level boost stays on one view");
        Result straight = c.apply(fast, level, Vec3.ZERO, Vec3.ZERO, level.look(), level.look());
        check(straight.feasible() && straight.velocity().length() > 14 && straight.velocity().length() <= 15 + 1e-6,
                "cardinal level flight reaches the speed cap: " + straight.velocity().length());
        Selection diagonal = c.select(fast, new Intent(new Vec3(1, 0, 1), 0, false), 0, 200);
        Result diag = c.apply(fast, diagonal, Vec3.ZERO, Vec3.ZERO, diagonal.look(), diagonal.look());
        check(diag.feasible() && diag.velocity().length() > 14 && diag.velocity().length() <= 15 + 1e-6,
                "diagonal level flight reaches the speed cap: " + diag.velocity().length());
        Config gentle = new Config(15, -45, 30, true, -1.7, false, 60, 8, 1.7, .002).withBoxCap(8);
        Selection g = new CoupledCruise().select(gentle, new Intent(NORTH, 0, false), 0, 200);
        Result gr = new CoupledCruise().apply(gentle, g, Vec3.ZERO, Vec3.ZERO, g.look(), g.look());
        check(gr.feasible() && gr.velocity().length() > 7.5 && gr.velocity().length() <= 8 + 1e-6,
                "gentle level cap is honored: " + gr.velocity().length());
        Config boxed = Config.defaults();
        Selection boxedLevel = new CoupledCruise().select(boxed, new Intent(NORTH, 0, false), 0, 200);
        Result limited = new CoupledCruise().apply(boxed, boxedLevel, Vec3.ZERO, Vec3.ZERO, boxedLevel.look(), boxedLevel.look());
        check(limited.feasible() && limited.velocity().length() < 2.5,
                "the 1.7 axis box still limits level flight when the caller keeps it");
    }

    private static Config pulse() { return new Config(15,-45,30,true,-1.7,true,60,15,1.7,.002); }
    private static Config cap(double cap) { return new Config(15,-45,30,true,-1.7,false,60,cap,1.7,.002); }
    private static Vec3 vector(Random random, double range) {
        return new Vec3((random.nextDouble()*2-1)*range,(random.nextDouble()*2-1)*range,(random.nextDouble()*2-1)*range);
    }
    private static double pitch(Vec3 look) { return Math.toDegrees(-Math.asin(look.y)); }
    private static void near(double actual, double expected, String message) { check(Math.abs(actual-expected)<1e-8, message + ": " + actual); }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
