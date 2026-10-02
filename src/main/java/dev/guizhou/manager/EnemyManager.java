package dev.guizhou.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.guizhou.GuizhouWare;
import dev.guizhou.util.traits.Jsonable;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

public class EnemyManager implements Jsonable {
    private final List<String> enemies = new ArrayList<>();

    public void init() {
        GuizhouWare.configManager.addConfig(this);
    }

    public boolean isEnemy(String name) {
        return this.enemies.stream().anyMatch(enemy -> enemy.equalsIgnoreCase(name));
    }

    public boolean isEnemy(Player player) {
        return this.isEnemy(player.getGameProfile().getName());
    }

    public void addEnemy(String name) {
        enemies.removeIf(s -> s.equalsIgnoreCase(name));
        this.enemies.add(name);
        GuizhouWare.configManager.markDirty();

        GuizhouWare.friendManager.removeFriend(name);
    }

    public void removeEnemy(String name) {
        enemies.removeIf(s -> s.equalsIgnoreCase(name));
    }

    public void clearEnemies() {
        enemies.clear();
    }

    public List<String> getEnemies() {
        return this.enemies;
    }

    @Override
    public JsonElement toJson() {
        JsonObject object = new JsonObject();
        JsonArray array = new JsonArray();
        for (String enemy : enemies) {
            array.add(enemy);
        }
        object.add("enemies", array);
        return object;
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element == null || !element.isJsonObject() || !element.getAsJsonObject().has("enemies")) return;
        // Replace, and drop case-insensitive duplicates left behind by older merging loads.
        enemies.clear();
        for (JsonElement e : element.getAsJsonObject().get("enemies").getAsJsonArray()) {
            String name = e.getAsString();
            if (!isEnemy(name)) enemies.add(name);
        }
    }

    @Override
    public String getFileName() {
        return "enemies.json";
    }
}
