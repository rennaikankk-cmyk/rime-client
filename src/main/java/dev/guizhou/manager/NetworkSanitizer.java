package dev.guizhou.manager;

import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.Feature;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

/**
 * Drops redundant sprint-state packets (Mio trick): the server only accepts
 * START/STOP_SPRINTING as a state toggle, so a duplicate gives an "illegal sprint state"
 * kick on strict setups. We track what the server last saw and cancel repeats;
 * a server teleport resets the assumption conservatively.
 */
public class NetworkSanitizer extends Feature {

    private boolean serverSprinting = false;

    public void init() {
        EVENT_BUS.register(this, "NetworkSanitizer");
    }

    @Subscribe
    public void onPacketSend(PacketEvent.Send event) {
        if (!(event.getPacket() instanceof ServerboundPlayerCommandPacket cmd)) return;

        switch (cmd.getAction()) {
            case START_SPRINTING -> {
                if (serverSprinting) {
                    event.cancel();
                    return;
                }
                serverSprinting = true;
            }
            case STOP_SPRINTING -> {
                if (!serverSprinting) {
                    event.cancel();
                    return;
                }
                serverSprinting = false;
            }
            default -> { }
        }
    }

    @Subscribe
    public void onPacketReceive(PacketEvent.Receive event) {
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            serverSprinting = false;
        }
    }

    public boolean isServerSprinting() {
        return serverSprinting;
    }
}
