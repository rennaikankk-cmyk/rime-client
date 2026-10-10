package dev.guizhou.features.modules.qinghua.util;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Port of eternal PredictUtility — prediction math unchanged. */
public final class PredictUtility {
    private PredictUtility() {}

    public static Vec3 predictPosition(Entity entity, int ticks) {
        Vec3 velocity = entity.getDeltaMovement();
        Vec3 pos = entity.position();
        return new Vec3(pos.x + velocity.x * ticks, pos.y + velocity.y * ticks, pos.z + velocity.z * ticks);
    }

    public static AABB predictBox(Player player, int ticks) {
        Vec3 predictedPos = predictPosition(player, ticks);
        AABB box = player.getBoundingBox();
        double dx = predictedPos.x - player.getX();
        double dy = predictedPos.y - player.getY();
        double dz = predictedPos.z - player.getZ();
        return box.move(dx, dy, dz);
    }
}
