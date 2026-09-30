package dev.guizhou.features.modules.extra;

import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.player.ChatUtil;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;

public final class LagNotifierModule extends UtilityModule {
    private final Setting<Double> timeout = num("Timeout", 1.4, 0.5, 10.0);
    private volatile long lastPacket = System.nanoTime();
    private long lastNotice;
    public LagNotifierModule() { super("LagNotifier", "Warns locally when the server stops sending packets or corrects your position.", Category.CLIENT); }
    @Override protected void resetSession() { lastPacket = System.nanoTime(); lastNotice = 0; }
    @Subscribe private void packet(PacketEvent.Receive event) {
        lastPacket = System.nanoTime();
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket && permitted()) {
            Object queuedLevel = mc.level, queuedPlayer = mc.player;
            mc.execute(() -> {
                if (ready() && queuedLevel == mc.level && queuedPlayer == mc.player)
                    ChatUtil.sendPersistent("lag-notifier", Component.literal(dev.guizhou.util.lang.ClientLanguage.text("Server corrected your position.")));
            });
        }
    }
    @Override protected void tick() {
        long now = System.nanoTime();
        if (now - lastPacket > timeout.getValue() * 1e9 && now - lastNotice > 1_000_000_000L) {
            ChatUtil.sendPersistent("lag-notifier", Component.literal(String.format(Locale.ROOT,
                    dev.guizhou.util.lang.ClientLanguage.text("Server not responding (%.1fs)"), (now - lastPacket) / 1e9))); lastNotice = now;
        }
    }
}
