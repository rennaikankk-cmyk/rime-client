package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import java.util.Random;

public final class AerialEngagementTest {
    private static int checks;
    public static void main(String[] args) {
        Vec3 vanilla = new Vec3(2, 0, 0), applied = new Vec3(15, 0, 0);
        check(AerialEngagement.observedVelocity(vanilla, applied, 1, true).equals(applied), "Fresh GZ motion");
        for (long age : new long[]{-1, 2, 50, Integer.MAX_VALUE})
            check(AerialEngagement.observedVelocity(vanilla, applied, age, true).equals(vanilla), "Old/future sample rejected");
        check(AerialEngagement.observedVelocity(vanilla, applied, 0, false).equals(vanilla), "Setback, impulse and collision invalidate sample");
        check(AerialEngagement.observedVelocity(vanilla, null, 0, true).equals(vanilla), "Missing sample");
        check(AerialEngagement.observedVelocity(vanilla, new Vec3(33,0,0), 0, true).equals(vanilla), "Teleport sample");
        check(AerialEngagement.observedVelocity(null, null, 0, true).equals(Vec3.ZERO), "Invalid fallback");
        Vec3 offset = new Vec3(16, 0, 0);
        check(window(offset, applied, Vec3.ZERO, 16, 8), "Prepare before a 15-block step skips the fixed 8-block setup radius");
        check(!window(offset, vanilla, Vec3.ZERO, 16, 8), "Slow approach remains cruising");
        check(!window(offset, applied, applied, 16, 8), "Equal drift is not closing speed");
        check(!window(offset, applied.scale(-1), Vec3.ZERO, 16, 8), "Moving away does not arm approach");
        check(!window(new Vec3(16,0,20), applied, Vec3.ZERO, Math.hypot(16,20),8), "Lateral miss is not radial entry");
        check(window(offset, vanilla, new Vec3(-10,0,0),16,8), "Opposing target counts toward closure");
        check(AerialEngagement.chargeRange(offset, new Vec3(6,0,0), new Vec3(-6,0,0),4.5,10,1) == 160.5,
                "Opposing passes start charging at relative speed");
        check(AerialEngagement.chargeRange(offset, applied, Vec3.ZERO,4.5,40,3) == 256, "Charge setup remains bounded");
        var precise = AerialEngagement.spearStep(new Vec3(6,0,0),offset,Vec3.ZERO,applied,16,2,4.5);
        check(precise.precision() && precise.velocity().equals(new Vec3(6,0,0)),
                "Preserve a six-block request before a boosted fifteen-block overrun even without shortening it");
        var shortStep = AerialEngagement.spearStep(new Vec3(6,0,0),new Vec3(6,0,0),Vec3.ZERO,applied,6,2,4.5);
        check(shortStep.precision() && 6-shortStep.velocity().x > 2.35, "Keep another query outside the inner band");
        check(!AerialEngagement.spearStep(new Vec3(6,0,0),offset,applied,applied,16,2,4.5).precision(),
                "Do not discard cruise speed when the target is fleeing equally fast");
        check(!AerialEngagement.spearStep(new Vec3(0,0,6),offset,Vec3.ZERO,applied,16,2,4.5).precision(),
                "Threat sidestep owns its direction");
        check(!AerialEngagement.spearStep(new Vec3(-6,0,0),offset,Vec3.ZERO,applied,16,2,4.5).precision(),
                "Escape does not become an approach");
        var climb = AerialEngagement.climbStep(new Vec3(0,4,0), new Vec3(3,0,0),15);
        check(climb.precision() && climb.velocity().equals(new Vec3(3,2,0)), "Keep target drift at the apex");
        check(!AerialEngagement.climbStep(new Vec3(0,40,0),Vec3.ZERO,15).precision(), "Far climb retains boost");
        check(!AerialEngagement.climbStep(new Vec3(0,10,0),new Vec3(0,2.5,0),15).precision(),
                "Buying height over a rising opponent must keep powered climb thrust");
        check(AerialEngagement.climbStep(new Vec3(0,4,0),new Vec3(0,-1,0),15).precision(),
                "Falling apex still permits terminal control");
        AABB victim = new AABB(-.3, 0, -.3, .3, 1.8, .3);
        check(AerialEngagement.maceThreatWindow(new Vec3(0,-2.18,0),new Vec3(0,-.8,0),victim,Vec3.ZERO,3,1),
                "A falling attacker below the victim can still reach upward from its standing eye");
        check(!AerialEngagement.maceThreatWindow(new Vec3(0,-5,0),new Vec3(0,-.8,0),victim,Vec3.ZERO,3,1),
                "A distant attacker falling away is not an upward threat");
        check(AerialEngagement.maceThreatWindow(new Vec3(0,6,0),new Vec3(0,-2,0),victim,Vec3.ZERO,3,1),
                "One-tick descending entry remains a threat");
        check(!AerialEngagement.maceThreatWindow(new Vec3(0,3,0),new Vec3(0,.8,0),victim,Vec3.ZERO,3,1),
                "A climbing mace cannot create a falling smash window");
        Random random = new Random(20261001);
        for (int i=0;i<1000;i++) {
            double angle = random.nextDouble()*Math.PI*2;
            Vec3 axis = new Vec3(Math.cos(angle),0,Math.sin(angle));
            check(window(axis.scale(16),axis.scale(15),Vec3.ZERO,16,8), "Rotated interception remains equivalent");
            var step=AerialEngagement.spearStep(axis.scale(6),axis.scale(6),Vec3.ZERO,axis.scale(15),6,2,4.5);
            check(step.precision() && step.velocity().length() <= 6 && step.velocity().normalize().dot(axis) > .999999,
                    "Approach never reverses, accelerates or changes the attack lane");
            var near=AerialEngagement.climbStep(axis.scale(random.nextDouble()*25),new Vec3(1,0,1),15);
            check(near.precision() && near.velocity().length() <= 15+1e-9, "Moving apex respects flight cap");
        }
        System.out.println("AerialEngagementTest passed: " + checks + " checks");
    }
    private static boolean window(Vec3 offset,Vec3 self,Vec3 target,double distance,double range) {
        return AerialEngagement.closingWindow(offset,self,target,distance,range,1);
    }
    private static void check(boolean ok,String message) {checks++;if(!ok)throw new AssertionError(message);}
}
