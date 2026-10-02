package dev.guizhou.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.guizhou.GuizhouWare;
import dev.guizhou.util.traits.Jsonable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FriendManager implements Jsonable {

    public static final long PERMANENT = -1L;

    private final Map<String, Long> friends = new LinkedHashMap<>();
    /**
     * Whisper "added as a friend" to the player. Off by default: it floods chat on strict
     * servers and tells the other player you run a client. Batch adds never whisper.
     */
    private boolean whisperOnAdd = false;

    public void init() {
        GuizhouWare.configManager.addConfig(this);
    }

    public boolean isFriend(String name) {
        purgeExpired();
        return friends.keySet().stream().anyMatch(friend -> friend.equalsIgnoreCase(name));
    }

    public boolean isFriend(Player player) {
        return this.isFriend(player.getGameProfile().getName());
    }

    public void addFriend(String name) {
        this.addFriend(name, PERMANENT);
    }

    public void addFriend(String name, long expiry) {
        addFriend(name, expiry, whisperOnAdd);
    }

    public void addFriend(String name, long expiry, boolean whisper) {

        boolean alreadyFriend = friends.keySet().stream().anyMatch(friend -> friend.equalsIgnoreCase(name));

        friends.keySet().removeIf(friend -> friend.equalsIgnoreCase(name));
        friends.put(name, expiry);

        GuizhouWare.enemyManager.removeEnemy(name);

        if (!alreadyFriend && whisper) {
            whisperFriendAdded(name);
        }
        GuizhouWare.configManager.markDirty();
    }

    public boolean isWhisperOnAdd() {
        return whisperOnAdd;
    }

    public void setWhisperOnAdd(boolean whisperOnAdd) {
        this.whisperOnAdd = whisperOnAdd;
        GuizhouWare.configManager.markDirty();
    }

    private void whisperFriendAdded(String name) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) return;
        connection.sendCommand("w " + name + " Added as a friend on client");
    }

    public void removeFriend(String name) {
        friends.keySet().removeIf(s -> s.equalsIgnoreCase(name));
        GuizhouWare.configManager.markDirty();
    }

    public void clearFriends() {
        friends.clear();
        GuizhouWare.configManager.markDirty();
    }

    public long getExpiry(String name) {
        for (Map.Entry<String, Long> entry : friends.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return PERMANENT;
    }

    public List<String> getFriends() {
        purgeExpired();
        return new ArrayList<>(friends.keySet());
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        friends.values().removeIf(expiry -> expiry != PERMANENT && expiry <= now);
    }

    @Override
    public JsonElement toJson() {
        purgeExpired();
        JsonObject object = new JsonObject();
        JsonArray array = new JsonArray();
        for (Map.Entry<String, Long> entry : friends.entrySet()) {
            JsonObject element = new JsonObject();
            element.addProperty("name", entry.getKey());
            element.addProperty("expiry", entry.getValue());
            array.add(element);
        }
        object.add("friends", array);
        object.addProperty("whisperOnAdd", whisperOnAdd);
        return object;
    }

    /** Loading a file or preset replaces the list; merging made preset switches accumulate friends. */
    @Override
    public void fromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) return;
        JsonObject root = element.getAsJsonObject();
        if (root.has("whisperOnAdd")) whisperOnAdd = root.get("whisperOnAdd").getAsBoolean();
        if (!root.has("friends") || !root.get("friends").isJsonArray()) return;
        friends.clear();
        for (JsonElement e : root.get("friends").getAsJsonArray()) {
            if (e.isJsonObject()) {
                JsonObject obj = e.getAsJsonObject();
                long expiry = obj.has("expiry") ? obj.get("expiry").getAsLong() : PERMANENT;
                String name = obj.get("name").getAsString();
                friends.keySet().removeIf(friend -> friend.equalsIgnoreCase(name));
                friends.put(name, expiry);
            } else {
                String name = e.getAsString();
                friends.keySet().removeIf(friend -> friend.equalsIgnoreCase(name));
                friends.put(name, PERMANENT);
            }
        }
        purgeExpired();
    }

    @Override
    public String getFileName() {
        return "friends.json";
    }
}
