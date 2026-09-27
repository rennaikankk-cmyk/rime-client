package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;

public final class FlightMotionSampleTest {
    private static int checks;
    public static void main(String[] args) {
        var sample=new FlightMotionSample();
        var fallback=new Vec3(.2,.3,.4);
        check(sample.sample(Vec3.ZERO,10,fallback,true).equals(fallback),"initial sample preserves vanilla");
        check(sample.sample(new Vec3(1,2,3),11,fallback,true).equals(new Vec3(1,2,3)),"uses measured displacement, not post-rocket velocity");
        check(sample.sample(new Vec3(2,4,6),11,fallback,true).equals(fallback),"second hook in one tick is not another movement");
        check(sample.sample(new Vec3(5,8,10),14,fallback,true).equals(fallback),"skipped ticks do not become acceleration");
        check(sample.sample(new Vec3(6,9,11),15,fallback,false).equals(fallback),"collision or impulse keeps vanilla");
        check(sample.sample(new Vec3(7,10,12),16,fallback,true).equals(fallback),"unsafe previous tick cannot seed prediction");
        check(sample.sample(new Vec3(1000,1000,1000),17,fallback,true).equals(fallback),"teleport discontinuity rejected");
        sample.reset();
        check(sample.sample(new Vec3(1001,1001,1001),18,fallback,true).equals(fallback),"session reset drops momentum");
        check(sample.sample(new Vec3(Double.NaN,0,0),19,fallback,true).equals(fallback),"invalid coordinates cannot enter prediction");
        System.out.println("FlightMotionSampleTest passed: "+checks+" checks");
    }
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
