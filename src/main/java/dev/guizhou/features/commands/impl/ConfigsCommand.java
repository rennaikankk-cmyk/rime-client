package dev.guizhou.features.commands.impl;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.commands.ui.ChatFeed;
import dev.guizhou.features.commands.ui.ChatPalette;
import dev.guizhou.features.commands.ui.ChatTone;
import dev.guizhou.features.commands.ui.CommandCategory;
import dev.guizhou.features.commands.ui.FuzzyMatch;
import dev.guizhou.manager.CommandManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

public class ConfigsCommand extends Command {

    public ConfigsCommand() {
        super("configs", "config", "cfg");
        setDescription("Named config presets: save/load/list/delete");
        category(CommandCategory.CONFIG).summary("配置预设：保存 / 加载 / 列表 / 删除")
                .usage("list", "查看已保存的预设")
                .usage("save <名称>", "把当前配置保存为预设（A-Z a-z 0-9 _ -）")
                .usage("load <名称>", "加载预设")
                .usage("delete <名称>", "删除预设");
    }

    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        builder.then(literal("list")
                        .executes((ctx) -> {
                            List<String> names = GuizhouWare.configManager.listNamed();
                            if (names.isEmpty()) {
                                return replyKeyed("list", ChatTone.MUTED, Component.empty()
                                        .append(ChatFeed.text("还没有保存的预设，使用 ", ChatPalette.MUTED))
                                        .append(ChatFeed.command("configs save ", null)));
                            }
                            String prefix = ctx.getSource().getCommandPrefix();
                            MutableComponent body = Component.empty()
                                    .append(ChatFeed.text("预设 " + names.size() + "  ", ChatPalette.TEXT));
                            for (int i = 0; i < names.size(); i++) {
                                if (i > 0) body.append(ChatFeed.text(" · ", ChatPalette.SUBTLE));
                                String name = names.get(i);
                                body.append(ChatFeed.suggest(name, prefix + "configs load " + name,
                                        ChatFeed.text("加载预设 " + name, ChatPalette.MUTED), ChatPalette.ACCENT_2));
                            }
                            return replyKeyed("list", ChatTone.INFO, body);
                        }))
                .then(literal("save")
                        .then(argument("name", StringArgumentType.word())
                                .suggests(savedNames())
                                .executes((ctx) -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    if (!GuizhouWare.configManager.saveNamed(name)) {
                                        return fail("无法保存预设 '%s'（名称只能包含 A-Z a-z 0-9 _ -）", name);
                                    }
                                    return success("已保存预设 {cyan} %s", name);
                                })))
                .then(literal("load")
                        .then(argument("name", StringArgumentType.word())
                                .suggests(savedNames())
                                .executes((ctx) -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    if (!GuizhouWare.configManager.loadNamed(name)) {
                                        return fail("未能完整加载预设 '%s'，请检查名称，或查看日志中的无效文件", name);
                                    }
                                    return success("已加载预设 {cyan} %s", name);
                                })))
                .then(literal("delete")
                        .then(argument("name", StringArgumentType.word())
                                .suggests(savedNames())
                                .executes((ctx) -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    if (!GuizhouWare.configManager.deleteNamed(name)) {
                                        return fail("没有名为 '%s' 的预设", name);
                                    }
                                    return success("已删除预设 {cyan} %s", name);
                                })));
    }

    private static SuggestionProvider<CommandManager> savedNames() {
        return (ctx, builder) -> {
            for (String name : GuizhouWare.configManager.listNamed()) {
                if (FuzzyMatch.matches(builder.getRemaining(), name)) builder.suggest(name);
            }
            return builder.buildFuture();
        };
    }
}
