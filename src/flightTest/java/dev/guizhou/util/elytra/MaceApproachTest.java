package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;

/** Geometry fixtures exercise intercept feasibility, direction changes and receding-horizon pursuit. */
public final class MaceApproachTest {
    private static int checks;

    public static void main(String[] args) {
        minimumSmashHeight();
        stationaryRecovery();
        descendingCatchUp();
        Vec3 overhead = new Vec3(0.06, 90.0, 0.03);
        Vec3 angled = MaceApproach.pullUpIntent(overhead, 45.0);
        close(6.0, Math.min(6.0, angled.length()),
                "A near-overhead target must not collapse a six-block climb request to horizontal error");
        close(overhead.length(), angled.length(), "Pitch override preserves requested travel distance");
        close(angled.y, angled.horizontalDistance(), "45 degree climb has equal vertical and horizontal components");
        check(MaceApproach.pullUpIntent(new Vec3(0, 5, 0), 45).equals(new Vec3(0, 5, 0)),
                "An exactly vertical intent keeps its heading when no horizontal direction exists");
        check(MaceApproach.pullUpIntent(overhead, 0).equals(overhead), "Disabled pitch override preserves intent");
        check(MaceApproach.pullUpIntent(overhead, Double.NaN).equals(overhead), "Invalid pitch cannot corrupt intent");
        var verticalMeeting = MaceApproach.climb(Vec3.ZERO, new Vec3(0, 8, 0),
                new Vec3(0, 1, 0), 3, 6, 1.5, 0, true);
        close(21.0, verticalMeeting.aim().y,
                "Rising target apex uses climb arrival time, not the end of the entire pass");
        close(8.5, verticalMeeting.ticks(),
                "Seven ticks up plus one and a half down includes target rise during descent");
        close(3.0, time(new Vec3(6, 0, 0), Vec3.ZERO, 2, 0), "stationary");
        close(6.0, time(new Vec3(6, 0, 0), new Vec3(1, 0, 0), 2, 0), "fleeing");
        close(1.5, time(new Vec3(6, 0, 0), new Vec3(-2, 0, 0), 2, 0), "equal-speed meeting");
        close(10.0, time(new Vec3(0, 10, 0), Vec3.ZERO, 2, 10), "climb and drop both count");
        check(!Double.isFinite(time(new Vec3(6, 0, 0), new Vec3(3, 0, 0), 2, 0)), "faster target is unreachable");
        Vec3 offset = new Vec3(12, -7, 3), moving = new Vec3(0.9, 0.4, 0.6);
        double intercept = time(offset, moving, 2, 0);
        close(2 * intercept, offset.add(moving.scale(intercept)).length(), "diagonal rising intercept meets target");
        double oldLead = AerialIntercept.leadTicks(offset, moving, 2, 0, true);
        Vec3 oldDirection = offset.add(moving.scale(oldLead)).normalize();
        check(oldDirection.scale(2 * intercept).distanceTo(offset.add(moving.scale(intercept))) > 1.0,
                "four-tick cap reproduces a geometric miss in the diagonal fixture");

        var plan = MaceApproach.climb(Vec3.ZERO, new Vec3(6, 0, 0), new Vec3(0.8, 0, 0),
                2, 10, 1.5, 0, true);
        check(plan.reachable() && plan.height() < 10 && plan.height() >= 3,
                "a fleeing target gets an affordable lower apex");
        double climbTime = plan.aim().length() / 2;
        double descentTime = plan.height() / Math.sqrt(4 - 0.8 * 0.8);
        close(climbTime + descentTime, plan.ticks(), "both legs fit the round-trip budget");
        close(6 + 0.8 * climbTime, plan.aim().x, "apex meets the target at climb arrival");
        var impossible = MaceApproach.climb(Vec3.ZERO, new Vec3(10, 0, 0), new Vec3(3, 0, 0),
                2, 40, 1.5, 0, true);
        check(!impossible.reachable() && impossible.aim().y == 0 && impossible.aim().x > 10,
                "do not spend speed on another climb behind a faster horizontal target");
        var rising = MaceApproach.climb(Vec3.ZERO, new Vec3(10, 2, 0), new Vec3(3, 0.2, 0),
                2, 40, 1.5, 0, true);
        check(!rising.reachable() && rising.aim().y < 3, "failed round trip follows rise without adding forty blocks");
        var turned = MaceApproach.intercept(Vec3.ZERO, new Vec3(10, 0, 0), new Vec3(0, 0, 1), 2, 0, false);
        check(!turned.reachable() && turned.aim().z <= 2, "a turn immediately limits speculative lead");
        check(MaceApproach.stable(Vec3.ZERO, Vec3.ZERO, true), "stationary history is stable");
        check(!MaceApproach.stable(new Vec3(1, 0, 0), new Vec3(0, 0, 1), true), "a dodge invalidates long lead");
        check(!MaceApproach.stable(new Vec3(2, 0, 0), new Vec3(1, 0, 0), true), "acceleration invalidates long lead");
        check(!MaceApproach.stable(new Vec3(1, 0, 0), new Vec3(1, 0, 0), false), "first sample is uncertain");
        var invalid = MaceApproach.climb(Vec3.ZERO, offset, moving, Double.NaN, 10, 1.5, 1, true);
        check(!invalid.reachable() && invalid.aim().equals(Vec3.ZERO), "bad speed cannot issue motion");

        // Replan moving targets rather than testing only the first quadratic result.
        for (Vec3 velocity : new Vec3[]{Vec3.ZERO, new Vec3(0.8, 0, 0), new Vec3(0.6, 0.2, 0.8)}) {
            Vec3 self = new Vec3(0, 12, 0), target = new Vec3(10, 0, 0);
            double closest = Double.POSITIVE_INFINITY;
            for (int tick = 0; tick < 20; tick++) {
                var strike = MaceApproach.intercept(self, target, velocity, 2, 0, true);
                Vec3 error = strike.aim().subtract(self);
                self = self.add(error.normalize().scale(Math.min(2, error.length())));
                target = target.add(velocity);
                closest = Math.min(closest, self.distanceTo(target));
            }
            check(closest < 1.0, "replanned descent closes a reachable moving-target pass: " + velocity);
        }
        for (int i = 1; i <= 500; i++) {
            Vec3 velocity = new Vec3(i % 7 * 0.13, i % 3 * 0.11, i % 5 * 0.09);
            var p = MaceApproach.climb(Vec3.ZERO, new Vec3(5 + i % 23, i % 9, 2), velocity,
                    2.4, 4 + i % 40, 1.5, i % 4, true);
            check(Double.isFinite(p.aim().lengthSqr())
                            && (velocity.lengthSqr() < 1.0E-8 || p.ticks() <= 20) && p.ticks() >= 0,
                    "bounded finite plan");
        }
        for (Vec3 velocity : new Vec3[]{new Vec3(0.8, 0, 0), new Vec3(0.6, 0.2, 0.8)}) {
            int legacy = approachTicks(velocity, false);
            int adaptive = approachTicks(velocity, true);
            check(adaptive > 0 && adaptive < legacy,
                    "adaptive climb must shorten the complete climb/descent path: " + legacy + " -> " + adaptive);
            System.out.println("Constant-speed geometric fixture " + velocity + ": fixed climb "
                    + legacy + " ticks, adaptive climb " + adaptive + " ticks (not a server benchmark)");
        }
        System.out.println("MaceApproachTest passed: " + checks + " checks");
    }

