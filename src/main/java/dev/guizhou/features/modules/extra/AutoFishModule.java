package dev.guizhou.features.modules.extra;

import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.settings.Setting;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

public final class AutoFishModule extends UtilityModule {
    private final Setting<Boolean> autoCast = bool("AutoCast", true);
    private final Setting<Integer> catchDelay = num("CatchDelayTicks", 3, 0, 40);
    private final Setting<Integer> recastDelay = num("RecastDelayTicks", 9, 1, 100);
    private int nextTick, catchTick = -1;
    private Object hook;
    public AutoFishModule() { super("AutoFish", "Reels on a splash at your own bobber and recasts after a delay.", Category.PLAYER); }
    @Override protected void resetSession() { nextTick = 0; catchTick = -1; hook = null; }
    @Subscribe private void splash(PacketEvent.Receive event) {
        if (!(event.getPacket() instanceof ClientboundSoundPacket packet) || !permitted()
                || packet.getSound().value() != net.minecraft.sounds.SoundEvents.FISHING_BOBBER_SPLASH) return;
        Object queuedLevel = mc.level, queuedPlayer = mc.player;
        mc.execute(() -> {
            if (!ready() || queuedLevel != mc.level || queuedPlayer != mc.player || mc.player.fishing == null || catchTick >= 0) return;
            if (mc.player.fishing.position().distanceToSqr(new Vec3(packet.getX(), packet.getY(), packet.getZ())) > 4) return;
            hook = mc.player.fishing;
            catchTick = mc.player.tickCount + catchDelay.getValue();
        });
    }
    @Override protected void tick() {
        if (!inventoryReady()) return;
        InteractionHand hand = mc.player.getMainHandItem().is(Items.FISHING_ROD) ? InteractionHand.MAIN_HAND
                : mc.player.getOffhandItem().is(Items.FISHING_ROD) ? InteractionHand.OFF_HAND : null;
        if (hand == null) { resetSession(); return; }
        int tick = mc.player.tickCount;
        if (catchTick >= 0 && mc.player.fishing != hook) { catchTick = -1; hook = null; }
        if (mc.player.fishing != null && catchTick >= 0 && tick >= catchTick) {
            mc.gameMode.useItem(mc.player, hand); catchTick = -1; hook = null;
            nextTick = tick + recastDelay.getValue();
        } else if (mc.player.fishing == null && autoCast.getValue() && tick >= nextTick) {
            mc.gameMode.useItem(mc.player, hand); nextTick = tick + recastDelay.getValue();
        }
    }
}
