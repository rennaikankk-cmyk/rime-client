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

/** Equipment ownership is independent of the optional flight direction controller. */
public final class ArmorFlyModule extends Module {
    private static ArmorFlyModule instance;
    private final Setting<Boolean> autoRocket = bool("AutoRocket", true);

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
    }
}
