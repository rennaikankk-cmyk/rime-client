package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Bounded local routing. Collision queries must sweep the player's complete body. */
public final class FlightRoutePlanner {
    public static final int MAX_NODES = 256;
    public static final int REPLAN_TICKS = 4;
    private static final double GRID_STEP = 2.0;
    private static final double REACHED = 0.65;
    private static final int[][] NEIGHBORS = neighbors();

    @FunctionalInterface
    public interface Clearance {
        boolean clear(Vec3 from, Vec3 to);
    }

    public record Result(Vec3 direction, boolean avoiding, boolean blocked, double distance) {
    }

    private final ArrayDeque<Vec3> waypoints = new ArrayDeque<>();
    private Vec3 plannedHeading = Vec3.ZERO;
    private Vec3 segmentStart;
    private long plannedTick = Long.MIN_VALUE / 2;
    private int lastSearchNodes;

    public void reset() {
        waypoints.clear();
        plannedHeading = Vec3.ZERO;
        segmentStart = null;
        plannedTick = Long.MIN_VALUE / 2;
        lastSearchNodes = 0;
    }

    public void invalidate() {
        reset();
    }

    public int lastSearchNodes() {
        return lastSearchNodes;
    }

    List<Vec3> plannedWaypoints() {
        return List.copyOf(waypoints);
    }

    public Result route(Vec3 position, Vec3 desiredDisplacement, double lookahead, long tick, Clearance space) {
        return route(position, desiredDisplacement, lookahead, Double.POSITIVE_INFINITY, tick, space);
    }

    /** Use a known destination to keep a downward probe from extending beyond it into the floor. */
    public Result routeTo(Vec3 position, Vec3 destination, double lookahead, long tick, Clearance space) {
        Vec3 displacement = finite(position) && finite(destination) ? destination.subtract(position) : null;
        return route(position, displacement, lookahead, displacement == null ? 0.0 : displacement.length(), tick, space);
    }

    private Result route(Vec3 position, Vec3 desiredDisplacement, double lookahead, double destinationDistance,
                         long tick, Clearance space) {
        if (!finite(position) || !finite(desiredDisplacement) || space == null
                || !Double.isFinite(lookahead) || desiredDisplacement.lengthSqr() < 1.0E-9) {
            reset();
            return stopped(false);
        }
        Vec3 heading = desiredDisplacement.normalize();
        double horizon = Math.min(destinationDistance, Math.clamp(lookahead, 8.0, 32.0));
        Vec3 goal = position.add(heading.scale(horizon));
        // The arbitrary lookahead endpoint may fall inside a thick wall even though its far side is open.
        if (!space.clear(goal, goal)) {
            double end = Math.min(destinationDistance, Math.min(32.0, horizon + 8.0));
            for (double distance = Math.min(horizon + GRID_STEP, end); distance > horizon; distance = Math.min(distance + GRID_STEP, end)) {
                Vec3 candidate = position.add(heading.scale(distance));
                if (space.clear(candidate, candidate)) {
                    horizon = distance;
                    goal = candidate;
                    break;
                }
                if (distance == end) break;
            }
        }
        if (tick < plannedTick || plannedHeading.lengthSqr() > 0.0 && heading.dot(plannedHeading) < 0.5) reset();

        while (!waypoints.isEmpty() && (position.distanceToSqr(waypoints.peekFirst()) < REACHED * REACHED
                || segmentStart != null && waypoints.peekFirst().subtract(position)
                .dot(waypoints.peekFirst().subtract(segmentStart)) <= 0)) {
            if (waypoints.size() > 1 && position.distanceToSqr(waypoints.peekFirst()) > 1e-8) {
                var points = waypoints.iterator();
                points.next();
                if (!space.clear(position, points.next())) break;
            }
            segmentStart = waypoints.removeFirst();
        }
        if (!waypoints.isEmpty()) {
            Vec3 next = waypoints.peekFirst();
            if (space.clear(position, next)) {
                // Retain the same detour through a turn; early direct-ray successes otherwise oscillate at corners.
                if (tick - plannedTick >= REPLAN_TICKS && space.clear(position, goal)) {
                    waypoints.clear();
                    return toward(position, goal, false);
                }
                return toward(position, next, true);
            }
            waypoints.clear();
        }
        if (space.clear(position, goal)) return toward(position, goal, false);
        if (tick - plannedTick < REPLAN_TICKS) return stopped(true);

        plannedTick = tick;
        plannedHeading = heading;
        List<Vec3> route = search(position, goal, horizon, space);
        if (route.isEmpty()) return stopped(true);
        waypoints.addAll(route);
        segmentStart = position;
        return toward(position, waypoints.peekFirst(), true);
    }

