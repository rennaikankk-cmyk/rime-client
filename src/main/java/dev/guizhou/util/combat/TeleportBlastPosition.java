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
        // Three rings stepping inward from the furthest legal radius, staggered so the inner
        // rings sample between the outer angles, one to two blocks higher and one lower: blast
        // falloff and terrain occlusion vary enough across a single block that a single flat
        // ring used to refuse positions that are actually safe to stand on.
        double outer = reach + (interaction.getXsize() > 1 ? .7 : -.8);
        double[] radii = {outer, outer - .7, outer - 1.4};
        for (int ring = 0; ring < radii.length; ring++) {
            if (radii[ring] <= 0.3) continue;
            int[] heights = ring == 0 ? new int[]{0, 1, -1} : new int[]{0, 1};
            for (int height : heights) {
                for (int i = 0; i < 16; i++) {
                    double angle = i * Math.PI / 8 + ring * (Math.PI / 32);
                    candidates.add(source.add(Math.cos(angle) * radii[ring], height, Math.sin(angle) * radii[ring]));
                }
            }
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
