package dev.guizhou.util.elytra;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Executor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class AsyncFlightRouteTest {
    private static final AABB BODY = new AABB(-0.3, -0.02, -0.3, 0.3, 0.62, 0.3);
    private static final Vec3 GOAL = new Vec3(10, 0, 0);
    private static final List<AABB> WALL = List.of(new AABB(3, -30, -3, 4, 30, 3));
    private static int checks;

    public static void main(String[] args) {
        directAndAsynchronousWork();
        freshnessAndInvalidation();
        nullCapturePhase();
        traverse(WALL, GOAL, "Single wall");
        traverse(List.of(new AABB(3, -30, -4, 4, 30, 4), new AABB(3, -30, -4, 9, 30, -3)),
                new Vec3(12, 0, 0), "Two-segment corner");
        traverse(List.of(new AABB(8, -30, -2, 16, 30, 2)), new Vec3(24, 0, 0), "Thick wall crossing snapshot boundary");
        System.out.println("AsyncFlightRouteTest passed: " + checks + " checks");
    }

    private static void directAndAsynchronousWork() {
        QueueExecutor executor = new QueueExecutor();
        AsyncFlightRoute route = new AsyncFlightRoute(executor);
        int[] captures = {0};
        FlightRoutePlanner.Result direct = route.routeTo(Vec3.ZERO, GOAL, 8, 0,
                () -> { captures[0]++; return null; }, (from, to) -> true);
        check(!direct.blocked() && !direct.avoiding(), "Open route returns immediately");
        check(captures[0] == 0 && executor.jobs.isEmpty(), "Open route requires no snapshot or background calculation");
        FlightCollisionSnapshot scene = snapshot(Vec3.ZERO, 40, WALL);
        FlightRoutePlanner.Clearance live = (from, to) -> {
            check(!executor.running, "Worker must not call live world clearance");
            return scene.clear(from, to);
        };
        var pending = route.routeTo(Vec3.ZERO, GOAL, 8, 1, () -> snapshot(Vec3.ZERO, 12, WALL), live);
        check(pending.blocked() && executor.jobs.size() == 1, "Blocked route queues work and does not run A-star synchronously");
        executor.runAll();
        var adopted = route.routeTo(Vec3.ZERO, GOAL, 8, 2, () -> null, live);
        check(adopted.avoiding() && !adopted.blocked(), "Fresh background path passes live validation");
        var retained = route.routeTo(Vec3.ZERO, GOAL, 8, 8, () -> null, live);
        check(!retained.blocked() && retained.direction().dot(adopted.direction()) > 0.999,
                "Validated waypoint survives result TTL and submission cooldown");
        check(executor.jobs.isEmpty(), "Following an unchanged path does not repeatedly search");
        var obstructed = route.routeTo(Vec3.ZERO, GOAL, 8, 9, () -> null, (from, to) -> false);
        check(obstructed.blocked(), "New live obstacle invalidates retained path immediately");
    }

    private static void freshnessAndInvalidation() {
        QueueExecutor executor = new QueueExecutor();
        FlightCollisionSnapshot scene = snapshot(Vec3.ZERO, 40, WALL);
        AsyncFlightRoute route = new AsyncFlightRoute(executor);
        route.routeTo(Vec3.ZERO, GOAL, 8, 0, () -> snapshot(Vec3.ZERO, 12, WALL), scene);
        executor.runAll();
        check(route.routeTo(Vec3.ZERO, GOAL, 8, 3, () -> null, scene).blocked(), "Result older than two ticks is not adopted");
        route.reset();
        route.routeTo(Vec3.ZERO, GOAL, 8, 4, () -> snapshot(Vec3.ZERO, 12, WALL), scene);
        route.invalidate();
        executor.runAll();
        check(route.routeTo(Vec3.ZERO, GOAL, 8, 5, () -> null, scene).blocked(), "Session reset discards queued calculation");
        route.routeTo(Vec3.ZERO, GOAL, 8, 6, () -> snapshot(Vec3.ZERO, 12, WALL), scene);
        executor.runAll();
        var reverse = route.routeTo(Vec3.ZERO, new Vec3(-10, 0, 0), 8, 7, () -> null, scene);
        check(!reverse.blocked() && !reverse.avoiding() && reverse.direction().x < -0.99,
                "Changed target cannot adopt old direction");
        check(route.routeTo(Vec3.ZERO, new Vec3(Double.NaN, 0, 0), 8, 8, () -> null, scene).blocked(),
                "Invalid target fails closed");
    }

    private static void nullCapturePhase() {
        QueueExecutor executor = new QueueExecutor();
        AsyncFlightRoute route = new AsyncFlightRoute(executor);
        FlightCollisionSnapshot scene = snapshot(Vec3.ZERO, 40, WALL);
        for (int tick = 1; tick <= 4; tick++) {
            int current = tick;
            route.routeTo(Vec3.ZERO, GOAL, 8, tick,
                    () -> current % 4 == 0 ? snapshot(Vec3.ZERO, 12, WALL) : null, scene);
        }
        check(executor.jobs.size() == 1, "Null cache phase cannot permanently miss provider's capture interval");
        executor.runAll();
        check(!route.routeTo(Vec3.ZERO, GOAL, 8, 5, () -> null, scene).blocked(), "First available shared capture yields a usable route");
    }

    private static void traverse(List<AABB> obstacles, Vec3 goal, String context) {
        QueueExecutor executor = new QueueExecutor();
        AsyncFlightRoute route = new AsyncFlightRoute(executor);
        FlightCollisionSnapshot live = snapshot(Vec3.ZERO, 100, obstacles);
        Vec3 position = Vec3.ZERO;
        int captures = 0;
        for (int tick = 0; tick < 240 && position.distanceTo(goal) > 0.65; tick++) {
            Vec3 capturedOrigin = position;
            int[] captureCounter = {0};
            var result = route.routeTo(position, goal, 12, tick, () -> {
                captureCounter[0]++;
                return snapshot(capturedOrigin, 12, obstacles);
            }, live);
            captures += captureCounter[0];
            executor.runAll();
            if (result.blocked()) continue;
            Vec3 next = position.add(result.direction().scale(Math.min(0.5, result.distance())));
            check(live.clear(position, next), context + ": every moved segment is collision-free");
            position = next;
        }
        check(position.distanceTo(goal) <= 0.65, context + ": reach target, actual=" + position);
        check(captures <= 6, context + ": complete waypoint path prevents searching each corner, captures=" + captures);
    }

    private static FlightCollisionSnapshot snapshot(Vec3 origin, double radius, List<AABB> obstacles) {
        return new FlightCollisionSnapshot(origin, BODY, new AABB(origin.x - radius, origin.y - radius, origin.z - radius,
                origin.x + radius, origin.y + radius, origin.z + radius), obstacles);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class QueueExecutor implements Executor {
        final ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        boolean running;
        @Override public void execute(Runnable command) { jobs.addLast(command); }
        void runAll() {
            running = true;
            try { while (!jobs.isEmpty()) jobs.removeFirst().run(); }
            finally { running = false; }
        }
    }
}
