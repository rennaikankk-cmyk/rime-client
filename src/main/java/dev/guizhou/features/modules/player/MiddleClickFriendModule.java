package dev.guizhou.features.modules.player;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.input.MouseInputEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import org.lwjgl.glfw.GLFW;

/** Middle-click a player to add or remove them as a friend. Takes precedence over MiddleClick's pearl. */
public class MiddleClickFriendModule extends Module {
    private static MiddleClickFriendModule INSTANCE;

    private final Setting<Boolean> whisper = bool("Whisper", false);

    public MiddleClickFriendModule() {
        super("MiddleClickFriend", "Middle-click a player to add or remove a friend. Whisper tells them "
                + "(off by default: it spams chat and reveals the client).", Category.PLAYER);
        INSTANCE = this;
    }

    /** True when a middle click right now would hit a player, so MiddleClick must not throw a pearl. */
    public static boolean consumesClick() {
        return INSTANCE != null && INSTANCE.isEnabled() && target() != null;
    }

    private static Player target() {
        if (nullCheck() || mc.screen != null) return null;
        return mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof Player player
                && player != mc.player ? player : null;
    }

    @Subscribe(priority = 10)
    private void onMouse(MouseInputEvent event) {
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_MIDDLE || event.getAction() != GLFW.GLFW_PRESS) return;
        Player player = target();
        if (player == null) return;
        String name = player.getGameProfile().name();
        if (GuizhouWare.friendManager.isFriend(name)) {
            GuizhouWare.friendManager.removeFriend(name);
            Command.sendMessage("Removed {red} %s {reset} from your friends", name);
        } else {
            GuizhouWare.friendManager.addFriend(name, dev.guizhou.manager.FriendManager.PERMANENT, whisper.getValue());
            Command.sendMessage("Added {green} %s {reset} to your friends", name);
        }
    }
}
