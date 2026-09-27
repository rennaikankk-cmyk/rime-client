package dev.guizhou.util.combat;

import dev.guizhou.util.traits.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Predicate;

/** Bounded search for a reachable, unoccupied interaction position with acceptable blast risk. */
public final class TeleportBlastPosition implements Util {
    private TeleportBlastPosition() {}

    public static Vec3 find(Vec3 source, AABB interaction, double reach, Predicate<Vec3> safe) {
        if (mc.player == null || mc.level == null) return null;
        var candidates = new ArrayList<Vec3>();
        candidates.add(mc.player.position());
        candidates.add(source.add(0, 2.2, 0));
        double radius = reach + (interaction.getXsize() > 1 ? .7 : -.8);
        for (int i = 0; i < 16; i++) {
            double angle = i * Math.PI / 8;
            candidates.add(source.add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius));
        }
        candidates.sort(Comparator.comparingDouble(p -> p.distanceToSqr(mc.player.position())));
        for (Vec3 feet : candidates) {
            AABB box = mc.player.getBoundingBox().move(feet.subtract(mc.player.position()));
            Vec3 eye = feet.add(0, mc.player.getEyeHeight(), 0);
            if (interaction.distanceToSqr(eye) > reach * reach
                    || !mc.level.hasChunkAt(BlockPos.containing(feet))
                    || !mc.level.getWorldBorder().isWithinBounds(box)
                    || !mc.level.noCollision(mc.player, box) || !safe.test(feet)) continue;
            return feet;
        }
        return null;
    }
}
