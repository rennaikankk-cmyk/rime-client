package dev.guizhou.features.modules.extra;

import dev.guizhou.features.settings.Setting;

public final class AutoRespawnModule extends UtilityModule {
    private final Setting<Integer> delay = num("DelayTicks", 5, 0, 100);
    private int deadTicks;
    private boolean requested;
    public AutoRespawnModule() { super("AutoRespawn", "Requests respawn once after the configured death delay.", Category.PLAYER); }
    @Override protected boolean allowDead() { return true; }
    @Override protected void resetSession() { deadTicks = 0; requested = false; }
    @Override protected void tick() {
        if (!mc.player.isDeadOrDying()) { resetSession(); return; }
        if (!requested && ++deadTicks >= delay.getValue()) {
            mc.player.respawn(); requested = true;
        }
    }
}
