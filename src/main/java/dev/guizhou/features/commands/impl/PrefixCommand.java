package dev.guizhou.features.commands.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.modules.client.ClickGuiModule;
import dev.guizhou.manager.CommandManager;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;

public class PrefixCommand extends Command {
    public PrefixCommand() {
        super("prefix", "setprefix");
        setDescription("Sets the command prefix");
    }

    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        builder.then(argument("prefix", word())
                .executes((ctx) -> {
                    String prefix = getString(ctx, "prefix");
                    if (prefix == null || prefix.isEmpty()) {
                        return fail("前缀不能为空");
                    }
                    ctx.getSource().setCommandPrefix(prefix);
                    // ClickGui's Prefix setting is re-applied on every start, so it must hold the value too.
                    ClickGuiModule clickGui = ClickGuiModule.getInstance();
                    if (clickGui != null) clickGui.prefix.setValue(prefix);
                    return success("命令前缀已改为 {cyan} %s", prefix);
                }));
    }
}
