package dev.guizhou.util.elytra;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class FlightRoutePlannerTest {
    private static int checks;

    public static void main(String[] args) {
        openSpaceAndEarlyLookahead();
        wallBehindTargetAndCorner();
        lowWallAndCeiling();
        memoryResetAndMovingObstacle();
        enclosedAndBudget();
        narrowMineTurns();
        System.out.println("FlightRoutePlannerTest passed: " + checks + " checks");
    }

    private static void narrowMineTurns() {
        FlightRoutePlanner.Clearance tunnel = (from,to)-> {
            int steps=Math.max(1,(int)Math.ceil(from.distanceTo(to)*20));
            for(int i=0;i<=steps;i++) {
                Vec3 p=from.lerp(to,(double)i/steps);
                boolean corridor=p.x>=-.35 && p.x<=3.35 && Math.abs(p.z)<=.35
                        || Math.abs(p.x-3)<=.35 && p.z>=-.35 && p.z<=3.35
                        || p.x>=2.65 && p.x<=6.35 && Math.abs(p.z-3)<=.35;
                if(!corridor || Math.abs(p.y)>.2)return false;
            }
            return true;
        };
        FlightRoutePlanner planner=new FlightRoutePlanner();
        Vec3 at=Vec3.ZERO,goal=new Vec3(6,0,3);
        for(int tick=0;tick<80 && at.distanceTo(goal)>.65;tick++) {
            var route=planner.routeTo(at,goal,12,tick,tunnel);
            check(!route.blocked(),"One-block lattice finds the odd-coordinate turn that a two-block lattice skips");
            check(planner.lastSearchNodes()<=FlightRoutePlanner.MAX_NODES,"Fine grid retains the original node cap");
            Vec3 next=at.add(route.direction().scale(Math.min(.5,route.distance())));
            check(tunnel.clear(at,next),"Narrow mine movement cannot cut the inside corner");
            at=next;
        }
        check(at.distanceTo(goal)<=.65,"Exit two narrow roofed bends");
        FlightRoutePlanner.Clearance centeredTunnel=(from,to)-> {
            int steps=Math.max(1,(int)Math.ceil(from.distanceTo(to)*20));
            for(int i=0;i<=steps;i++) {
                Vec3 p=from.lerp(to,(double)i/steps).subtract(.5,0,.5);
                if(Math.abs(p.y)>.2 || !(p.x>=-.14 && p.x<=3.14 && Math.abs(p.z)<=.14
                        || Math.abs(p.x-3)<=.14 && p.z>=-.14 && p.z<=3.14
                        || p.x>=2.86 && p.x<=6.14 && Math.abs(p.z-3)<=.14))return false;
            }
            return true;
        };
        planner.reset();at=new Vec3(.7,0,.5);goal=new Vec3(6.5,0,3.5);
        for(int tick=0;tick<80 && at.distanceTo(goal)>.65;tick++) {
            var route=planner.routeTo(at,goal,12,tick,centeredTunnel);
            check(!route.blocked(),"Off-center entry can align with block-centered one-block passages");
            Vec3 next=at.add(route.direction().scale(Math.min(.5,route.distance())));
            check(centeredTunnel.clear(at,next),"Centering step is swept too");at=next;
        }
        check(at.distanceTo(goal)<=.65,"Off-center mine entry reaches the exit");
    }

    private static void openSpaceAndEarlyLookahead() {
        FlightRoutePlanner planner = new FlightRoutePlanner();
        Scene scene = new Scene();
        FlightRoutePlanner.Result direct = planner.route(Vec3.ZERO, new Vec3(1, 0, 0), 1, 0, scene);
        check(!direct.avoiding() && !direct.blocked(), "Clear route remains direct");
        close(8, direct.distance(), "Minimum eight-block lookahead");
        scene.boxes.add(new AABB(5, -20, -2, 6, 20, 2));
        FlightRoutePlanner.Result detour = planner.route(Vec3.ZERO, new Vec3(1, 0, 0), 1, 1, scene);
        check(detour.avoiding() && !detour.blocked(), "Obstacle five blocks away must trigger routing before one-tick collision");
        check(Math.abs(detour.direction().z) > 0.1, "Wall avoidance uses horizontal clearance");
        Scene thickWall = new Scene(new AABB(5, -20, -2, 10, 20, 2));
        FlightRoutePlanner.Result thick = new FlightRoutePlanner().route(Vec3.ZERO, new Vec3(1, 0, 0), 8, 0, thickWall);
        check(thick.avoiding() && !thick.blocked(), "Lookahead endpoint inside thick wall must look beyond it, not shorten to the speed vector");
    }

    private static void wallBehindTargetAndCorner() {
        Scene wall = new Scene(new AABB(3, -30, -3, 4, 30, 3));
        traverse(wall, new Vec3(10, 0, 0), "Target behind solid wall");
        Scene corner = new Scene(new AABB(3, -30, -4, 4, 30, 4),
                new AABB(3, -30, -4, 9, 30, -3));
        traverse(corner, new Vec3(12, 0, 0), "L-shaped wall");
        Scene diagonal = new Scene(new AABB(0.2, -20, 3.2, 0.8, 20, 3.8));
        FlightRoutePlanner.Result result = new FlightRoutePlanner().route(Vec3.ZERO, new Vec3(8, 0, 8), 8, 0, diagonal);
        check(!result.avoiding(), "Obstacle in diagonal bounding hull but outside swept body must not block");
    }

    private static void lowWallAndCeiling() {
        Scene lowWall = new Scene(new AABB(3, -20, -12, 4, 1.5, 12),
                new AABB(-20, -2, -20, 20, -1, 20));
        FlightRoutePlanner.Result climb = new FlightRoutePlanner().route(Vec3.ZERO, new Vec3(8, 0, 0), 8, 0, lowWall);
        check(!climb.blocked() && climb.direction().y > 0.1, "Low wall chooses a climb with body clearance");
        traverse(lowWall, new Vec3(10, 0, 0), "Low-wall climb and descent");
        Scene ceiling = new Scene(new AABB(-20, 1, -20, 20, 2, 20),
                new AABB(-20, -2, -20, 20, -1, 20), new AABB(3, -1, -2, 4, 1, 2));
        FlightRoutePlanner.Result side = new FlightRoutePlanner().route(Vec3.ZERO, new Vec3(8, 0, 0), 8, 0, ceiling);
        check(!side.blocked() && Math.abs(side.direction().y) < 1.0E-9,
                "Low ceiling and floor force a lateral detour");
        traverse(ceiling, new Vec3(10, 0, 0), "Route under low ceiling");
    }

    private static void memoryResetAndMovingObstacle() {
        Scene scene = new Scene(new AABB(3, -30, -3, 4, 30, 3));
        FlightRoutePlanner planner = new FlightRoutePlanner();
        FlightRoutePlanner.Result first = planner.route(Vec3.ZERO, new Vec3(8, 0, 0), 8, 100, scene);
        check(!first.blocked(), "Initial detour exists");
        int nodes = planner.lastSearchNodes();
        for (int tick = 101; tick < 104; tick++) {
            FlightRoutePlanner.Result cached = planner.route(Vec3.ZERO, new Vec3(8, 0, (tick % 2 == 0 ? 1 : -1) * 0.02), 8, tick, scene);
            check(cached.direction().dot(first.direction()) > 0.999, "Stable waypoint prevents side alternation");
            check(planner.lastSearchNodes() == nodes, "Cached route does not re-search within four ticks");
        }
        Vec3 blockedPoint = first.direction().scale(Math.min(1.5, first.distance() * 0.5));
        scene.boxes.add(new AABB(blockedPoint.x - 0.4, blockedPoint.y - 0.4, blockedPoint.z - 0.4,
                blockedPoint.x + 0.4, blockedPoint.y + 0.4, blockedPoint.z + 0.4));
        FlightRoutePlanner.Result wait = planner.route(Vec3.ZERO, new Vec3(8, 0, 0), 8, 103, scene);
        check(wait.blocked(), "New obstruction invalidates cached leg immediately even during cooldown");
        planner.invalidate();
        FlightRoutePlanner.Result changed = planner.route(Vec3.ZERO, new Vec3(8, 0, 0), 8, 103, scene);
        check(!changed.blocked() && scene.clear(Vec3.ZERO, changed.direction().scale(changed.distance())),
                "Explicit invalidation replans immediately around moving obstacle");
        planner.reset();
        FlightRoutePlanner.Result newWorld = planner.route(new Vec3(100, 60, 100), new Vec3(0, 0, -8), 8, 0, new Scene());
        check(!newWorld.avoiding() && newWorld.direction().z < -0.99, "World and target reset discard old waypoint");
        planner.route(Vec3.ZERO, new Vec3(8, 0, 0), 8, 10, scene);
        FlightRoutePlanner.Result reverse = planner.route(Vec3.ZERO, new Vec3(-8, 0, 0), 8, 11, scene);
        check(!reverse.avoiding() && reverse.direction().x < -0.99, "Target heading reversal resets detour");
        check(planner.route(Vec3.ZERO, new Vec3(Double.NaN, 0, 0), 8, 12, scene).blocked(), "Invalid input stops safely");
    }

    private static void enclosedAndBudget() {
        Scene enclosed = new Scene(new AABB(1, -2, -2, 2, 2, 2), new AABB(-2, -2, -2, -1, 2, 2),
                new AABB(-2, -2, 1, 2, 2, 2), new AABB(-2, -2, -2, 2, 2, -1),
                new AABB(-2, 1, -2, 2, 2, 2), new AABB(-2, -2, -2, 2, -1, 2));
        FlightRoutePlanner planner = new FlightRoutePlanner();
        FlightRoutePlanner.Result stopped = planner.route(Vec3.ZERO, new Vec3(8, 0, 0), 8, 0, enclosed);
        check(stopped.blocked() && stopped.direction().lengthSqr() == 0.0, "Completely enclosed flight must stop requesting motion");
        check(planner.lastSearchNodes() <= FlightRoutePlanner.MAX_NODES, "Enclosed search obeys budget");
        Scene infiniteWall = new Scene(new AABB(3, -100, -100, 4, 100, 100));
        planner.reset();
        check(planner.route(Vec3.ZERO, new Vec3(8, 0, 0), 8, 0, infiniteWall).blocked(), "Unreachable local goal returns blocked");
        check(planner.lastSearchNodes() <= FlightRoutePlanner.MAX_NODES, "Failed search is capped at 256 recorded nodes");
    }

    private static void traverse(Scene scene, Vec3 target, String context) {
        FlightRoutePlanner planner = new FlightRoutePlanner();
        Vec3 position = Vec3.ZERO;
        for (int tick = 0; tick < 160 && position.distanceTo(target) > 0.8; tick++) {
            Vec3 desired = target.subtract(position);
            FlightRoutePlanner.Result result = planner.routeTo(position, target, 8, tick, scene);
            check(planner.lastSearchNodes() <= FlightRoutePlanner.MAX_NODES, context + ": bounded search");
            if (result.blocked()) continue;
            double step = Math.min(0.5, Math.min(desired.length(), result.distance()));
            Vec3 next = position.add(result.direction().scale(step));
            check(scene.clear(position, next), context + ": every moved body sweep remains clear");
            position = next;
        }
        check(position.distanceTo(target) <= 0.8, context + ": reach target, actual=" + position);
    }

    private static void close(double expected, double actual, String message) {
        check(Math.abs(expected - actual) < 1.0E-8, message + ": " + actual);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class Scene implements FlightRoutePlanner.Clearance {
        final List<AABB> boxes = new ArrayList<>();

        Scene(AABB... obstacles) {
            boxes.addAll(List.of(obstacles));
        }

        @Override
        public boolean clear(Vec3 from, Vec3 to) {
            // Minkowski expansion gives exact continuous collision for a 0.6-block body, including diagonal motion.
            for (AABB box : boxes) {
                AABB expanded = box.inflate(0.3);
                if (intersects(expanded, from, to)) return false;
            }
            return true;
        }

        private static boolean intersects(AABB box, Vec3 from, Vec3 to) {
            double lower = 0.0;
            double upper = 1.0;
            double[] start = {from.x, from.y, from.z};
            double[] delta = {to.x - from.x, to.y - from.y, to.z - from.z};
            double[] min = {box.minX, box.minY, box.minZ};
            double[] max = {box.maxX, box.maxY, box.maxZ};
            for (int axis = 0; axis < 3; axis++) {
                if (Math.abs(delta[axis]) < 1.0E-12) {
                    if (start[axis] < min[axis] || start[axis] > max[axis]) return false;
                } else {
                    double first = (min[axis] - start[axis]) / delta[axis];
                    double last = (max[axis] - start[axis]) / delta[axis];
                    lower = Math.max(lower, Math.min(first, last));
                    upper = Math.min(upper, Math.max(first, last));
                    if (lower > upper) return false;
                }
            }
            return true;
        }
    }
}
