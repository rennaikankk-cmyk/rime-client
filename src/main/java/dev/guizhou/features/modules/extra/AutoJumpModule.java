package dev.guizhou.features.modules.extra;

import dev.guizhou.features.settings.Setting;

public final class AutoJumpModule extends UtilityModule {
    private final Setting<Boolean> movingOnly = bool("MovingOnly", true);
    public AutoJumpModule() { super("AutoJump", "Jumps on the ground while moving, without retaining a pressed jump key.", Category.MOVEMENT); }
    @Override protected void tick() {
        if (mc.screen == null && mc.player.onGround() && !mc.player.isUsingItem() && !mc.player.isShiftKeyDown()
                && (!movingOnly.getValue() || mc.player.input.getMoveVector().lengthSquared() > 0)) mc.player.jumpFromGround();
    }
}
