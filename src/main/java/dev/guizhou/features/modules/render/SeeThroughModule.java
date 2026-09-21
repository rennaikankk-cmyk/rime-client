package dev.guizhou.features.modules.render;

import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.projectile.Projectile;

public class SeeThroughModule extends Module {

    public Setting<Boolean> players      = bool("Players",     true).setPage("Entities");
    public Setting<Float>   playersRange = num("Players Range", 64.0f, 4.0f, 256.0f).setPage("Entities").setVisibility(v -> players.getValue());

    public Setting<Boolean> monsters      = bool("Monsters",    false).setPage("Entities");
    public Setting<Float>   monstersRange = num("Monsters Range", 64.0f, 4.0f, 256.0f).setPage("Entities").setVisibility(v -> monsters.getValue());

    public Setting<Boolean> animals      = bool("Animals",     false).setPage("Entities");
    public Setting<Float>   animalsRange = num("Animals Range", 64.0f, 4.0f, 256.0f).setPage("Entities").setVisibility(v -> animals.getValue());

    public Setting<Boolean> items      = bool("Items",        true).setPage("Entities");
    public Setting<Float>   itemsRange = num("Items Range", 64.0f, 4.0f, 256.0f).setPage("Entities").setVisibility(v -> items.getValue());

    public Setting<Boolean> crystals      = bool("Crystals",     false).setPage("Entities");
    public Setting<Float>   crystalsRange = num("Crystals Range", 64.0f, 4.0f, 256.0f).setPage("Entities").setVisibility(v -> crystals.getValue());

    public Setting<Boolean> projectiles      = bool("Projectiles",  false).setPage("Entities");
    public Setting<Float>   projectilesRange = num("Projectiles Range", 64.0f, 4.0f, 256.0f).setPage("Entities").setVisibility(v -> projectiles.getValue());

    public SeeThroughModule() {
        super("SeeThrough", "Render selected entities through walls", Category.RENDER);
    }

    /** 1.21.1: works directly on the entity; distance measured against the camera. */
    public boolean shouldSeeThrough(Entity entity) {
        if (entity == null) return false;

        EntityType<?> type = entity.getType();

        if (type == EntityType.PLAYER)      return inRange(entity, players, playersRange);
        if (type == EntityType.ITEM)        return inRange(entity, items, itemsRange);
        if (type == EntityType.END_CRYSTAL) return inRange(entity, crystals, crystalsRange);
        if (Projectile.class.isAssignableFrom(type.getBaseClass())) return inRange(entity, projectiles, projectilesRange);
        if (type.getCategory() == MobCategory.MONSTER) return inRange(entity, monsters, monstersRange);
        if (type.getCategory() != MobCategory.MISC)    return inRange(entity, animals, animalsRange);
        return false;
    }

    private boolean inRange(Entity entity, Setting<Boolean> enabled, Setting<Float> range) {
        if (!enabled.getValue()) return false;
        float r = range.getValue();
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        return entity.position().distanceToSqr(camera.getPosition()) <= (double) r * r;
    }
}
