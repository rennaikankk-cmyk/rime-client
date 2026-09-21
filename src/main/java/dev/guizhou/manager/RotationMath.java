package dev.guizhou.manager;

/** Shared angle policy, independent of Minecraft and the render frame rate. */
public final class RotationMath {
    private RotationMath() {}

    public static boolean finite(float yaw, float pitch) {
        return Float.isFinite(yaw) && Float.isFinite(pitch);
    }

    public static float difference(float target, float current) {
        float delta = (target % 360f - current % 360f) % 360f;
        if (delta >= 180f) delta -= 360f;
        if (delta < -180f) delta += 360f;
        return delta;
    }

    public static float step(float current, float target, float limit) {
        float delta = difference(target, current);
        return current + Math.clamp(delta, -limit, limit);
    }

    public static boolean matches(float yaw, float pitch, float targetYaw, float targetPitch, float tolerance) {
        return finite(yaw, pitch) && finite(targetYaw, targetPitch)
                && Math.abs(difference(targetYaw, yaw)) <= tolerance
                && Math.abs(targetPitch - pitch) <= tolerance;
    }
}
