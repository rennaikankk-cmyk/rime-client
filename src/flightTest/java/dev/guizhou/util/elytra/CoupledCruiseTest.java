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
        acceleratedCruise();
        acceleratedClimb();
        boostModeSelection();
        longWaveWindow();
        forecastDoesNotAdvanceState();
        combatHeading();
        System.out.println("CoupledCruiseTest passed: " + checks + " checks");
    }

    private static void combatHeading() {
        Random random = new Random(9028);
        Config config = Config.defaults();
        for (int i = 0; i < 500; i++) {
            Vec3 previous = new Vec3(random.nextDouble()*8-4, random.nextDouble()*8-4, random.nextDouble()*8-4);
            Vec3 request = new Vec3(random.nextDouble()*8-4, random.nextDouble()*8-4, random.nextDouble()*8-4);
            Vec3 look = request.normalize();
            Vec3 simulated = ElytraPhysics.simulateGlide(previous, look, .08);
            Envelope box = CoupledCruise.envelope(config, previous, simulated, look, look);
            for (boolean precise : new boolean[]{false,true}) {
                Result result = CoupledCruise.applyCombat(config, request, previous, simulated, look, look, precise, precise);
                check(!result.feasible() || box.contains(result.velocity(),1e-8), "Combat stays in measured envelope");
                check(!result.feasible() || result.velocity().length() <= config.speedCap()+1e-8, "Combat respects cruise cap");
            }
        }
        CoupledCruise controller = new CoupledCruise();
        Vec3 climb = new Vec3(1, 3, 1);
        check(controller.selectCombat(config, climb, 100, false, false, false) == null, "No dual without protocol support");
        check(controller.selectCombat(config, climb, 100, true, true, false) == null, "Precision cancels dual");
        check(controller.selectCombat(config, climb, 100, true, false, true) == null, "Defense direction cancels dual");
        Selection selection = controller.selectCombat(config, climb, 100, true, false, false);
        check(selection != null && selection.dualActive(), "Climb uses actual dual selection");
        Selection gentle = new CoupledCruise().selectCombat(config, climb, 100, true, false, false, BoostMode.Gentle);
        check(gentle != null && gentle.look().y < selection.look().y
                        && gentle.boxLook().distanceTo(selection.boxLook()) > .1,
                "Combat respects the configured gentle climb pitch and banking span");
        Vec3 simulated = ElytraPhysics.simulateGlide(Vec3.ZERO, selection.look(), .08);
        Result dual = CoupledCruise.applyCombat(config, climb, Vec3.ZERO, simulated,
                selection.look(), selection.boxLook(), false, false);
        check(dual.feasible() && CoupledCruise.envelope(config, Vec3.ZERO, simulated,
                selection.look(), selection.boxLook()).contains(dual.velocity(), 1e-8), "Dual combat stays inside published views");
        Vec3 retained = controller.selectCombat(config, new Vec3(.95,3,1.05), 102, true,false,false).look();
        check(Math.abs(retained.z) < 1e-8, "Small bearing jitter retains the climb axis");
        Vec3 turned = controller.selectCombat(config, new Vec3(.1,3,1), 104, true,false,false).look();
        check(Math.abs(turned.x) < 1e-8, "Climb turns when the old axis exceeds 60 degrees");
        controller.reset();
        Vec3 resetHeading = controller.selectCombat(config, new Vec3(.9,3,1), 100, true,false,false).look();
        check(Math.abs(resetHeading.x) < 1e-8, "New flight does not retain the previous climb bearing");
        Vec3 small = new Vec3(.1,0,0);
        var exact = CoupledCruise.applyCombat(config, small, Vec3.ZERO, Vec3.ZERO,
                new Vec3(1,0,0),new Vec3(1,0,0),true,true);
        check(exact.feasible() && exact.velocity().distanceTo(small)<1e-9, "Terminal request is not accelerated past its intercept");
    }

    private static void forecastDoesNotAdvanceState() {
        Config cfg = new Config(15, -45, 30, true, -1.7, true, 1000, 14.5, 1.7, .002);
        CoupledCruise live = new CoupledCruise(), control = new CoupledCruise();
        for (int i = 0; i < 200; i++) {
            Intent input = new Intent(NORTH, i < 60 ? -1 : i < 100 ? 1 : 0, false, true);
            double y = 2500 - i * 12;
            Selection expected = control.select(cfg, input, 0, y, true);
            for (int probe = 0; probe < 4; probe++) {
                check(live.previewSelection(cfg, input, 0, y, true).equals(expected),
                        "repeated combat previews preserve bank and wave phase");
            }
            check(live.select(cfg, input, 0, y, true).equals(expected), "actual movement unchanged by preview");
        }
    }

    private static void longWaveWindow() {
        Config cfg = new Config(15, -45, 30, true, -1.7, true, 1000, 14.5, 1.7, .002);
        check(cfg.heightWindow() == 1000, "long wave must not silently become a 256-block window");
        CoupledCruise controller = new CoupledCruise();
        Intent cruise = new Intent(NORTH, 0, false, true, BoostMode.Aggressive);
        check(controller.select(cfg, cruise, 0, 2500, true).phase() == Phase.DESCEND, "wave starts descending");
        check(controller.select(cfg, cruise, 0, 2000, true).phase() == Phase.DESCEND, "halfway is still descent");
        check(controller.select(cfg, cruise, 0, 1500, true).phase() == Phase.CLIMB, "wave turns at the full window");
        check(controller.select(cfg, cruise, 0, 2501, true).phase() == Phase.DESCEND, "wave completes its cycle");
        check(controller.select(cfg, cruise, 0, 1500, true).phase() == Phase.CLIMB, "overshoot does not raise the next floor");
        check(controller.select(cfg, new Intent(NORTH, -1, false, true), 0, 1800, true).phase() == Phase.LEVEL,
                "manual descent takes over from the automatic wave");
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

    private static void acceleratedCruise() {
        Config cfg = Config.defaults();
        CoupledCruise c = new CoupledCruise();
        Intent fast = new Intent(NORTH, 0, false, true);
        Selection unsupported = c.select(cfg, fast, 0, 200, false);
        check(!unsupported.dualActive() && unsupported.vertical() == 0, "unsupported protocol retains ordinary level flight");
        check(!c.select(cfg, new Intent(Vec3.ZERO, 1, false, true), 0, 200, true).dualActive(), "GZBoost does not alter pure vertical input");
        Selection up = c.select(cfg, fast, 0, 200, true);
        check(up.dualActive() && up.vertical() == 1 && !up.banked(), "accelerated level starts its altitude cycle");
        check(c.select(cfg, fast, 0, 202, true).vertical() == -1, "cycle returns toward entry altitude");
        Selection ordinary = c.select(cfg, new Intent(NORTH, 0, false), 0, 202, true);
        check(ordinary.vertical() == 0 && !ordinary.dualActive(), "disabling GZBoost restores ordinary cruise immediately");
        check(c.select(cfg, fast, 0, 220, true).vertical() == 1, "reentering acceleration anchors the new altitude");
        c.reset();
        check(c.select(cfg, fast, 0, -100, true).vertical() == 1, "world reset discards accelerated altitude");
        Selection left = c.select(cfg, new Intent(NORTH, -1, false, true), 0, 200, true);
        Selection right = c.select(cfg, new Intent(NORTH, -1, false, true), 0, 198, true);
        check(left.banked() && right.banked() && left.boxLook().x * right.boxLook().x < 0,
                "cardinal descent alternates bounded transverse support");
        check(!c.select(cfg, new Intent(new Vec3(1,0,1), -1, false, true), 0, 200, true).banked(),
                "noncardinal headings are not silently redirected for banking");
        Vec3 velocity = Vec3.ZERO;
        double height = 6000;
        for (int i = 0; i < 800; i++) {
            Selection selection = c.select(cfg, new Intent(NORTH,-1,false,true), 0, height, true);
            Vec3 simulated = ElytraPhysics.simulateGlide(velocity, selection.look(), .08);
            Result result = c.apply(cfg, selection, velocity, simulated, selection.look(), selection.look());
            Envelope box = CoupledCruise.envelope(cfg, velocity, simulated, selection.look(), selection.boxLook());
            check(result.feasible() && box.contains(result.velocity(), 1e-8), "accelerated result remains inside the predicted envelope");
            check(result.velocity().length() <= cfg.speedCap() + 1e-8, "acceleration retains the total speed limit");
            velocity = result.velocity();
            height += velocity.y;
        }
        check(velocity.z > 13 && velocity.y < 0 && Math.abs(velocity.x) < 1,
                "model gains sustained forward speed with explicit altitude cost and bounded banking");
    }

    private static void acceleratedClimb() {
        Config cfg = cap(15);
        for (BoostMode mode : BoostMode.values()) {
            double cardinalY = Double.NaN, diagonalY = Double.NaN;
            for (int angle = 0; angle < 360; angle += 45) {
                CoupledCruise c = new CoupledCruise();
                double yaw = Math.toRadians(angle);
                Vec3 direction = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
                Vec3 v = Vec3.ZERO;
                for (int tick = 0; tick < 600; tick++) {
                    Selection choice = c.select(cfg, new Intent(direction, 1, false, true, mode), angle, 200, true);
                    Vec3 predicted = ElytraPhysics.simulateGlide(v, choice.look(), .08);
                    Result result = c.apply(cfg, choice, v, predicted, choice.look(), choice.look());
                    Envelope envelope = CoupledCruise.envelope(cfg, v, predicted, choice.look(), choice.boxLook());
                    check(result.feasible() && envelope.contains(result.velocity(), 1e-8), "boosted climb stays inside every envelope axis");
                    check(result.velocity().length() <= 15 + 1e-8, "boosted climb retains total speed bound");
                    v = result.velocity();
                }
                check(v.y > (mode == BoostMode.Gentle ? 7 : 10), "powered directional climb sustains the intended model gain");
                if (angle % 90 == 0) {
                    if (Double.isNaN(cardinalY)) cardinalY = v.y;
                    check(Math.abs(v.y - cardinalY) < .005, "all cardinal axes have equivalent climb, including tiny sin/cos residues");
                } else {
                    if (Double.isNaN(diagonalY)) diagonalY = v.y;
                    check(Math.abs(v.y - diagonalY) < .005, "all diagonal quadrants have equivalent climb");
                }
                Selection off = c.select(cfg, new Intent(direction, 1, false, false, mode), angle, 200, true);
                check(!off.banked(), "switching GZBoost off leaves the ordinary climb path");
                check(!c.select(cfg, new Intent(direction, 1, false, true, mode), angle, 200, false).dualActive(),
                        "unsupported protocol cannot select boosted dual climb");
            }
            CoupledCruise turning = new CoupledCruise();
            Vec3 v = Vec3.ZERO;
            for (int tick = 0; tick < 720; tick++) {
                double yaw = Math.toRadians(tick * .5);
                Vec3 direction = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
                Selection choice = turning.select(cfg, new Intent(direction, 1, false, true, mode), tick * .5, 200, true);
                Vec3 predicted = ElytraPhysics.simulateGlide(v, choice.look(), .08);
                Result result = turning.apply(cfg, choice, v, predicted, choice.look(), choice.look());
                check(result.feasible() && result.velocity().length() <= 15 + 1e-8,
                        "heading transitions remain feasible across both blend regions and all quadrants");
                v = result.velocity();
            }
        }
    }

    private static void boostModeSelection() {
        Config cfg = cap(15);
        check(new Intent(NORTH, 1, false, true).boostMode() == BoostMode.Aggressive,
                "existing accelerated callers retain the released aggressive behavior");
        check(new Intent(NORTH, 1, false, true, null).boostMode() == BoostMode.Aggressive,
                "missing boost mode uses the aggressive default");
        CoupledCruise switching = new CoupledCruise();
        for (BoostMode mode : new BoostMode[]{BoostMode.Aggressive, BoostMode.Gentle, BoostMode.Aggressive}) {
            Selection selected = switching.select(cfg, new Intent(NORTH, 1, false, true, mode), 0, 200, true);
            near(pitch(selected.look()), mode == BoostMode.Gentle ? -86.25 : -89.25,
                    "live mode changes use the measured candidate pitch");
            near(Math.abs(Math.toDegrees(Math.atan2(-selected.boxLook().x, selected.boxLook().z))),
                    mode == BoostMode.Gentle ? 20 : 72.5, "live mode changes select the measured lateral span");
            Selection vertical = switching.select(cfg, new Intent(Vec3.ZERO, 1, false, true, mode), 0, 200, true);
            check(vertical.pureVertical() && vertical.look().x == 0 && vertical.look().z == 0,
                    "neither profile adds horizontal motion to vertical input");
        }
        for (Vec3 heading : new Vec3[]{NORTH, new Vec3(1, 0, 1)}) {
            for (int vertical : new int[]{-1, 0}) {
                Selection gentle = new CoupledCruise().select(cfg, new Intent(heading, vertical, false, true, BoostMode.Gentle), 0, 200, true);
                Selection aggressive = new CoupledCruise().select(cfg, new Intent(heading, vertical, false, true, BoostMode.Aggressive), 0, 200, true);
                check(gentle.equals(aggressive), "boost modes share the validated cruise and descent behavior");
            }
        }
        Selection gentleOff = new CoupledCruise().select(cfg, new Intent(NORTH, 1, false, false, BoostMode.Gentle), 0, 200, true);
        Selection ordinary = new CoupledCruise().select(cfg, new Intent(NORTH, 1, false), 0, 200, true);
        check(gentleOff.equals(ordinary), "a saved gentle profile cannot affect disabled GZBoost");
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
