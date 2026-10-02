package dev.guizhou.manager;

import dev.guizhou.event.impl.network.DisconnectEvent;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.Feature;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

/**
 * Drops redundant sprint-state packets (Mio trick): the server only accepts
 * START/STOP_SPRINTING as a state toggle, so a duplicate gives an "illegal sprint state"
 * kick on strict setups. We track what the server last saw and cancel repeats.
 *
 * <p>A position correction does not reset the server's sprint flag, so after one the state is
 * unknown rather than "not sprinting": the next START or STOP is always let through and
 * re-establishes the tracked state. A fresh login or respawn really starts non-sprinting.
 */
public class NetworkSanitizer extends Feature {

    private enum SprintState { UNKNOWN, SPRINTING, WALKING }

    private volatile SprintState serverSprint = SprintState.WALKING;

    public void init() {
        EVENT_BUS.register(this, "NetworkSanitizer");
    }

    @Subscribe
    public void onPacketSend(PacketEvent.Send event) {
        if (!(event.getPacket() instanceof ServerboundPlayerCommandPacket cmd)) return;

        switch (cmd.getAction()) {
            case START_SPRINTING -> {
                if (serverSprint == SprintState.SPRINTING) {
                    event.cancel();
                    return;
                }
                serverSprint = SprintState.SPRINTING;
            }
            case STOP_SPRINTING -> {
                if (serverSprint == SprintState.WALKING) {
                    event.cancel();
                    return;
                }
                serverSprint = SprintState.WALKING;
            }
            default -> { }
        }
    }

    @Subscribe
    public void onPacketReceive(PacketEvent.Receive event) {
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            serverSprint = SprintState.UNKNOWN;
        } else if (event.getPacket() instanceof ClientboundLoginPacket
                || event.getPacket() instanceof ClientboundRespawnPacket) {
            serverSprint = SprintState.WALKING;
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        serverSprint = SprintState.WALKING;
    }

    /** Only a confirmed START counts; callers treat an unknown state as not sprinting. */
    public boolean isServerSprinting() {
        return serverSprint == SprintState.SPRINTING;
    }
}
