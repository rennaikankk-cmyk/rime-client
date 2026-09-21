package dev.guizhou.util.elytra;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.function.BiPredicate;

public final class AerialAttackGeometryTest {
    private static int checks;
    public static void main(String[] args) {
        Vec3 eye = new Vec3(0,1.6,0);
        AABB box = new AABB(-0.3,0,2,0.3,1.8,2.6);
        BiPredicate<Vec3,Vec3> open = (a,b)->true;
        check(AerialAttackGeometry.aimPoint(eye,box,3,open)!=null,"visible target is aimable");
        check(AerialAttackGeometry.canHit(eye,box,3,0,0,open),"forward ray intersects the received box");
        check(!AerialAttackGeometry.canHit(eye,box,3,180,0,open),"facing away cannot attack");
        check(!AerialAttackGeometry.canHit(eye,box,1,0,0,open),"out-of-reach ray rejected");
        check(AerialAttackGeometry.aimPoint(eye,box,3,(a,b)->false)==null,"fully occluded target has no aim point");
        check(!AerialAttackGeometry.canHit(eye,box,3,0,0,(a,b)->false),"final ray rechecks occlusion");
        Vec3 partial = AerialAttackGeometry.aimPoint(eye,box,3,(a,b)->b.y<1);
        check(partial!=null && partial.y<1,"partial cover selects a visible alternate point");
        check(AerialAttackGeometry.aimPoint(eye,box.move(0,0,5),3,open)==null,"received far position wins over old rendered near position");
        check(!AerialAttackGeometry.canHit(eye,box,3,Float.NaN,0,open),"invalid yaw rejected");
        check(AerialAttackGeometry.aimPoint(eye,box,Double.POSITIVE_INFINITY,open)==null,"infinite reach rejected");
        check(AerialAttackGeometry.aimPoint(new Vec3(Double.NaN,0,0),box,3,open)==null,"invalid eye rejected");
        check(AerialAttackGeometry.canHit(box.getCenter(),box,3,0,0,open),"eye inside box has zero-distance hit");
        System.out.println("AerialAttackGeometryTest passed: "+checks+" checks");
    }
    private static void check(boolean value,String message) { checks++; if(!value)throw new AssertionError(message); }
}
