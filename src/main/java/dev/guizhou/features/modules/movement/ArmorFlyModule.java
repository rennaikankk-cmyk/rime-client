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

import static dev.guizhou.util.traits.Util.mc;

/** Equipment ownership is independent of the optional flight direction controller. */
public final class ArmorFlyModule extends Module {
    private static ArmorFlyModule instance;
    private final Setting<Boolean> autoRocket = bool("AutoRocket", true);
    private final Setting<Boolean> cruisePriority = bool("CruisePriority", true)
            .described("GZBoost 手动巡航时保持鞘翅，空战接管后再恢复甲飞换装。关闭后会牺牲部分巡航稳定性。");
    private final Setting<Integer> glideTrackerDelay = num("GlideTrackerDelay", 0, 0, 40)
            .described("独立甲飞的实验滑翔延迟，单位 tick，0 关闭。GZBoost 和下砸时不延迟；触发回拉后本次连接停用。");
    private final dev.guizhou.util.elytra.GlideMetadataDelay metadata = new dev.guizhou.util.elytra.GlideMetadataDelay();
    private Object metadataPlayer, metadataLevel;
    private int lastStartTick = Integer.MIN_VALUE;
    private int lastDelayedTick = Integer.MIN_VALUE;
    private boolean delayBlocked;

    public ArmorFlyModule() {
        super("ArmorFly", "Independent armor/elytra switching while gliding; keeps manual steering.", Category.MOVEMENT);
        displayName.setValue("甲飞");
        instance = this;
        dev.guizhou.util.PacketSendUtil.addSubmissionListener(packet -> {
            if (mc.isSameThread() && requested() && mc.player != null
                    && packet instanceof net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket command
                    && command.getId() == mc.player.getId()
                    && command.getAction() == net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.START_FALL_FLYING) {
                ensureMetadataSession();
                lastStartTick = mc.player.tickCount;
            }
        });
    }

    public static boolean requested() {
        return instance != null && instance.isEnabled() && HeartbeatManager.authorized();
    }

    public static boolean yieldsToCruise() {
        if (!requested() || !instance.cruisePriority.getValue()) return false;
        var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        return flight != null && flight.gzBoostControlsLook();
    }

    @Override public void onEnable() {
        var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        if (flight != null) flight.monitorArmorFly();
    }

    @Override public void onDisable() { flushMetadata(); release(); }

    @Subscribe(priority = 200)
    private void onPreTick(PreTickEvent event) {
        if (!HeartbeatManager.authorized()) {
            flushMetadata();
            HeartbeatManager.notifyDeniedOnce();
            return;
        }
        ensureMetadataSession();
        if (mc.player != null && (metadata.due(mc.player.tickCount) || !metadataEligible())) flushMetadata();
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

    private boolean metadataEligible() {
        var flight = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        return requested() && !delayBlocked && glideTrackerDelay.getValue() > 0 && mc.player != null
                && (flight == null || !flight.isEnabled())
                && !mc.player.onGround() && !mc.player.isDeadOrDying()
                && !mc.player.isInWater() && !mc.player.isInLava()
                && !dev.guizhou.features.modules.combat.GuangxiElytraChaseModule.preservesMaceDescent()
                && !dev.guizhou.features.modules.combat.GuangxiElytraChaseModule.isMaceDiveActive();
    }

    private void ensureMetadataSession() {
        if (metadataPlayer == mc.player && metadataLevel == mc.level) return;
        metadata.reset(); lastStartTick = Integer.MIN_VALUE;
        delayBlocked = false; lastDelayedTick = Integer.MIN_VALUE;
        metadataPlayer = mc.player; metadataLevel = mc.level;
    }

    private void flushMetadata() {
        ensureMetadataSession();
        if (metadata.pending() && mc.player != null) {
            var key = dev.guizhou.mixin.entity.EntityFlagsAccessor.rime$sharedFlags();
            byte flags = mc.player.getEntityData().get(key);
            mc.player.getEntityData().set(key, (byte) (flags & ~0x80));
        }
        metadata.reset();
    }

    public static void discardDelayedMetadata() {
        if (instance == null || !mc.isSameThread()) return;
        instance.ensureMetadataSession();
        if (mc.player != null && (long) mc.player.tickCount - instance.lastDelayedTick <= 40) instance.delayBlocked = true;
        instance.flushMetadata(); instance.lastStartTick = Integer.MIN_VALUE;
    }

    public static java.util.List<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>> filterMetadata(
            int id, java.util.List<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>> values) {
        if (instance == null || !mc.isSameThread() || mc.player == null || id != mc.player.getId()) return values;
        instance.ensureMetadataSession();
        var key = dev.guizhou.mixin.entity.EntityFlagsAccessor.rime$sharedFlags();
        var result = new java.util.ArrayList<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>>(values.size());
        for (var value : values) {
            if (value.id() == key.id() && value.value() instanceof Byte flags) {
                int age = instance.lastStartTick == Integer.MIN_VALUE ? Integer.MAX_VALUE
                        : mc.player.tickCount - instance.lastStartTick;
                byte filtered = (byte) instance.metadata.accept(mc.player.getEntityData().get(key), flags,
                        mc.player.tickCount, age, instance.glideTrackerDelay.getValue(), instance.metadataEligible());
                if (filtered != flags) instance.lastDelayedTick = mc.player.tickCount;
                result.add(new net.minecraft.network.syncher.SynchedEntityData.DataValue<>(key.id(), key.serializer(), filtered));
            } else result.add(value);
        }
        return result;
    }

    @Override public String getDisplayInfo() {
        if (yieldsToCruise()) return "巡航优先";
        if (delayBlocked && glideTrackerDelay.getValue() > 0) return "回拉后已停用延迟";
        return metadata.pending() ? "等待滑翔同步" : null;
    }
}
