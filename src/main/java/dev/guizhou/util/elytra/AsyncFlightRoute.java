package dev.guizhou.util.elytra;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Main-thread route coordinator. Workers receive only immutable numeric snapshots. */
public final class AsyncFlightRoute {
    private static final int SUBMIT_TICKS = 4;
    private static final int MAX_RESULT_AGE = 2;
    private static final double REACHED = 0.65;
    private final FlightPlanningTask<Request, List<Vec3>> task;
    private long previousTick = Long.MIN_VALUE;
    private long submittedTick = Long.MIN_VALUE / 2;
    private FlightPlanningTask.Completed<Request, List<Vec3>> consumed;
    private Request waypointRequest;
    private final ArrayDeque<Vec3> waypoints = new ArrayDeque<>();
    private Vec3 segmentStart;

    public AsyncFlightRoute() {
        task = new FlightPlanningTask<>(AsyncFlightRoute::calculate);
    }

    AsyncFlightRoute(Executor executor) {
        task = new FlightPlanningTask<>(AsyncFlightRoute::calculate, executor);
    }

    /** snapshots owns capture throttling; null may mean another planner captured on a different tick. */
    public FlightRoutePlanner.Result routeTo(Vec3 position, Vec3 goal, double lookahead, long tick,
                                             Supplier<FlightCollisionSnapshot> snapshots,
                                             FlightRoutePlanner.Clearance live) {
        if (!finite(position) || !finite(goal) || !Double.isFinite(lookahead)
                || snapshots == null || live == null) {
            reset();
            return stopped(false);
        }
        if (tick < previousTick) reset();
        previousTick = tick;
        Vec3 displacement = goal.subtract(position);
        double distance = displacement.length();
        if (!Double.isFinite(distance) || distance < 1.0E-6) {
            reset();
            return stopped(false);
        }
        double horizon = Math.min(distance, Math.clamp(lookahead, 8.0, 32.0));
        Vec3 direct = position.add(displacement.scale(horizon / distance));
        if (waypointRequest != null && !sameTarget(waypointRequest, goal)) invalidatePlanning();

        FlightPlanningTask.Completed<Request, List<Vec3>> completed = task.latest();
        if (completed != null && completed != consumed) {
            consumed = completed;
            Request request = completed.input();
            if (!completed.output().isEmpty() && tick >= request.tick && tick - request.tick <= MAX_RESULT_AGE
                    && position.distanceToSqr(request.position) <= 64.0 && sameTarget(request, goal)
                    && live.clear(position, completed.output().getFirst())) {
                waypoints.clear();
                waypoints.addAll(completed.output());
                waypointRequest = request;
                segmentStart = request.position;
            }
        }

        while (!waypoints.isEmpty()) {
            Vec3 waypoint = waypoints.peekFirst();
            Vec3 segment = waypoint.subtract(segmentStart);
            Vec3 remaining = waypoint.subtract(position);
            if (remaining.lengthSqr() <= REACHED * REACHED || remaining.dot(segment) <= 0.0) {
                if (waypoints.size() > 1 && remaining.lengthSqr() > 1e-8) {
                    var points = waypoints.iterator();
                    points.next();
                    if (!live.clear(position, points.next())) {
                        if (live.clear(position, waypoint)) return toward(position, waypoint, true);
                        waypoints.clear();
                        break;
                    }
                }
                segmentStart = waypoints.removeFirst();
                continue;
            }
            if (!live.clear(position, waypoint)) {
                waypoints.clear();
            } else if (tick - waypointRequest.tick < SUBMIT_TICKS || !live.clear(position, direct)) {
                // Retain the whole detour across corners, validating each current leg against the live world.
                return toward(position, waypoint, true);
            }
            break;
        }
        if (live.clear(position, direct)) {
            if (waypointRequest != null) invalidatePlanning();
            return toward(position, direct, false);
        }
        if (tick - submittedTick >= SUBMIT_TICKS) {
            FlightCollisionSnapshot snapshot = snapshots.get();
            if (snapshot != null) {
                Vec3 localGoal = clippedGoal(snapshot.navigationBounds(), position, goal,
                        Math.min(distance, Math.min(32.0, horizon + 8.0)));
                if (localGoal != null) {
                    task.submit(new Request(position, goal, localGoal, horizon, tick, snapshot));
                    submittedTick = tick;
                }
            }
        }
        return stopped(true);
    }

    public void reset() {
        invalidatePlanning();
        previousTick = Long.MIN_VALUE;
    }

    public void invalidate() {
        reset();
    }

    private void invalidatePlanning() {
        task.invalidate();
        consumed = null;
        waypointRequest = null;
        waypoints.clear();
        segmentStart = null;
        submittedTick = Long.MIN_VALUE / 2;
    }

