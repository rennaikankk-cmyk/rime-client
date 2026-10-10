package dev.guizhou.features.modules.extra;

public final class SafeWalkModule extends UtilityModule {
    public SafeWalkModule() { super("SafeWalk", "Uses vanilla edge clipping while grounded, without changing held sneak input.", Category.MOVEMENT); }
    @Override protected void tick() {}
    public boolean active() { return ready() && mc.screen == null && mc.player.onGround() && !mc.player.isFallFlying(); }
}
