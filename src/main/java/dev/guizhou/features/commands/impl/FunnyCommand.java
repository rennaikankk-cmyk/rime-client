package dev.guizhou.features.commands.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.Feature;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.commands.ui.CommandCategory;
import dev.guizhou.features.gui.GuizhouWareGui;
import dev.guizhou.features.modules.Module;
import dev.guizhou.manager.CommandManager;

public class FunnyCommand extends Command {
    public FunnyCommand() {
        super("funny");
        setDescription("Toggles visibility of the Funny category in the ClickGui");
        category(CommandCategory.MODULES).summary("显示或隐藏 ClickGUI 中的 Funny 分类")
                .usage("", "切换显示；隐藏时会关闭该分类下的模块");
    }

    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        builder.executes((ctx) -> {
            CommandManager cm = ctx.getSource();
            boolean nowVisible = !cm.isFunnyVisible();
            cm.setFunnyVisible(nowVisible);
            if (!nowVisible) {
                GuizhouWare.moduleManager.stream()
                        .filter(m -> m.getCategory() == Module.Category.FUNNY)
                        .filter(Feature::isEnabled)
                        .forEach(Module::disable);
            }
            GuizhouWareGui.getInstance().reload();
            return nowVisible
                    ? success("Funny 分类已显示")
                    : info("Funny 分类已隐藏，其中的模块已关闭");
        });
    }
}
