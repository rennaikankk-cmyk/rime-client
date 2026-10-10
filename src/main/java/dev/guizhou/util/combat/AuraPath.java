package dev.guizhou.util.combat;

import java.util.*;
import java.util.function.BiPredicate;

/** Bounded six-neighbour search. The caller validates the full player volume on each edge. */
public final class AuraPath {
    private AuraPath() {}
    public record Point(double x, double y, double z) {
        public Point add(double dx, double dy, double dz) { return new Point(x + dx, y + dy, z + dz); }
        public double distance(Point p) { return Math.sqrt(Math.pow(x-p.x,2)+Math.pow(y-p.y,2)+Math.pow(z-p.z,2)); }
        public Point lerp(Point p, double t) { return add((p.x-x)*t,(p.y-y)*t,(p.z-z)*t); }
        public boolean finite() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z); }
    }
    private record Cell(int x, int y, int z) {
        Point at(Point origin) { return origin.add(x,y,z); }
    }
    private record Node(Cell cell, int cost, double score) {}
    private static final int[][] DIRECTIONS = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

    public static List<Point> find(Point origin, Point goal, double step, double range,
                                    int budget, BiPredicate<Point, Point> clear) {
        if (!origin.finite() || !goal.finite() || !Double.isFinite(step) || step < 1
                || !Double.isFinite(range) || range <= 0 || origin.distance(goal) > range) return List.of();
        step = Math.min(step, 5);
        List<Point> direct = segment(origin, goal, step);
        Point previous = origin;
        boolean valid = true;
        for (Point point : direct) {
            if (!clear.test(previous, point)) { valid = false; break; }
            previous = point;
        }
        if (valid) return direct;
        Cell start = new Cell(0,0,0);
        PriorityQueue<Node> queue = new PriorityQueue<>(Comparator.comparingDouble(Node::score));
        Map<Cell,Integer> costs = new HashMap<>();
        Map<Cell,Cell> parents = new HashMap<>();
        costs.put(start,0);
        queue.add(new Node(start,0,origin.distance(goal)));
        for (int expanded = 0; !queue.isEmpty() && expanded < Math.clamp(budget,1,512);) {
            Node node = queue.remove();
            if (node.cost != costs.get(node.cell)) continue;
            expanded++;
            Point here = node.cell.at(origin);
            if (here.distance(goal) <= step && clear.test(here, goal)) {
                List<Point> route = new ArrayList<>();
                route.add(goal);
                for (Cell cell = node.cell; !cell.equals(start); cell = parents.get(cell)) route.add(cell.at(origin));
                Collections.reverse(route);
                if (route.size() > 48) return List.of();
                List<Point> compressed = new ArrayList<>();
                Point from = origin;
                for (int i = 0; i < route.size();) {
                    int end = i;
                    for (int j = i + 1; j < route.size(); j++) {
                        if (from.distance(route.get(j)) > step || !clear.test(from,route.get(j))) break;
                        end = j;
                    }
                    from = route.get(end);
                    compressed.add(from);
                    i = end + 1;
                }
                return List.copyOf(compressed);
            }
            if (node.cost >= 48) continue;
            for (int[] d : DIRECTIONS) {
                Cell next = new Cell(node.cell.x+d[0],node.cell.y+d[1],node.cell.z+d[2]);
                Point point = next.at(origin);
                int cost = node.cost+1;
                if (point.distance(origin) > range || cost >= costs.getOrDefault(next,Integer.MAX_VALUE)
                        || !clear.test(here,point)) continue;
                costs.put(next,cost);
                parents.put(next,node.cell);
                queue.add(new Node(next,cost,cost+point.distance(goal)));
            }
        }
        return List.of();
    }
    private static List<Point> segment(Point from, Point to, double step) {
        int count = Math.max(1,(int)Math.ceil(from.distance(to)/step));
        if (count > 48) return List.of();
        List<Point> points = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) points.add(from.lerp(to,i/(double)count));
        return List.copyOf(points);
    }
}
