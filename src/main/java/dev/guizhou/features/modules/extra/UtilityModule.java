package dev.guizhou.features.modules.extra;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.Module;

/** Rime-native utility modules: all automatic actions retain the licence gate and session ownership. */
public abstract class UtilityModule extends Module {
    private Object level, player;
    protected UtilityModule(String name, String description, Category category) { super(name, description, category); }
    protected final boolean permitted() { return isEnabled() && dev.guizhou.manager.HeartbeatManager.authorized(); }
    protected final boolean ready() { return permitted() && !nullCheck() && mc.gameMode != null; }
    protected final boolean inventoryReady() {
        return ready() && mc.screen == null && mc.player.containerMenu == mc.player.inventoryMenu
                && mc.player.inventoryMenu.getCarried().isEmpty() && !mc.player.isUsingItem()
                && !GuizhouWare.swapManager.isLatched();
    }
    protected boolean allowDead() { return false; }
    protected void resetSession() {}
    @Override public void onEnable() { resetSession(); level = mc.level; player = mc.player; }
    @Override public void onDisable() { resetSession(); }
    @Override public final void onTick() {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) {
            resetSession();
            dev.guizhou.manager.HeartbeatManager.notifyDeniedOnce(); return;
        }
        if (level != mc.level || player != mc.player) { resetSession(); level = mc.level; player = mc.player; }
        if (!ready()) return;
        if (mc.player.isDeadOrDying() && !allowDead()) { resetSession(); return; }
        tick();
    }
    protected abstract void tick();
}
