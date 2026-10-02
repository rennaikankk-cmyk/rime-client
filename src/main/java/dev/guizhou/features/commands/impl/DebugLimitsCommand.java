package dev.guizhou.features.commands.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.commands.ui.CommandCategory;
import dev.guizhou.features.debug.MoveLimitDebug;
import dev.guizhou.manager.CommandManager;

public class DebugLimitsCommand extends Command {
    public DebugLimitsCommand() {
        super("debuglimits");
        setDescription("Toggles the move-rate debug overlay");
        category(CommandCategory.DIAGNOSTICS).summary("切换移动速率调试浮层")
                .usage("", "开启或关闭浮层");
    }

    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        builder.executes((ctx) -> {
            MoveLimitDebug debug = MoveLimitDebug.get();
            debug.toggle();
            return debug.isEnabled()
                    ? success(dev.guizhou.util.lang.ClientLanguage.text("移动速率调试浮层已开启"))
                    : info(dev.guizhou.util.lang.ClientLanguage.text("移动速率调试浮层已关闭"));
        });
    }
}
