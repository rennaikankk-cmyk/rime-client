package dev.guizhou.features.modules.extra;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.settings.Setting;

public final class AutoWalkModule extends UtilityModule {
    private final Setting<Boolean> pauseLag = bool("PauseLag", true);
    private final Setting<Double> lagTimeout = num("LagTimeout", 1.4, 0.5, 10.0);
    private volatile long lastPacket = System.nanoTime();
    public AutoWalkModule() { super("AutoWalk", "Adds forward input while in game; pauses when the server stops responding.", Category.MOVEMENT); }
    @Override protected void tick() {}
    @Override protected void resetSession() { lastPacket = System.nanoTime(); }
    @Subscribe private void receive(PacketEvent.Receive event) { lastPacket = System.nanoTime(); }
    public boolean active() {
        return ready() && mc.screen == null && !mc.options.keyDown.isDown()
                && (!pauseLag.getValue() || System.nanoTime() - lastPacket <= lagTimeout.getValue() * 1e9
                    && !GuizhouWare.tpsCounterService.isRubberbandRecent());
    }
}
