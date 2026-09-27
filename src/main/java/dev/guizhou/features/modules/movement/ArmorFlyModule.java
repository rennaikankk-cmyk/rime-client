package dev.guizhou.features.modules.movement;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.HeartbeatManager;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;

import static dev.guizhou.util.traits.Util.mc;

/** Equipment ownership is independent of the optional flight direction controller. */
public final class ArmorFlyModule extends Module {
    private static ArmorFlyModule instance;
    private final Setting<Boolean> autoRocket = bool("AutoRocket", true);
    /** 0 leaves entity-tracker updates alone. A positive value drops our own tracker packet for that many ticks after glide starts. */
    private final Setting<Integer> glideTrackerDelay = num("GlideTrackerDelay", 0, 0, 40);

    public ArmorFlyModule() {
        super("ArmorFly", "Independent armor/elytra switching while gliding; keeps manual steering.", Category.MOVEMENT);
        displayName.setValue("甲飞");
        instance = this;
    }

    public static boolean requested() {
        return instance != null && instance.isEnabled() && HeartbeatManager.authorized();
    }

    @Override public void onEnable() {
        var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        if (flight != null) flight.monitorArmorFly();
    }

    @Override public void onDisable() { release(); }

    @Subscribe(priority = 200)
    private void onPreTick(PreTickEvent event) {
        if (!HeartbeatManager.authorized()) {
            HeartbeatManager.notifyDeniedOnce();
            return;
        }
        var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        if (flight != null) flight.monitorArmorFly();
    }

    private void release() {
        var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        if (flight != null) flight.releaseArmorFly();
    }

    @Subscribe(priority = -150)
    private void onFlightWindow(PreTickEvent event) {
        if (!HeartbeatManager.authorized()) { HeartbeatManager.notifyDeniedOnce(); return; }
        var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        if (flight != null) flight.serviceIndependentArmorFly(autoRocket.getValue());
    }

    @Subscribe
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket
                || event.getPacket() instanceof ClientboundRespawnPacket) {
            var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
            if (flight != null) flight.independentArmorCorrection();
        }
        int delay = glideTrackerDelay.getValue();
        if (delay <= 0 || !isEnabled() || !(event.getPacket() instanceof ClientboundSetEntityDataPacket data)) return;
        var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        if (flight == null || mc.player == null || data.id() != mc.player.getId()) return;
        if (flight.ticksSinceGlideCommand() < delay) event.cancel();
    }
}
