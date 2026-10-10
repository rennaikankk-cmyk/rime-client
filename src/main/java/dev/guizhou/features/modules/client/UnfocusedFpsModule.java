package dev.guizhou.features.modules.client;

import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;

/** Apply a runtime cap without overwriting the user's video settings. */
public final class UnfocusedFpsModule extends Module {
    private final Setting<Integer> fps = num("FPS", 30, 1, 240);

    public UnfocusedFpsModule() {
        super("UnfocusedFps", "Limits background FPS while the Minecraft window is unfocused.", Category.CLIENT);
    }

    public int limit(int vanillaLimit) {
        return isEnabled() && !mc.isWindowActive() ? Math.min(vanillaLimit, fps.getValue()) : vanillaLimit;
    }
}
