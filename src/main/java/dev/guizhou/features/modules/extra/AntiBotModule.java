package dev.guizhou.features.modules.extra;

import dev.guizhou.features.settings.Setting;
import net.minecraft.world.entity.player.Player;

public final class AntiBotModule extends UtilityModule {
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_]{1,16}");
    private final Setting<Boolean> tabList = bool("TabList", true), invalidName = bool("InvalidName", true);
    public AntiBotModule() { super("AntiBot", "Filters non-tab or invalid-name players through Rime's shared target policy.", Category.COMBAT); }
    @Override protected void tick() {}
    public boolean bot(Player player) {
        if (!permitted()) return false;
        String name = player.getGameProfile().getName();
        return invalidName.getValue() && !NAME.matcher(name).matches()
                || tabList.getValue() && mc.getConnection() != null && mc.getConnection().getPlayerInfo(player.getUUID()) == null;
    }
}
