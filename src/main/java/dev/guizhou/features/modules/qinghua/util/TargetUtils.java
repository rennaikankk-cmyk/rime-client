package dev.guizhou.features.modules.qinghua.util;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.client.TargetsModule;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Port of eternal TargetUtils — selection logic unchanged. */
public final class TargetUtils {
    public static final List<Entity> ENTITIES = new ArrayList<>();

    private TargetUtils() {}

    /** Rime Targets module (Players / Hostiles / Neutrals / Passives / ArmorStands). */
    public static boolean matchesSelector(Entity entity) {
        if (entity == null || entity == Minecraft.getInstance().player) return false;
        if (!(entity instanceof LivingEntity living) || living.isDeadOrDying()) return false;
        if (GuizhouWare.moduleManager == null) return entity instanceof Player;
        TargetsModule targets = GuizhouWare.moduleManager.getModuleByClass(TargetsModule.class);
        return targets == null || targets.isValidTarget(entity);
    }

    public static Entity get(Predicate<Entity> isGood, SortPriority sortPriority) {
        ENTITIES.clear();
        getList(ENTITIES, isGood, sortPriority, 1);
        return !ENTITIES.isEmpty() ? ENTITIES.get(0) : null;
    }

    public static void getList(List<Entity> targetList, Predicate<Entity> isGood, SortPriority sortPriority, int maxCount) {
        targetList.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.player != null) {
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity != null && isGood.test(entity)) {
                    targetList.add(entity);
                }
            }
            targetList.sort(sortPriority);
            for (int i = targetList.size() - 1; i >= maxCount; i--) {
                targetList.remove(i);
            }
        }
    }
}