    private static void minimumSmashHeight() {
        // Both sliders accept these combinations. A reachable apex below the
        // configured fall requirement cannot produce the requested smash.
        for (double preferred : new double[]{1.0, 2.0, 10.0}) {
            for (double minimumFall : new double[]{0.0, 1.5, 8.0, 20.0}) {
                double minimumHeight = Math.max(3.0, minimumFall + 1.5);
                for (Vec3 velocity : new Vec3[]{Vec3.ZERO, new Vec3(0.25, 0, 0)}) {
                    var plan = MaceApproach.climb(Vec3.ZERO, new Vec3(3, 0, 0), velocity,
                            2.0, preferred, minimumFall, 0, true);
                    check(!plan.reachable() || plan.height() >= minimumHeight,
                            "A reachable climb must leave room for the configured smash fall: preferred="
                                    + preferred + " minimumFall=" + minimumFall + " actual=" + plan.height());
                    if (velocity.equals(Vec3.ZERO)) {
                        check(plan.reachable(), "A stationary target permits the required charge height");
                        check(plan.aim().y >= minimumHeight,
                                "The steering apex must use the same charge floor as the plan metadata");
                    }
                }
            }
        }
    }

    private static void descendingCatchUp() {
        for (double altitude : new double[]{40, 100, 200}) {
            for (double downward : new double[]{-.5, -2, -4}) {
                Vec3 self = new Vec3(0, altitude, 0), target = new Vec3(3, 0, 0);
                var plan = MaceApproach.climb(self, target, new Vec3(0, downward, 0),
                        2.4, 10, 1.5, 0, false);
                check(!plan.reachable(), "Distant falling target has no immediate round-trip intercept");
                check(plan.aim().y < self.y, "Catch-up must descend from an abandoned apex");
                close(target.y + downward * 2 + 10, plan.aim().y,
                        "Catch-up retains charge room above the bounded predicted target");
            }
        }
        var level = MaceApproach.climb(Vec3.ZERO, new Vec3(10, 0, 0), new Vec3(3, 0, 0),
                2, 10, 1.5, 0, true);
        close(0, level.aim().y, "Level catch-up does not manufacture a climb");
    }

