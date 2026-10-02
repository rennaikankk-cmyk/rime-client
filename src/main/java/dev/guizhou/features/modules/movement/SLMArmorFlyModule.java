package dev.guizhou.features.modules.movement;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.HeartbeatManager;
import dev.guizhou.util.compat.ClientCoexistence;
import dev.guizhou.util.compat.ForeignClientProbe.Domain;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;

/** SlimefunHelper-style server-paced armor windows; shares the existing equipment owner. */
public final class SLMArmorFlyModule extends Module {
    private static SLMArmorFlyModule instance;
    private final Setting<Boolean> autoRocket = bool("AutoRocket", true);
    private final Setting<Boolean> cruisePriority = bool("CruisePriority", true);
    private final Setting<Boolean> autoEquip = bool("AutoEquip", true);
    private final Setting<Integer> minimumDurability = num("MinimumDurability", 5, 2, 100);
    private final Setting<Boolean> landAutoDisable = bool("LandAutoDisable", false);
    private final dev.guizhou.util.elytra.ArmorFlightHandoff handoff = new dev.guizhou.util.elytra.ArmorFlightHandoff();
    private Object player, level;
    public SLMArmorFlyModule() {
        super("SLMArmorFly", "Server-paced armor flight with inventory elytra lookup and durability reserve.", Category.MOVEMENT);
        instance = this;
    }
    public static boolean selected() { return instance != null && instance.isEnabled(); }
    public static boolean requested() { return selected() && HeartbeatManager.authorized() && !ClientCoexistence.blocks(Domain.ARMOR); }
    public static boolean autoEquip() { return requested() && instance.autoEquip.getValue(); }
    public static int minimumDurability() { return selected() ? instance.minimumDurability.getValue() : 2; }
    public static boolean yieldsToCruise() {
        var flight = flight();
        return requested() && instance.cruisePriority.getValue() && flight != null && flight.gzBoostControlsLook();
    }
    private static GuangxiElytraModule flight() {
        return GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
    }
    @Override public void onEnable() {
        var old = GuizhouWare.moduleManager.getModuleByClass(ArmorFlyModule.class);
        if (old != null && old.isEnabled()) old.disable();
        handoff.reset(); player = mc.player; level = mc.level;
        if (flight() != null) flight().monitorArmorFly();
    }
    @Override public void onDisable() {
        handoff.reset();
        if (flight() != null) flight().releaseArmorFly();
    }
    @Subscribe(priority = 200)
    private void onPreTick(PreTickEvent event) {
        if (!HeartbeatManager.authorized()) { HeartbeatManager.notifyDeniedOnce(); return; }
        if (player != mc.player || level != mc.level) { handoff.reset(); player = mc.player; level = mc.level; }
        if (mc.player == null) return;
        if (handoff.wasAirborne() && mc.player.onGround() && landAutoDisable.getValue()) { disable(); return; }
        handoff.observe(mc.player.isFallFlying(), mc.player.onGround(), ClientCoexistence.flightReason() != null);
        if (flight() != null) flight().monitorArmorFly();
    }
    @Subscribe(priority = -150)
    private void onFlightWindow(PreTickEvent event) {
        if (!HeartbeatManager.authorized()) { HeartbeatManager.notifyDeniedOnce(); return; }
        if (flight() != null) flight().serviceIndependentArmorFly(autoRocket.getValue(), handoff.resumeRequested());
    }
    @Subscribe private void onPacket(PacketEvent.Receive event) {
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket || event.getPacket() instanceof ClientboundRespawnPacket) {
            mc.execute(handoff::reset);
            if (flight() != null) flight().independentArmorCorrection();
        }
    }
    @Override public String getDisplayInfo() {
        String foreign = ClientCoexistence.reason(Domain.ARMOR);
        if (foreign != null) return dev.guizhou.util.lang.ClientLanguage.text("让行：") + foreign;
        if (yieldsToCruise()) return dev.guizhou.util.lang.ClientLanguage.text("巡航优先");
        return "SLM";
    }
}
