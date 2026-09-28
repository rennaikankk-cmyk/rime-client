package dev.guizhou.features.commands.impl;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.commands.ui.CommandCategory;
import dev.guizhou.features.commands.ui.CommandHelp;
import dev.guizhou.manager.CommandManager;

import static dev.guizhou.features.commands.argument.CommandArgumentType.command;
import static dev.guizhou.features.commands.argument.CommandArgumentType.getCommand;

public class HelpCommand extends Command {
    public HelpCommand() {
        super("help", "commands", "h", "cmds");
        setDescription("Displays all executable commands and additional information");
        category(CommandCategory.TOOLS).summary("查看命令列表与用法")
                .usage("", "分组列出全部命令")
                .usage("<页码>", "翻页")
                .usage("<命令>", "查看某条命令或模块的用法");
    }

    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        builder.executes(ctx -> {
                    CommandHelp.page(ctx.getSource(), 1);
                    return NO_OP;
                })
                .then(argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            CommandHelp.page(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "page"));
                            return NO_OP;
                        }))
                .then(argument("command_name", command())
                        .executes(ctx -> {
                            CommandHelp.detail(ctx.getSource(), getCommand(ctx, "command_name"));
                            return NO_OP;
                        }));
    }
}
