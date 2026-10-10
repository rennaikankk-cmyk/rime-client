package dev.guizhou.features.modules.qinghua;

import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.qinghua.util.TPUtil;
import dev.guizhou.features.settings.Setting;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;

/**
 * Port of eternal AntiLag — lagback cancel + TP-back logic unchanged.
 * PlayerPositionLookS2CPacket → ClientboundPlayerPositionPacket (1.21.11 absolute change).
 */
public class AntiLagModule extends Module {
    public static AntiLagModule INSTANCE;

    public final Setting<Double> range = num("Range", 200.0, 0.0, 256.0)
            .described("Max range. If the packet correction is farther than this, it is ignored.");
    public final Setting<Double> moveDistance = num("MoveDistance", 10.0, 0.0, 128.0)
            .described("Move distance per packet when sending TP back.");
    private final Setting<Boolean> debug = bool("Debug", false)
            .described("Print debug information about intercepted packets.");

    public AntiLagModule() {
        super("AntiLag", "lagback++.", Category.MOVEMENT);
        INSTANCE = this;
    }

    public static AntiLagModule getInstance() {
        return INSTANCE;
    }

    @Subscribe
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!(event.getPacket() instanceof ClientboundPlayerPositionPacket packet)) return;
        if (mc.player == null || mc.getConnection() == null) return;

        PositionMoveRotation absolute = PositionMoveRotation.calculateAbsolute(
                PositionMoveRotation.of(mc.player), packet.change(), packet.relatives());
        Vec3 packetPos = absolute.position();
        Vec3 playerPos = mc.player.position();
        double dis = packetPos.distanceTo(playerPos);
        if (dis >= this.range.getValue()) return;

        event.cancel();
        mc.getConnection().send(new ServerboundAcceptTeleportationPacket(packet.id()));
        if (this.debug.getValue()) {
            // keep original info intent without Meteor this.info
            System.out.println("[AntiLag] Blocked lagback -- packetPos: "
                    + String.format("%.1f, %.1f, %.1f", packetPos.x, packetPos.y, packetPos.z));
        }
        double md = this.moveDistance.getValue() > 0.0 ? this.moveDistance.getValue() : Double.MAX_VALUE;
        TPUtil.doTp(packetPos, playerPos, md, false);
    }
}
