package dev.guizhou.features.commands.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.client.ClickGuiModule;
import dev.guizhou.features.modules.client.HudClientModule;
import dev.guizhou.manager.CommandManager;
import dev.guizhou.util.render.UiSounds;

import java.util.List;

public class PanicCommand extends Command {
    public PanicCommand() {
        super("panic", "disableall");
        setDescription("Turns off combat, movement, world, player and render modules. \"all\" also turns off client and HUD modules.");
    }

    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        builder.executes(ctx -> panic(false))
                .then(literal("all").executes(ctx -> panic(true)));
    }

    private int panic(boolean everything) {
        List<Module> active = GuizhouWare.moduleManager.stream()
                .filter(Module::isEnabled)
                .filter(m -> !(m instanceof ClickGuiModule) && !(m instanceof HudClientModule))
                .filter(m -> everything || m.getCategory() != Module.Category.CLIENT && m.getCategory() != Module.Category.HUD)
                .toList();
        int disabled = 0;
        for (Module module : active) {
            try {
                module.disable();
                disabled++;
            } catch (RuntimeException failure) {
                GuizhouWare.LOGGER.error("Panic could not disable {}", module.getName(), failure);
            }
        }
        if (disabled > 0) UiSounds.moduleToggle(false);
        return success("Disabled {red} %s {reset} module(s)", disabled);
    }
}
