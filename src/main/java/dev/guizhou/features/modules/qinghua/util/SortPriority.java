package dev.guizhou.features.modules.qinghua.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.Comparator;

/** Subset of Meteor SortPriority used by qinghua ports. */
public enum SortPriority implements Comparator<Entity> {
    LowestDistance,
    HighestDistance,
    LowestHealth,
    HighestHealth;

    @Override
    public int compare(Entity a, Entity b) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        return switch (this) {
            case LowestDistance -> Double.compare(mc.player.distanceToSqr(a), mc.player.distanceToSqr(b));
            case HighestDistance -> Double.compare(mc.player.distanceToSqr(b), mc.player.distanceToSqr(a));
            case LowestHealth -> {
                float ha = a instanceof net.minecraft.world.entity.LivingEntity la ? la.getHealth() : 0;
                float hb = b instanceof net.minecraft.world.entity.LivingEntity lb ? lb.getHealth() : 0;
                yield Float.compare(ha, hb);
            }
            case HighestHealth -> {
                float ha = a instanceof net.minecraft.world.entity.LivingEntity la ? la.getHealth() : 0;
                float hb = b instanceof net.minecraft.world.entity.LivingEntity lb ? lb.getHealth() : 0;
                yield Float.compare(hb, ha);
            }
        };
    }
}
