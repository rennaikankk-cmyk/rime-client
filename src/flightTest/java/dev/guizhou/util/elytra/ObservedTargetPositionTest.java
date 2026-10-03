package dev.guizhou.util.elytra;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

public final class ObservedTargetPositionTest {
    public static void main(String[] args) {
        TargetMotionTracker tracker = new TargetMotionTracker();
        for (int tick = 0; tick <= 5; tick++) {
            Vec3 endpoint = new Vec3(tick * 2, 100, 0);
            Vec3 rendered = new Vec3(Math.max(0, tick * 2 - 3), 100, 0);
            tracker.sample(rendered.add(ObservedTargetPosition.offset(rendered, endpoint)), tick, false);
        }
        check(tracker.velocity().distanceToSqr(new Vec3(2, 0, 0)) < 1e-9, "render easing must not corrupt speed");
        check(ObservedTargetPosition.offset(Vec3.ZERO, new Vec3(12,0,0)).x == 12, "bounded endpoint accepted");
        check(ObservedTargetPosition.offset(Vec3.ZERO, new Vec3(13,0,0)).equals(Vec3.ZERO), "teleport rejected");
        check(ObservedTargetPosition.offset(Vec3.ZERO, new Vec3(Double.NaN,0,0)).equals(Vec3.ZERO), "non-finite rejected");
        check(ObservedTargetPosition.offset(Vec3.ZERO, null).equals(Vec3.ZERO), "no interpolation falls back");
        AABB observed = new AABB(2.2,100,-.3,2.8,101.8,.3);
        Vec3 aim = ObservedTargetPosition.forecastCenter(observed,new Vec3(2.5,100,0),new Vec3(3.5,100,0));
        check(Math.abs(aim.x-3.5)<1e-9&&Math.abs(aim.y-100.9)<1e-9,"server offset and body center are added exactly once");
        check(ObservedTargetPosition.look(90,0,Vec3.ZERO).x < -.999,"server yaw controls threat direction");
        check(ObservedTargetPosition.look(Float.NaN,0,new Vec3(1,0,0)).x==1,"invalid rotation falls back");
        System.out.println("ObservedTargetPositionTest passed: 8 checks");
    }
    static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
