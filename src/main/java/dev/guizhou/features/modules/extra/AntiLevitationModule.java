package dev.guizhou.features.modules.extra;

public final class AntiLevitationModule extends UtilityModule {
    public AntiLevitationModule() { super("AntiLevitation", "Removes levitation from the local player each tick.", Category.MOVEMENT); }
    @Override protected void tick() { mc.player.removeEffect(net.minecraft.world.effect.MobEffects.LEVITATION); }
}
