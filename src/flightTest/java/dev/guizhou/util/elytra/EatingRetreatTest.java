package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.function.Predicate;

public final class EatingRetreatTest {
    private static int checks;
    public static void main(String[] args) {
        Vec3 self = Vec3.ZERO, velocity = new Vec3(2, 0, 0);
        var east = new EatingRetreat.Threat(new Vec3(8, 0, 0), Vec3.ZERO, 3);
        Vec3 climb = plan(self, velocity, List.of(east), step -> true);
        check(climb.x < 0 && climb.y > 0, "Close enemy: increase horizontal separation while gaining height");
        Vec3 high = plan(new Vec3(0, 12, 0), velocity, List.of(east), step -> true);
        check(Math.abs(high.y) < 1e-9, "Already above desired height: do not climb indefinitely");
        Vec3 ceiling = plan(self, velocity, List.of(east), step -> step.y <= 0);
        check(ceiling.x < 0 && ceiling.y == 0, "Ceiling: escape horizontally instead of requesting a blocked climb");
        Vec3 underRoof = new Vec3(6, 162.5, 0), roofVelocity = new Vec3(.48, 0, 0);
        FlightTerrainSafety.Clearance headroom = (from, to) -> Math.max(from.y, to.y) + underRoof.y + .62 < 164;
        Vec3 roofEscape = plan(underRoof, roofVelocity,
                List.of(new EatingRetreat.Threat(new Vec3(16, 160, 0), Vec3.ZERO, 3)),
                step -> headroom.clear(Vec3.ZERO, step.normalize().scale(10))
                        && FlightTerrainSafety.trajectoryClear(roofVelocity, step.normalize(), .08, 12, true, headroom));
        check(roofEscape.x < 0 && roofEscape.y == 0, "Low ceiling with an attached rocket: retain a clear escape away from the enemy");
        check(FlightTerrainSafety.trajectoryClear(roofVelocity, roofEscape, .08, 12, true, headroom),
                "Escape clearance includes powered glide and momentum, not only a short ray");
        check(plan(self, velocity, List.of(east), step -> false).equals(Vec3.ZERO), "No safe body path: brake");
        var west = new EatingRetreat.Threat(new Vec3(-8, 0, 0), Vec3.ZERO, 11);
        Vec3 multiple = plan(self, velocity, List.of(east, west), step -> true);
        for (var enemy : List.of(east, west))
            check(self.add(multiple.scale(4)).distanceTo(enemy.position()) >= self.distanceTo(enemy.position()),
                    "Escaping one opponent must not close on the other nearby opponent");
        var overhead = new EatingRetreat.Threat(new Vec3(0, 3, 0), Vec3.ZERO, 3);
        Vec3 underMace = plan(self, velocity, List.of(overhead), step -> true);
        check(underMace.y == 0 && underMace.horizontalDistance() > .9, "Enemy directly above: lateral separation precedes lift into reach");
        Vec3 slow = plan(self, new Vec3(.1, 0, 0), List.of(east), step -> true);
        check(slow.y <= .12, "Low observed speed: avoid a steep stall request");
        Vec3 crossing = plan(self, velocity, List.of(new EatingRetreat.Threat(new Vec3(10, 0, 0), new Vec3(-2, 0, 0), 11)), step -> true);
        check(crossing.x < 0 && finite(crossing), "Closing spear: lead the danger and retain an escape");
        check(plan(self, velocity, List.of(), step -> true).equals(Vec3.ZERO), "No observed enemy: no invented attack direction");
        Vec3 far = plan(new Vec3(80, 12, 0), velocity, List.of(east), step -> true);
        check(far.x < 0 && far.y == 0, "Once safely clear: retain a bounded escape lane rather than endlessly increasing distance and height");
        check(plan(new Vec3(Double.NaN, 0, 0), velocity, List.of(east), step -> true).equals(Vec3.ZERO), "Invalid self state fails closed");
        for (int angle = 0; angle < 360; angle += 5) {
            double radians = Math.toRadians(angle);
            Vec3 enemyPosition = new Vec3(Math.cos(radians) * 9, 0, Math.sin(radians) * 9);
            Vec3 result = plan(self, velocity, List.of(new EatingRetreat.Threat(enemyPosition, Vec3.ZERO, 11)), step -> true);
            check(finite(result) && Math.abs(result.length() - 1) < 1e-9, "Escape remains finite and normalized across bearings");
            check(result.dot(enemyPosition) <= 1e-9, "A close enemy bearing cannot receive a pursuit request");
        }
        System.out.println("EatingRetreatTest passed: " + checks + " checks");
    }
    private static Vec3 plan(Vec3 self, Vec3 velocity, List<EatingRetreat.Threat> threats, Predicate<Vec3> clear) {
        return EatingRetreat.direction(self, velocity, threats, new Vec3(-1, 0, 0), 16, 6, 1, clear);
    }
    private static boolean finite(Vec3 v) { return Double.isFinite(v.lengthSqr()); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
