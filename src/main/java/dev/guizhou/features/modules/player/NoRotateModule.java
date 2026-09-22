package dev.guizhou.features.modules.player;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.Module;

public class NoRotateModule extends Module {

    public NoRotateModule() {
        super("NoRotate", "Ignores server-forced rotations.", Category.PLAYER);
    }

    public static boolean isActive() {
        if (GuizhouWare.moduleManager == null) return false;
        NoRotateModule module = GuizhouWare.moduleManager.getModuleByClass(NoRotateModule.class);
        return module != null && module.isEnabled() && dev.guizhou.manager.HeartbeatManager.authorized();
    }
}
