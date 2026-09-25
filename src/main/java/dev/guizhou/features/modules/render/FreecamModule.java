package dev.guizhou.features.modules.render;

import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.network.DisconnectEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.client.player.Input;
import net.minecraft.world.phys.Vec3;

/**
 * Detaches the camera from the player. Purely client-side: the player receives no movement
 * input while active, so the only packets sent are vanilla's idle position heartbeats.
 */
public class FreecamModule extends Module {
    private static FreecamModule INSTANCE;

    private final Setting<Double> speed = num("Speed", 1.0, 0.1, 5.0);
    private final Setting<Double> verticalSpeed = num("VerticalSpeed", 0.8, 0.1, 5.0);
    private final Setting<Boolean> sprintBoost = bool("SprintBoost", true);
    private final Setting<Boolean> disableOnDamage = bool("DisableOnDamage", true);

    private Vec3 pos = Vec3.ZERO, prevPos = Vec3.ZERO;
    private float yaw, pitch;
    private boolean forward, back, left, right, jump, sneak;
    private ClientLevel sessionLevel;
    private LocalPlayer sessionPlayer;
    private float lastHealth;

    public FreecamModule() {
        super("Freecam", "Detaches the camera and flies it with the movement keys; your player stands still "
                + "and sends no extra packets. Jump/sneak move up/down.", Category.RENDER);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        if (nullCheck()) {
            disable();
            return;
        }
        Vec3 eye = mc.player.getEyePosition(1.0f);
        pos = prevPos = eye;
        yaw = mc.player.getYRot();
        pitch = mc.player.getXRot();
        forward = back = left = right = jump = sneak = false;
        sessionLevel = mc.level;
        sessionPlayer = mc.player;
        lastHealth = mc.player.getHealth();
    }

    @Override
    public void onDisable() {
        forward = back = left = right = jump = sneak = false;
        sessionLevel = null;
        sessionPlayer = null;
    }

    @Subscribe
    private void onDisconnect(DisconnectEvent event) {
        disable();
    }

    @Subscribe(priority = 1000)
    private void onPreTick(PreTickEvent event) {
        if (nullCheck() || mc.level != sessionLevel || mc.player != sessionPlayer || mc.player.isDeadOrDying()) {
            disable();
            return;
        }
        if (disableOnDamage.getValue() && mc.player.getHealth() < lastHealth) {
            disable();
            return;
        }
        lastHealth = mc.player.getHealth();
        prevPos = pos;
        double forward = (this.forward ? 1 : 0) - (back ? 1 : 0);
        double strafe = (left ? 1 : 0) - (right ? 1 : 0);
        double vertical = (jump ? 1 : 0) - (sneak ? 1 : 0);
        double horizontal = speed.getValue() * (sprintBoost.getValue() && mc.options.keySprint.isDown() ? 2.0 : 1.0);
        double length = Math.sqrt(forward * forward + strafe * strafe);
        if (length > 1.0e-4) {
            forward /= length;
            strafe /= length;
        }
        double rad = Math.toRadians(yaw);
        double sin = Math.sin(rad), cos = Math.cos(rad);
        double dx = (strafe * cos - forward * sin) * horizontal;
        double dz = (forward * cos + strafe * sin) * horizontal;
        pos = pos.add(dx, vertical * verticalSpeed.getValue(), dz);
    }

    public static boolean active() {
        return INSTANCE != null && INSTANCE.isEnabled() && !nullCheck();
    }

    /** Called after the vanilla input tick: keeps the keys for the camera and zeroes the player's. */
    public static boolean captureInput(Input real) {
        if (!active()) return false;
        INSTANCE.forward = real.up;
        INSTANCE.back = real.down;
        INSTANCE.left = real.left;
        INSTANCE.right = real.right;
        INSTANCE.jump = real.jumping;
        INSTANCE.sneak = real.shiftKeyDown;
        real.up = real.down = real.left = real.right = false;
        real.jumping = false;
        real.shiftKeyDown = false;
        real.forwardImpulse = 0f;
        real.leftImpulse = 0f;
        return true;
    }

    /** Mouse look turns the camera instead of the player while active. */
    public static boolean consumeTurn(double yawDelta, double pitchDelta) {
        if (!active()) return false;
        INSTANCE.yaw += (float) yawDelta * 0.15f;
        INSTANCE.pitch = Mth.clamp(INSTANCE.pitch + (float) pitchDelta * 0.15f, -90f, 90f);
        return true;
    }

    public static Vec3 cameraPos(float partialTick) {
        return INSTANCE.prevPos.lerp(INSTANCE.pos, partialTick);
    }

    public static float cameraYaw() {
        return INSTANCE.yaw;
    }

    public static float cameraPitch() {
        return INSTANCE.pitch;
    }

    @Override
    public String getDisplayInfo() {
        if (!active() || mc.player == null) return null;
        return String.valueOf((int) pos.distanceTo(mc.player.getEyePosition()));
    }
}
