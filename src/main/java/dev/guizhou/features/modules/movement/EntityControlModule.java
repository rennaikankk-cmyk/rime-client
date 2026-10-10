package dev.guizhou.features.modules.movement;

import dev.guizhou.features.modules.Module;
import dev.guizhou.manager.HeartbeatManager;

/** Lets the local player steer a horse, donkey or mule that has no saddle. */
public class EntityControlModule extends Module {
    public static EntityControlModule INSTANCE;

    public EntityControlModule() {
        super("EntityControl", "Steer horses, donkeys and mules without a saddle.", Category.MOVEMENT);
        INSTANCE = this;
    }

    public static boolean active() {
        return HeartbeatManager.authorized() && INSTANCE != null && INSTANCE.isEnabled();
    }
}
