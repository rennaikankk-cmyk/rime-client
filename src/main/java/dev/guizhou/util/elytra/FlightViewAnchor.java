package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;

/** Keeps flight input independent of camera movement while free look is held. */
public final class FlightViewAnchor {
    private boolean locked;
    private float yaw;
    private float pitch;

    public void update(boolean freeLook, float cameraYaw, float cameraPitch) {
        if (!freeLook) locked = false;
        if (!Float.isFinite(cameraYaw) || !Float.isFinite(cameraPitch)) return;
        if (!locked) {
            yaw = cameraYaw;
            pitch = Math.max(-90.0F, Math.min(90.0F, cameraPitch));
        }
        locked = freeLook;
    }

    public void reset() {
        locked = false;
        yaw = 0.0F;
        pitch = 0.0F;
    }

    public float yaw() { return yaw; }

    public Vec3 direction() { return ElytraPhysics.pitchYawToRotation(pitch, yaw); }
}