    private static List<Vec3> calculate(Request request) {
        List<Vec3> directPlan = planTo(request, request.localGoal);
        if (!directPlan.isEmpty() || request.localGoal.distanceToSqr(request.goal) < 1.0E-6) return directPlan;
        Vec3 heading = request.goal.subtract(request.position).normalize();
        Vec3 side = new Vec3(-heading.z, 0.0, heading.x).normalize();
        if (side.lengthSqr() < 1.0E-6) side = new Vec3(1.0, 0.0, 0.0);
        AABB bounds = request.snapshot.navigationBounds();
        List<Vec3> candidates = new ArrayList<>(5);
        for (double amount : new double[]{4.0, -4.0, 12.0, -12.0}) {
            candidates.add(clamp(request.localGoal.add(side.scale(amount)), bounds));
        }
        candidates.add(clamp(request.localGoal.add(0.0, 4.0, 0.0), bounds));
        candidates.sort(Comparator.comparingDouble(candidate -> candidate.distanceToSqr(request.goal)));
        for (Vec3 candidate : candidates) {
            if (candidate.subtract(request.position).dot(heading) < 1.0
                    || !request.snapshot.clear(candidate, candidate)) continue;
            List<Vec3> plan = planTo(request, candidate);
            if (!plan.isEmpty()) return plan;
        }
        return List.of();
    }

    private static List<Vec3> planTo(Request request, Vec3 goal) {
        FlightRoutePlanner planner = new FlightRoutePlanner();
        FlightRoutePlanner.Result result = planner.routeTo(request.position, goal,
                Math.max(request.lookahead, request.position.distanceTo(goal)), request.tick, request.snapshot);
        if (result.blocked()) return List.of();
        List<Vec3> planned = planner.plannedWaypoints();
        return planned.isEmpty() ? List.of(request.position.add(result.direction().scale(result.distance()))) : planned;
    }

    private static Vec3 clamp(Vec3 point, AABB bounds) {
        return new Vec3(Math.clamp(point.x, bounds.minX + 0.001, bounds.maxX - 0.001),
                Math.clamp(point.y, bounds.minY + 0.001, bounds.maxY - 0.001),
                Math.clamp(point.z, bounds.minZ + 0.001, bounds.maxZ - 0.001));
    }

    private static boolean sameTarget(Request request, Vec3 goal) {
        if (goal.distanceToSqr(request.goal) > Math.pow(Math.max(6.0, request.lookahead * 0.5), 2)) return false;
        Vec3 initial = request.goal.subtract(request.position);
        Vec3 current = goal.subtract(request.position);
        return current.lengthSqr() > 1.0E-9 && initial.normalize().dot(current.normalize()) >= 0.8;
    }

    private static Vec3 clippedGoal(AABB bounds, Vec3 position, Vec3 goal, double horizon) {
        if (position.x < bounds.minX || position.x > bounds.maxX
                || position.y < bounds.minY || position.y > bounds.maxY
                || position.z < bounds.minZ || position.z > bounds.maxZ) return null;
        Vec3 direction = goal.subtract(position).normalize();
        double distance = horizon;
        for (int axis = 0; axis < 3; axis++) {
            double start = axis == 0 ? position.x : axis == 1 ? position.y : position.z;
            double delta = axis == 0 ? direction.x : axis == 1 ? direction.y : direction.z;
            double min = axis == 0 ? bounds.minX : axis == 1 ? bounds.minY : bounds.minZ;
            double max = axis == 0 ? bounds.maxX : axis == 1 ? bounds.maxY : bounds.maxZ;
            if (delta > 1.0E-12) distance = Math.min(distance, (max - start - 0.001) / delta);
            else if (delta < -1.0E-12) distance = Math.min(distance, (min - start + 0.001) / delta);
        }
        return distance >= REACHED ? position.add(direction.scale(distance)) : null;
    }

    private static FlightRoutePlanner.Result toward(Vec3 position, Vec3 destination, boolean avoiding) {
        Vec3 displacement = destination.subtract(position);
        double distance = displacement.length();
        return new FlightRoutePlanner.Result(displacement.scale(1.0 / distance), avoiding, false, distance);
    }

    private static FlightRoutePlanner.Result stopped(boolean avoiding) {
        return new FlightRoutePlanner.Result(Vec3.ZERO, avoiding, true, 0.0);
    }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private record Request(Vec3 position, Vec3 goal, Vec3 localGoal, double lookahead, long tick,
                           FlightCollisionSnapshot snapshot) {
        private Request { Objects.requireNonNull(snapshot); }
    }
}
