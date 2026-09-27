package dev.guizhou.util.combat;

import dev.guizhou.GuizhouWare;
import net.minecraft.world.phys.Vec3;
import static dev.guizhou.util.traits.Util.mc;

/** Environment checks shared by manual and automatic utility throws. */
public final class ThrowEnvironment {
    private ThrowEnvironment() {}
    public static boolean nearEnemy(double range) {
        if (mc.player == null || mc.level == null) return false;
        return mc.level.players().stream().anyMatch(p -> p != mc.player && p.isAlive() && !p.isSpectator()
                && !GuizhouWare.friendManager.isFriend(p) && p.distanceToSqr(mc.player) <= range * range);
    }
    public static boolean accepts(boolean ground, boolean ceiling, boolean rising, boolean airborne, double gap) {
        if (mc.player == null || mc.level == null) return false;
        var box = mc.player.getBoundingBox();
        boolean floor = ground && (mc.player.onGround() || !mc.level.noCollision(mc.player, box.move(0, -gap, 0)));
        boolean roof = ceiling && !mc.level.noCollision(mc.player, box.move(0, gap, 0));
        return floor || roof || rising && steepAscent(mc.player.getDeltaMovement())
                || airborne && mc.player.isFallFlying();
    }
    public static boolean steepAscent(Vec3 motion) {
        return motion.y > 0.15 && motion.y > motion.horizontalDistance() * 5.67;
    }
}