    private List<Vec3> search(Vec3 origin, Vec3 goal, double horizon, Clearance space) {
        lastSearchNodes = 0;
        if (!space.clear(origin, origin) || !space.clear(goal, goal)) return List.of();
        // Two-block cells can skip the only one-block turn in a mine passage.
        // Use the same node budget on a finer grid only under a low ceiling.
        double gridStep = space.clear(origin, origin.add(0, GRID_STEP, 0))
                && space.clear(goal, goal.add(0, GRID_STEP, 0)) ? GRID_STEP : 1.0;
        Vec3 gridOrigin = origin;
        if (gridStep == 1.0) {
            Vec3 centered = new Vec3(Math.floor(origin.x) + .5, origin.y, Math.floor(origin.z) + .5);
            if (space.clear(origin, centered)) gridOrigin = centered;
        }
        double radius = Math.min(40.0, Math.max(12.0, horizon + 6.0));
        Map<Cell, Node> known = new HashMap<>();
        PriorityQueue<Open> open = new PriorityQueue<>(Comparator.comparingDouble(Open::score)
                .thenComparingLong(Open::order));
        Node entrance = gridOrigin.distanceToSqr(origin) > 1e-8
                ? new Node(new Cell(0, 0, 0), origin, 0, null) : null;
        Node start = new Node(new Cell(0, 0, 0), gridOrigin, gridOrigin.distanceTo(origin), entrance);
        known.put(start.cell, start);
        long order = 0;
        open.add(new Open(start, start.cost, heuristic(origin, goal), order++));
        while (!open.isEmpty()) {
            Open pending = open.remove();
            Node current = pending.node;
            if (current.closed || pending.cost != current.cost) continue;
            current.closed = true;
            if (space.clear(current.position, goal)) {
                lastSearchNodes = known.size();
                return simplify(current, origin, goal, space);
            }
            for (int[] offset : NEIGHBORS) {
                Cell cell = new Cell(current.cell.x + offset[0], current.cell.y + offset[1], current.cell.z + offset[2]);
                Vec3 position = gridOrigin.add(cell.x * gridStep, cell.y * gridStep, cell.z * gridStep);
                if (position.distanceToSqr(origin) > radius * radius) continue;
                Node previous = known.get(cell);
                if (previous != null && previous.closed || previous == null && known.size() >= MAX_NODES) continue;
                double distance = position.distanceTo(current.position);
                double cost = current.cost + distance + Math.abs(offset[1]) * 0.15;
                if (previous != null && cost >= previous.cost || !space.clear(current.position, position)) continue;
                if (previous == null) {
                    previous = new Node(cell, position, cost, current);
                    known.put(cell, previous);
                } else {
                    previous.cost = cost;
                    previous.parent = current;
                }
                open.add(new Open(previous, cost, cost + heuristic(position, goal), order++));
            }
        }
        lastSearchNodes = known.size();
        return List.of();
    }

    private static List<Vec3> simplify(Node last, Vec3 origin, Vec3 goal, Clearance space) {
        ArrayDeque<Vec3> reversed = new ArrayDeque<>();
        reversed.addFirst(goal);
        for (Node node = last; node.parent != null; node = node.parent) reversed.addFirst(node.position);
        List<Vec3> points = new ArrayList<>(reversed);
        List<Vec3> result = new ArrayList<>();
        Vec3 from = origin;
        for (int index = 0; index < points.size();) {
            int furthest = index;
            for (int candidate = points.size() - 1; candidate > index; candidate--) {
                if (space.clear(from, points.get(candidate))) {
                    furthest = candidate;
                    break;
                }
            }
            from = points.get(furthest);
            result.add(from);
            index = furthest + 1;
        }
        return result;
    }

    private static double heuristic(Vec3 from, Vec3 to) {
        return from.distanceTo(to);
    }

    private static Result toward(Vec3 from, Vec3 to, boolean avoiding) {
        Vec3 displacement = to.subtract(from);
        double distance = displacement.length();
        return distance > 1.0E-9 ? new Result(displacement.scale(1.0 / distance), avoiding, false, distance)
                : stopped(avoiding);
    }

    private static Result stopped(boolean avoiding) {
        return new Result(Vec3.ZERO, avoiding, true, 0.0);
    }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private static int[][] neighbors() {
        List<int[]> offsets = new ArrayList<>();
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (x != 0 || y != 0 || z != 0) offsets.add(new int[]{x, y, z});
                }
            }
        }
        offsets.sort(Comparator.comparingInt((int[] value) -> Math.abs(value[1]))
                .thenComparingInt(value -> value[0] * value[0] + value[1] * value[1] + value[2] * value[2]));
        return offsets.toArray(int[][]::new);
    }

    private record Cell(int x, int y, int z) {
    }

    private record Open(Node node, double cost, double score, long order) {
    }

    private static final class Node {
        final Cell cell;
        final Vec3 position;
        double cost;
        Node parent;
        boolean closed;

        Node(Cell cell, Vec3 position, double cost, Node parent) {
            this.cell = cell;
            this.position = position;
            this.cost = cost;
            this.parent = parent;
        }
    }
}
