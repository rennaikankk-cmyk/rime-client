package dev.guizhou.features.modules.movement;

import dev.guizhou.features.modules.Module;
import dev.guizhou.manager.HeartbeatManager;

/** Clears the vanilla jump cooldown so the next jump can start on the following tick. */
public class NoJumpDelayModule extends Module {
    public static NoJumpDelayModule INSTANCE;

    public NoJumpDelayModule() {
        super("NoJumpDelay", "Removes the delay between jumps.", Category.MOVEMENT);
        INSTANCE = this;
    }

    public static boolean active() {
        return HeartbeatManager.authorized() && INSTANCE != null && INSTANCE.isEnabled();
    }
}
