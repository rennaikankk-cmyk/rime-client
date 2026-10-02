package dev.guizhou.util.player;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;

/** Shared "camera is inside solid geometry" probe used by Velocity PhaseOnly and wall overlays. */
public final class InsideBlockUtil {
    private InsideBlockUtil() {}

    /** True when the local player's hitbox intersects any block collision shape. */
    public static boolean isInsideBlock() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) return false;
        return level.getBlockCollisions(player, player.getBoundingBox()).iterator().hasNext();
    }
}
