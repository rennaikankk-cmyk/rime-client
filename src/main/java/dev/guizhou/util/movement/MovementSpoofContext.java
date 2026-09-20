package dev.guizhou.util.movement;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.combat.GuangxiElytraChaseModule;
import dev.guizhou.features.modules.movement.ArmorFlyModule;
import dev.guizhou.features.modules.movement.GuangxiElytraModule;

import static dev.guizhou.util.traits.Util.mc;

public final class MovementSpoofContext {
    private MovementSpoofContext() {}

    public static boolean ordinaryMovement() {
        if (!mc.isSameThread() || mc.player == null || mc.level == null || mc.getConnection() == null) return false;
        if (!mc.player.isAlive() || mc.player.isSpectator() || mc.player.isCreative()
                || mc.player.getAbilities().flying || mc.player.isFallFlying() || mc.player.isPassenger()
                || mc.player.isInWater() || mc.player.isInLava() || mc.player.onClimbable()) return false;
        // Equipment switching briefly leaves isFallFlying false; keep the whole flight owner exempt.
        if (ArmorFlyModule.requested()) return false;
        if (GuizhouWare.moduleManager == null) return true;
        var elytra = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraModule.class);
        var chase = GuizhouWare.moduleManager.getModuleByClass(GuangxiElytraChaseModule.class);
        return (elytra == null || !elytra.isEnabled()) && (chase == null || !chase.isEnabled());
    }
}
