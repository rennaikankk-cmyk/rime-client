package dev.guizhou.util.elytra;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.function.BiPredicate;

/** Uses one received target box for both aim and the final unobstructed attack ray. */
public final class AerialAttackGeometry {
    private AerialAttackGeometry() {}
    public static Vec3 aimPoint(Vec3 eye, AABB box, double reach, BiPredicate<Vec3,Vec3> visible) {
        if (!valid(eye,box,reach)) return null;
        Vec3 center = box.getCenter();
        Vec3 nearest = new Vec3(Math.clamp(eye.x,box.minX,box.maxX),Math.clamp(eye.y,box.minY,box.maxY),
                Math.clamp(eye.z,box.minZ,box.maxZ)).lerp(center,0.05);
        double inset = Math.min(0.1,box.getYsize()*0.25);
        for (Vec3 point : List.of(nearest,center,new Vec3(center.x,box.maxY-inset,center.z),
                new Vec3(center.x,box.minY+inset,center.z)))
            if (eye.distanceToSqr(point) <= reach*reach && visible.test(eye,point)) return point;
        return null;
    }
    public static boolean canHit(Vec3 eye, AABB box, double reach, float yaw, float pitch,
                                 BiPredicate<Vec3,Vec3> visible) {
        if (!valid(eye,box,reach) || !Float.isFinite(yaw) || !Float.isFinite(pitch)) return false;
        Vec3 point = box.contains(eye) ? eye
                : box.clip(eye,eye.add(Vec3.directionFromRotation(pitch,yaw).scale(reach))).orElse(null);
        return point != null && visible.test(eye,point);
    }
    private static boolean valid(Vec3 eye,AABB box,double reach) {
        return eye != null && box != null && Double.isFinite(eye.lengthSqr())
                && Double.isFinite(box.minX) && Double.isFinite(box.minY) && Double.isFinite(box.minZ)
                && Double.isFinite(box.maxX) && Double.isFinite(box.maxY) && Double.isFinite(box.maxZ)
                && Double.isFinite(reach) && reach > 0;
    }
}
