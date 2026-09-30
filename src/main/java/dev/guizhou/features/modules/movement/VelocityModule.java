package dev.guizhou.features.modules.movement;

import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;

public class VelocityModule extends Module {
    public final Setting<Boolean> phaseOnly = bool("PhaseOnly", true);
    public final Setting<Boolean> knockback = bool("Knockback", true);
    public final Setting<Boolean> explosions = bool("Explosions", false);
    public final Setting<Boolean> entityPush = bool("EntityPush", true);
    public final Setting<Boolean> blockPush = bool("BlockPush", true);

    public VelocityModule() {
        super("Velocity", "Cancels knockback, explosions, and entity/block push. With PhaseOnly (default, Grim-safe) "
                + "it only acts while you are inside a block; on open ground it does nothing.", Category.MOVEMENT);
    }

    public boolean shouldCancelKnockback() {
        return isEnabled() && knockback.getValue() && phaseConditionMet();
    }

    public boolean phaseConditionMet() {
        if (!phaseOnly.getValue()) return true;
        return dev.guizhou.util.player.InsideBlockUtil.isInsideBlock();
    }
}
