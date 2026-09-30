package dev.guizhou.features.modules.extra;

import dev.guizhou.features.settings.Setting;
import net.minecraft.world.phys.Vec3;

public final class AutoCenterModule extends UtilityModule {
    private final Setting<Double> speed = num("Speed", 0.2, 0.05, 0.5);
    private final Setting<Integer> timeout = num("Timeout", 20, 1, 80);
    private double x, z;
    private int ticks;
    public AutoCenterModule() { super("AutoCenter", "Centers on the current block with collision-aware movement and a timeout.", Category.MOVEMENT); }
    @Override protected void resetSession() {
        if (!nullCheck()) {
            x = Math.floor(mc.player.getX()) + .5;
            z = Math.floor(mc.player.getZ()) + .5;
        }
        ticks = 0;
    }
    @Override protected void tick() {
        if (mc.screen != null || !mc.player.onGround() || mc.player.isFallFlying() || ++ticks > timeout.getValue()) { disable(); return; }
        double dx = x - mc.player.getX(), dz = z - mc.player.getZ(), distance = Math.hypot(dx, dz);
        if (distance < .03) { disable(); return; }
        double scale = Math.min(speed.getValue(), distance) / distance;
        Vec3 move = new Vec3(dx * scale, 0, dz * scale);
        if (!mc.level.noCollision(mc.player, mc.player.getBoundingBox().move(move))) { disable(); return; }
        mc.player.setDeltaMovement(move.x, mc.player.getDeltaMovement().y, move.z);
    }
}
