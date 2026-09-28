package dev.guizhou.features.modules.render;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.awt.Color;

public class TablistModule extends Module {

    public Setting<Boolean> friends = bool("Friends", true);
    public Setting<Boolean> enemies = bool("Enemies", true);
    public final Setting<Color> friendColor = color("Friend Color", 155, 191, 166, 255);
    public final Setting<Color> enemyColor  = color("Enemy Color",  227, 154, 146, 255);

    public TablistModule() {
        super("Tablist", "Hides players in the tablist who aren't you, a friend, or an enemy", Category.RENDER);
    }

    public boolean shouldShow(PlayerInfo info) {
        String name = info.getProfile().getName();
        if (name == null) return false;
        if (mc.player != null && name.equalsIgnoreCase(mc.player.getGameProfile().getName())) return true;
        if (friends.getValue() && GuizhouWare.friendManager.isFriend(name)) return true;
        if (enemies.getValue() && GuizhouWare.enemyManager.isEnemy(name)) return true;
        return false;
    }
}
