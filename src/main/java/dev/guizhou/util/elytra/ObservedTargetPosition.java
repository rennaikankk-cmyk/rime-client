package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

/** Applied server endpoint, without the renderer's interpolation or an extra velocity lead. */
public final class ObservedTargetPosition {
    private ObservedTargetPosition() {}

    public static Vec3 offset(Vec3 rendered, Vec3 endpoint) {
        if (!finite(rendered) || !finite(endpoint)) return Vec3.ZERO;
        Vec3 delta = endpoint.subtract(rendered);
        return finite(delta) && delta.lengthSqr() <= 144.0 ? delta : Vec3.ZERO;
    }

    public static Vec3 forecastCenter(AABB observedBox, Vec3 observedFeet, Vec3 forecastFeet) {
        return observedBox.getCenter().add(forecastFeet.subtract(observedFeet));
    }

    public static Vec3 look(float yaw, float pitch, Vec3 fallback) {
        return Float.isFinite(yaw) && Float.isFinite(pitch)
                ? Vec3.directionFromRotation(Math.clamp(pitch, -90f, 90f), yaw) : fallback;
    }

    private static boolean finite(Vec3 v) {
        return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }
}
