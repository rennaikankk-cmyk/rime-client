package dev.guizhou.util.elytra;
import dev.guizhou.util.PacketSendUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import static dev.guizhou.util.traits.Util.mc;
/** Minecraft 1.21.1 has no modern PlayerInput/end-tick glide handshake. */
public final class ElytraJumpInput {
    private ElytraJumpInput() {}
    public static boolean canStart(LocalPlayer player) { return player != null; }

    /** Caller must first validate equipment and complete tryToStartFallFlying(). */
    public static boolean sendStart(LocalPlayer player) {
        return player != null && mc.getConnection() != null && mc.isSameThread()
                && dev.guizhou.manager.HeartbeatManager.authorized()
                && PacketSendUtil.send(new ServerboundPlayerCommandPacket(player,
                    ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
    }
}
