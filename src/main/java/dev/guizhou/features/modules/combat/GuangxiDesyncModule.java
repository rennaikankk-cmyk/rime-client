package dev.guizhou.features.modules.combat;

import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * One-shot client-side position desync (Mio HitboxDesync port): moves our client position
 * a hair beyond the block center along our facing axis, then disables. Enemy crystal auras
 * that read our position for damage math now aim at a spot we are not standing on.
 */
public class GuangxiDesyncModule extends Module {

    /** Nudges just past the block centre — far enough to shift hitboxes, small enough to look legal. */
    private static final double PUSH = 0.20000996883537;

    public GuangxiDesyncModule() {
        super("GuangxiDesync", "One-shot hitbox desync: enemy crystal auras read a stale position.", Category.COMBAT);
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (nullCheck() || mc.player == null) return;

        Direction facing = mc.player.getDirection();
        Vec3 blockCenter = Vec3.atCenterOf(
                BlockPos.containing(mc.player.getBoundingBox().getCenter()));

        // Only the facing-axis coordinate moves; the others keep the real player coordinate.
        Vec3 pushed = blockCenter.add(
                facing.getStepX() * PUSH, 0.0, facing.getStepZ() * PUSH);
        double x = facing.getStepX() == 0 ? mc.player.getX() : pushed.x;
        double z = facing.getStepZ() == 0 ? mc.player.getZ() : pushed.z;

        mc.player.setPos(x, mc.player.getY(), z);
        disable();
    }
}
