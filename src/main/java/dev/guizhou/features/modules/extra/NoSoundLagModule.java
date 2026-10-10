package dev.guizhou.features.modules.extra;

import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.settings.Setting;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;

public final class NoSoundLagModule extends UtilityModule {
    private final Setting<Integer> maxPerSecond = num("MaxPerSecond", 80, 10, 500);
    private long window;
    private int count;
    public NoSoundLagModule() { super("NoSoundLag", "Limits excessive incoming sound packets, including sounds attached to entities.", Category.CLIENT); }
    @Override protected synchronized void resetSession() { window = 0; count = 0; }
    @Override protected void tick() {}
    @Subscribe(priority = 1000) private synchronized void sound(PacketEvent.Receive event) {
        if (!permitted() || !(event.getPacket() instanceof ClientboundSoundPacket
                || event.getPacket() instanceof ClientboundSoundEntityPacket)) return;
        long now = System.nanoTime();
        if (window == 0 || now - window >= 1_000_000_000L) { window = now; count = 0; }
        if (++count > maxPerSecond.getValue()) event.cancel();
    }
}
