package dev.guizhou.manager;

import dev.guizhou.event.impl.entity.TotemPopEvent;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.network.DisconnectEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.Feature;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerInfoManager extends Feature {

    private final Map<UUID, Integer> totemPops = new ConcurrentHashMap<>();
    private final Set<UUID> lastTickAlive = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastSeen = new ConcurrentHashMap<>();
    private static final long ABSENT_EXPIRY_MS = 5 * 60 * 1000L;

    public void init() {
        EVENT_BUS.register(this, "PlayerInfoManager");
    }

    @Subscribe
    public void onTotemPop(TotemPopEvent event) {
        totemPops.merge(event.getEntity().getUUID(), 1, Integer::sum);
    }

    @Subscribe
    public void onPreTick(PreTickEvent event) {
        if (mc.level == null || mc.player == null) return;

        Set<UUID> currentlyAlive = new HashSet<>();
        Set<UUID> presentIds = new HashSet<>();
        for (Player player : mc.level.players()) {
            UUID id = player.getUUID();
            presentIds.add(id);
            if (player.isDeadOrDying()) {
                if (lastTickAlive.contains(id)) {
                    totemPops.remove(id);
                }
            } else {
                currentlyAlive.add(id);
            }
        }

        // Leaving render distance is not a death: keep the count, expire it only after a long absence.
        long now = System.currentTimeMillis();
        for (UUID id : presentIds) lastSeen.put(id, now);
        totemPops.keySet().removeIf(id -> !presentIds.contains(id)
                && now - lastSeen.getOrDefault(id, 0L) > ABSENT_EXPIRY_MS);
        lastSeen.keySet().removeIf(id -> !totemPops.containsKey(id) && !presentIds.contains(id));
        lastTickAlive.clear();
        lastTickAlive.addAll(currentlyAlive);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        resetAll();
    }

    public int getTotemPops(UUID uuid) {
        return totemPops.getOrDefault(uuid, 0);
    }

    public void resetTotemPops(UUID uuid) {
        totemPops.remove(uuid);
    }

    public void resetAll() {
        totemPops.clear();
        lastTickAlive.clear();
        lastSeen.clear();
    }

    public ItemStack getEquipment(Player player, EquipmentSlot slot) {
        return player.getItemBySlot(slot);
    }

    public ItemStack getMainHandItem(Player player) {
        return player.getMainHandItem();
    }

    public ItemStack getOffHandItem(Player player) {
        return player.getOffhandItem();
    }
}
