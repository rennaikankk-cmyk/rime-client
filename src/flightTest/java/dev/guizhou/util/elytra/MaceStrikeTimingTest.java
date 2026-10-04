package dev.guizhou.util.elytra;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class MaceStrikeTimingTest {
    private static int checks;
    public static void main(String[] args) {
        check(MaceStrikeTiming.mayReserve(0, 100, 10, false), "First reachable charge tick keeps the original allowance");
        check(MaceStrikeTiming.mayReserve(1, 101, 100, true), "Stable consecutive charge may buy one more fall tick");
        check(!MaceStrikeTiming.mayReserve(1, 101, 100, false), "A turning target must be struck without a second reservation");
        for (long tick : new long[]{99,100,102,1000})
            check(!MaceStrikeTiming.mayReserve(1,tick,100,true), "Same callback, rollback and stale observation cannot extend a pass");
        for (int used : new int[]{-1,2,3,100})
            check(!MaceStrikeTiming.mayReserve(used,101,100,true), "Hard two-tick ceiling cannot be renewed");
        AABB body = new AABB(-.3, 0, -.3, .3, .6, .3);
        Vec3 eye = new Vec3(0, 2, 0), fall = new Vec3(0, -.8, 0);
        check(MaceStrikeTiming.reserveOneTick(eye, fall, body, Vec3.ZERO, 2, 3), "Retain a visible charge window");
        check(!MaceStrikeTiming.reserveOneTick(eye, fall, body, new Vec3(0,4,0),2,3), "Do not wait beyond the departing target's last reachable tick");
        check(!MaceStrikeTiming.reserveOneTick(eye,new Vec3(0,-5,0),body,Vec3.ZERO,2,3), "Do not fly through the last hit window");
        check(!MaceStrikeTiming.reserveOneTick(eye,new Vec3(0,.8,0),body,Vec3.ZERO,2,3), "A climb cannot build natural fall charge");
        for (double value : new double[]{0,1.5,4.5,20,Double.NaN,Double.POSITIVE_INFINITY})
            check(!MaceStrikeTiming.reserveOneTick(eye,fall,body,Vec3.ZERO,value,3), "Unready or sufficiently charged attacks are not reserved");
        check(!MaceStrikeTiming.reserveOneTick(eye,fall,body,new Vec3(Double.NaN,0,0),2,3), "Invalid target observation cannot delay a real strike");
        check(!MaceStrikeTiming.reserveOneTick(new Vec3(0,5,0),fall,body,Vec3.ZERO,2,3), "Forecast does not authorize out-of-range attacks");
        for (int i=0;i<1000;i++) {
            double a=i*Math.PI*2/1000;
            Vec3 drift=new Vec3(Math.cos(a),0,Math.sin(a));
            check(MaceStrikeTiming.reserveOneTick(eye,fall.add(drift),body,drift,2,3), "Common lateral drift preserves the same relative window");
        }
        System.out.println("MaceStrikeTimingTest passed: "+checks+" checks");
    }
    private static void check(boolean value,String message) {checks++;if(!value)throw new AssertionError(message);}
}
