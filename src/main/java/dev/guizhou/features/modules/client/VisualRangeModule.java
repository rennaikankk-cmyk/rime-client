package dev.guizhou.features.modules.client;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.network.DisconnectEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.combat.AutoLogModule;
import dev.guizhou.features.modules.hud.NotifierHudModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.player.ChatUtil;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Announces players entering and leaving render distance; can AutoLog when a stranger appears. */
public class VisualRangeModule extends Module {
    private final Setting<Boolean> leave = bool("Leave", true);
    private final Setting<Boolean> friends = bool("Friends", false);
    private final Setting<Boolean> sound = bool("Sound", true);
    private final Setting<Boolean> ignoreFake = bool("IgnoreFakePlayers", true);
    private final Setting<Boolean> autoLog = bool("AutoLog", false);
    private final Setting<Boolean> autoLogEnemiesOnly = bool("AutoLogEnemiesOnly", false);

    private final Map<UUID, String> present = new HashMap<>();
    private Object sessionLevel;

    public VisualRangeModule() {
        super("VisualRange", "Notifies when players enter or leave your render distance. AutoLog disconnects when a "
                + "non-friend appears (never reconnected by AutoReconnect).", Category.CLIENT);
        autoLogEnemiesOnly.setVisibility(v -> autoLog.getValue());
    }

    @Override
    public void onEnable() {
        present.clear();
        sessionLevel = null;
    }

    @Override
    public void onDisable() {
        present.clear();
    }

    @Subscribe
    private void onDisconnect(DisconnectEvent event) {
        present.clear();
        sessionLevel = null;
    }

    @Override
    public void onTick() {
        if (nullCheck()) return;
        // A new world/dimension fills render distance at once: seed silently instead of spamming.
        boolean seeding = sessionLevel != mc.level;
        sessionLevel = mc.level;
        if (seeding) present.clear();
        Set<UUID> seen = new HashSet<>();
        for (Player player : mc.level.players()) {
            if (player == mc.player) continue;
            if (ignoreFake.getValue() && mc.getConnection() != null && mc.getConnection().getPlayerInfo(player.getUUID()) == null) continue;
            UUID id = player.getUUID();
            seen.add(id);
            if (present.containsKey(id)) continue;
            String name = player.getGameProfile().getName();
            present.put(id, name);
            if (seeding) continue;
            boolean friend = GuizhouWare.friendManager.isFriend(player);
            if (friend && !friends.getValue()) continue;
            boolean enemy = GuizhouWare.enemyManager.isEnemy(player);
            announce(id, name, true, friend, enemy);
            if (autoLog.getValue() && !friend && (!autoLogEnemiesOnly.getValue() || enemy)) {
                AutoLogModule.logOut("VisualRange: " + name);
                return;
            }
        }
        present.entrySet().removeIf(entry -> {
            if (seen.contains(entry.getKey())) return false;
            if (leave.getValue()) {
                boolean friend = GuizhouWare.friendManager.isFriend(entry.getValue());
                if (!friend || friends.getValue()) {
                    announce(entry.getKey(), entry.getValue(), false, friend, GuizhouWare.enemyManager.isEnemy(entry.getValue()));
                }
            }
            return true;
        });
    }

    private void announce(UUID id, String name, boolean entered, boolean friend, boolean enemy) {
        String tag = enemy ? dev.guizhou.util.lang.ClientLanguage.text(" (仇人)") : friend ? dev.guizhou.util.lang.ClientLanguage.text(" (好友)") : "";
        String text = name + tag + (entered ? dev.guizhou.util.lang.ClientLanguage.text(" 进入了视距") : dev.guizhou.util.lang.ClientLanguage.text(" 离开了视距"));
        int color = enemy ? 0xFF5555 : friend ? 0x55FF55 : entered ? 0xFFFF55 : 0xAAAAAA;
        ChatUtil.sendPersistent("visual:" + id, Component.literal(text).withColor(color));
        NotifierHudModule.push("visual:" + id, entered && !friend ? NotifierHudModule.Icon.DANGER : NotifierHudModule.Icon.INFO, text);
        if (sound.getValue() && entered && !friend) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f));
        }
    }

    @Override
    public String getDisplayInfo() {
        return present.isEmpty() ? null : String.valueOf(present.size());
    }
}
