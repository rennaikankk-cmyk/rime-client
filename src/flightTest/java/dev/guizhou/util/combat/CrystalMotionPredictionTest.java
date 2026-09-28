package dev.guizhou.util.combat;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class CrystalMotionPredictionTest {
    private static int checks;
    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        check(CrystalMotionPrediction.horizon(4, 0, 50) == 1, "local forecast needs only one tick");
        check(CrystalMotionPrediction.horizon(4, 100, 50) == 2, "100ms round trip");
        check(CrystalMotionPrediction.horizon(4, 500, 50) == 4, "user horizon caps lag");
        check(CrystalMotionPrediction.horizon(4, 200, 100) == 2, "slow server tick");
        check(CrystalMotionPrediction.horizon(0, 100, 50) == 0, "disabled horizon");
        AABB standing = new AABB(-.3, 0, -.3, .3, 1.8, .3);
        AABB flying = new AABB(-.3, 0, -.3, .3, .6, .3);
        AABB ceiling = new AABB(-1, .8, -1, 20, 2, 1);
        var flight = CrystalMotionPrediction.trace(Vec3.ZERO, flying, new Vec3(8, 0, 0), 1,
                false, b -> !b.intersects(ceiling));
        check(flight.getLast().x == 8, "flight pose passes under ceiling at 160 blocks/s");
        var walk = CrystalMotionPrediction.trace(Vec3.ZERO, standing, new Vec3(8, 0, 0), 1,
                false, b -> !b.intersects(ceiling));
        check(walk.size() == 1, "standing pose cannot pass ceiling");
        AABB wall = new AABB(4.01, -1, -1, 4.02, 4, 1);
        var blocked = CrystalMotionPrediction.trace(Vec3.ZERO, flying, new Vec3(14, 0, 0), 2,
                false, b -> !b.intersects(wall));
        check(blocked.getLast().x <= 3.71, "278 blocks/s cannot tunnel through thin wall");
        var path = CrystalMotionPrediction.trace(Vec3.ZERO, flying, new Vec3(14, 0, 0), 2, false, b -> true);
        check(path.getLast().x == 28, "observed glide velocity retained over short horizon");
        check(path.stream().anyMatch(v -> v.distanceTo(new Vec3(7, 0, 0)) < .01), "intermediate self exposure is represented");
        for (int i = 1; i < path.size(); i++) check(path.get(i).distanceTo(path.get(i-1)) <= .500001, "bounded sample spacing");
        var limited = CrystalMotionPrediction.trace(Vec3.ZERO, flying, new Vec3(30, 0, 0), 20, false, b -> true);
        check(limited.size() <= 129, "work bounded at extreme settings");
        check(CrystalMotionPrediction.trace(Vec3.ZERO, flying, new Vec3(100,0,0), 4, false, b -> true).size() == 1, "teleport delta rejected");
        check(CrystalMotionPrediction.trace(Vec3.ZERO, flying, new Vec3(Double.NaN,0,0), 4, false, b -> true).size() == 1, "nonfinite velocity rejected");
        System.out.println("CrystalMotionPredictionTest passed: " + checks + " checks");
    }
}
