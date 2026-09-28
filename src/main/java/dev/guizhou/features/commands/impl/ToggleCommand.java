package dev.guizhou.features.commands.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.commands.ui.ChatFeed;
import dev.guizhou.features.commands.ui.CommandCategory;
import dev.guizhou.features.modules.Module;
import dev.guizhou.manager.CommandManager;

import static dev.guizhou.features.commands.argument.ModuleArgumentType.getModule;
import static dev.guizhou.features.commands.argument.ModuleArgumentType.module;

public class ToggleCommand extends Command {
    public ToggleCommand() {
        super("toggle", "t");
        setDescription("Toggles a module");
        category(CommandCategory.MODULES).summary("切换模块开关")
                .usage("<模块>", "开启或关闭该模块");
    }

    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        builder.then(argument("module", module(true))
                .executes((ctx) -> {
                    Module module = getModule(ctx, "module");
                    module.toggleByUser();
                    // Same key as the Notifications toggle line, so the two never stack.
                    ChatFeed.moduleToggled(module.getName(), module.getDisplayName(), module.isEnabled());
                    return NO_OP;
                }));
    }
}