    private static void stationaryRecovery() {
        for (double speed : new double[]{0.5, 1.0, 2.0}) {
            for (double height : new double[]{10, 64}) {
                Vec3 self = new Vec3(0, 0, 0), target = new Vec3(1, 0, 0);
                var plan = MaceApproach.climb(self, target, Vec3.ZERO, speed, height, 1.5, 0, true);
                check(plan.reachable(), "A stationary nearby target must not trap a slow player in horizontal catch-up");
                close(height, plan.height(), "A stationary target does not require sacrificing smash height");
                close(height, plan.aim().y, "Low-altitude recovery must request a real climb");
                for (int tick = 0; tick < 160 && self.y < height - 0.1; tick++) {
                    plan = MaceApproach.climb(self, target, Vec3.ZERO, speed, height, 1.5, 0, true);
                    Vec3 offset = plan.aim().subtract(self);
                    self = self.add(offset.normalize().scale(Math.min(speed, offset.length())));
                }
                check(self.y >= height - 0.1, "Repeated replanning must complete the stationary-target climb");
            }
        }
    }

    private static int approachTicks(Vec3 velocity, boolean adaptive) {
        Vec3 self = Vec3.ZERO, target = new Vec3(10, 0, 0);
        boolean climbing = true;
        double height = 10;
        for (int tick = 0; tick < 200; tick++) {
            Vec3 aim;
            if (climbing) {
                var plan = MaceApproach.climb(self, target, velocity, 2, height, 1.5, 0, true);
                if (adaptive && plan.reachable()) height = plan.height();
                aim = adaptive ? plan.aim() : target.add(velocity.scale(4)).add(0, 10, 0);
                if (self.y >= target.y + (adaptive ? plan.height() - 0.1 : 10)
                        && (!adaptive || plan.reachable())) climbing = false;
            } else {
                aim = adaptive ? MaceApproach.intercept(self, target, velocity, 2, 0, true).aim()
                        : target.add(velocity.scale(4));
            }
            Vec3 error = aim.subtract(self);
            Vec3 step = error.normalize().scale(Math.min(2, error.length()));
            self = self.add(step);
            target = target.add(velocity);
            if (!climbing && step.y < -0.05 && self.distanceTo(target) < 2.5) return tick + 1;
        }
        return 200;
    }

    private static double time(Vec3 offset, Vec3 velocity, double speed, double extra) {
        return MaceApproach.interceptTime(offset, velocity, speed, extra);
    }

    private static void close(double expected, double actual, String name) {
        check(Math.abs(expected - actual) < 1.0E-7, name + ": " + actual);
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
