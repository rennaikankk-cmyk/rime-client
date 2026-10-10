package dev.guizhou.features.modules.render;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;

import java.awt.Color;
import java.util.function.Predicate;

public class SkyboxModule extends Module {
    public enum Mode { Aurora, Nebula, Sunset, Trippy }

    public Setting<Mode> mode = mode("Mode", Mode.Aurora).setPage("General");
    public Setting<Boolean> replaceCelestial = bool("ReplaceCelestial", true).setPage("General");

    public Setting<Color> auroraColor = color("AuroraColor", 26, 217, 115, 255).setPage("General");

    public SkyboxModule() {
        super("Skybox", "Replaces the sky with a custom OpenGL shader", Category.RENDER);
        auroraColor.setVisibility(v -> mode.getValue() == Mode.Aurora);
    }

    @Override
    public String getDisplayInfo() {
        return mode.getValue().name();
    }

    public static SkyboxModule getInstance() {
        if (GuizhouWare.moduleManager == null) return null;
        return GuizhouWare.moduleManager.getModuleByClass(SkyboxModule.class);
    }

    public static boolean isActive(Predicate<SkyboxModule> predicate) {
        SkyboxModule module = getInstance();
        return module != null && module.isEnabled() && predicate.test(module);
    }
}
