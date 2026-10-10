package dev.guizhou.features.modules.qinghua.util;

import dev.guizhou.GuizhouWare;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class CombatUtils {
    private CombatUtils() {}

    public static List<Player> getTargets(float range) {
        List<Player> targets = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return targets;

        for (Player player : mc.level.players()) {
            if (player == mc.player
                    || player.isDeadOrDying()
                    || !player.isAlive()
                    || player.getHealth() <= 0.0F
                    || GuizhouWare.friendManager.isFriend(player)
                    || mc.player.distanceTo(player) > range) {
                continue;
            }
            targets.add(player);
        }

        targets.sort(Comparator.comparingDouble(p -> mc.player.distanceTo(p)));
        return targets;
    }

    public static AABB createBox(Vec3 pos, Entity entity) {
        AABB eBox = entity.getBoundingBox();
        double hw = eBox.getXsize() / 2.0;
        double h = eBox.getYsize();
        double hd = eBox.getZsize() / 2.0;
        return new AABB(pos.x - hw, pos.y, pos.z - hd, pos.x + hw, pos.y + h, pos.z + hd);
    }

    public static Player getNearestTarget(float range) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return null;

        Player nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (Player player : mc.level.players()) {
            if (player == mc.player
                    || player.isDeadOrDying()
                    || !player.isAlive()
                    || player.getHealth() <= 0.0F
                    || GuizhouWare.friendManager.isFriend(player)) {
                continue;
            }
            double dist = mc.player.distanceTo(player);
            if (dist > range || dist >= nearestDist) continue;
            nearestDist = dist;
            nearest = player;
        }

        return nearest;
    }

    public static int remainingArmorCount(Player player) {
        int n = 0;
        for (var slot : new net.minecraft.world.entity.EquipmentSlot[]{
                net.minecraft.world.entity.EquipmentSlot.HEAD,
                net.minecraft.world.entity.EquipmentSlot.CHEST,
                net.minecraft.world.entity.EquipmentSlot.LEGS,
                net.minecraft.world.entity.EquipmentSlot.FEET}) {
            if (!player.getItemBySlot(slot).isEmpty()) n++;
        }
        return n;
    }

    public static int findItem(net.minecraft.world.item.Item item) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return -1;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).is(item)) return i;
        }
        return -1;
    }
}
