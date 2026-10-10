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
import dev.guizhou.manager.CloudConfigClient;
import dev.guizhou.manager.CommandManager;
import dev.guizhou.manager.ConfigManager;
import dev.guizhou.util.ConfigFiles;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ConfigsCommand extends Command {

    public ConfigsCommand() {
        super("configs", "config", "cfg");
        setDescription("Named config presets: save/load/list/delete");
        category(CommandCategory.CONFIG).summary("配置预设：本地保存 / 加载，以及云参数同步")
                .usage("list", "查看已保存的预设")
                .usage("save <名称>", "把当前配置保存为预设（A-Z a-z 0-9 _ -）")
                .usage("load <名称>", "加载预设")
                .usage("delete <名称>", "删除预设")
                .usage("cloud private list|save|load|delete", "私人云参数（需已通过授权）")
                .usage("cloud public list|save|load|delete", "公开云参数；载入时可写 账号/名称")
                .usage("import <名称> <路径>", "从 JSON 文件导入预设")
                .usage("export <live|名称> <路径>", "导出当前或已保存预设");
    }

    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        builder.then(literal("list")
                        .executes((ctx) -> {
                            List<String> names = GuizhouWare.configManager.listNamed();
                            if (names.isEmpty()) {
                                return replyKeyed("list", ChatTone.MUTED, Component.empty()
                                        .append(ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text("还没有保存的预设，使用 "), ChatPalette.MUTED))
                                        .append(ChatFeed.command("configs save ", null)));
                            }
                            String prefix = ctx.getSource().getCommandPrefix();
                            MutableComponent body = Component.empty()
                                    .append(ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text("预设 ") + names.size() + "  ", ChatPalette.TEXT));
                            for (int i = 0; i < names.size(); i++) {
                                if (i > 0) body.append(ChatFeed.text(" · ", ChatPalette.SUBTLE));
                                String name = names.get(i);
                                body.append(ChatFeed.suggest(name, prefix + "configs load " + name,
                                        ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text("加载预设 ") + name, ChatPalette.MUTED), ChatPalette.ACCENT_2));
                            }
                            return replyKeyed("list", ChatTone.INFO, body);
                        }))
                .then(literal("save")
                        .then(argument("name", StringArgumentType.word())
                                .suggests(savedNames())
                                .executes((ctx) -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    if (!GuizhouWare.configManager.saveNamed(name)) {
                                        return fail(dev.guizhou.util.lang.ClientLanguage.text("无法保存预设 '%s'（名称只能包含 A-Z a-z 0-9 _ -）"), name);
                                    }
                                    return success(dev.guizhou.util.lang.ClientLanguage.text("已保存预设 {cyan} %s"), name);
                                })))
                .then(literal("load")
                        .then(argument("name", StringArgumentType.word())
                                .suggests(savedNames())
                                .executes((ctx) -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    if (!GuizhouWare.configManager.loadNamed(name)) {
                                        return fail(dev.guizhou.util.lang.ClientLanguage.text("未能完整加载预设 '%s'，请检查名称，或查看日志中的无效文件"), name);
                                    }
                                    return success(dev.guizhou.util.lang.ClientLanguage.text("已加载预设 {cyan} %s"), name);
                                })))
                .then(literal("delete")
                        .then(argument("name", StringArgumentType.word())
                                .suggests(savedNames())
                                .executes((ctx) -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    if (!GuizhouWare.configManager.deleteNamed(name)) {
                                        return fail(dev.guizhou.util.lang.ClientLanguage.text("没有名为 '%s' 的预设"), name);
                                    }
                                    return success(dev.guizhou.util.lang.ClientLanguage.text("已删除预设 {cyan} %s"), name);
                                })))
                .then(literal("import").then(argument("name", StringArgumentType.word())
                        .then(argument("path", StringArgumentType.greedyString()).executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            String input = StringArgumentType.getString(ctx, "path").replaceAll("^\"|\"$", "");
                            try {
                                Path path = Path.of(input).toAbsolutePath().normalize();
                                if (!Files.isRegularFile(path) || Files.size(path) > 512 * 1024)
                                    return fail(dev.guizhou.util.lang.ClientLanguage.text("文件不存在或超过 512 KiB"));
                                return GuizhouWare.configManager.importBundle(name, Files.readString(path), false)
                                        ? success(dev.guizhou.util.lang.ClientLanguage.text("已导入参数 %s；使用 configs load %s 应用"), name, name)
                                        : fail(dev.guizhou.util.lang.ClientLanguage.text("参数格式不兼容：需要 GuizhouWare JSON 预设"));
                            } catch (Exception invalid) { return fail(dev.guizhou.util.lang.ClientLanguage.text("导入失败：%s"), invalid.getMessage()); }
                        }))))
                .then(literal("export").then(argument("name", StringArgumentType.word())
                        .then(argument("path", StringArgumentType.greedyString()).executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            String input = StringArgumentType.getString(ctx, "path").replaceAll("^\"|\"$", "");
                            try {
                                String encoded = GuizhouWare.configManager.exportBundle(
                                        "live".equalsIgnoreCase(name) ? null : name, false);
                                Path path = Path.of(input).toAbsolutePath().normalize();
                                ConfigFiles.write(path, encoded);
                                return success(dev.guizhou.util.lang.ClientLanguage.text("参数已导出：%s"), path);
                            } catch (Exception invalid) { return fail(dev.guizhou.util.lang.ClientLanguage.text("导出失败：%s"), invalid.getMessage()); }
                        }))))
                .then(literal("cloud").then(cloudScope("private")).then(cloudScope("public")));
    }

    private LiteralArgumentBuilder<CommandManager> cloudScope(String scope) {
        boolean publicPreset = "public".equals(scope);
        LiteralArgumentBuilder<CommandManager> branch = literal(scope);
        branch.then(literal("list").executes(ctx -> {
            CloudConfigClient.request(scope, "list", null, null, reply ->
                    sendMessage(reply.ok() ? (reply.names().isEmpty() ? dev.guizhou.util.lang.ClientLanguage.text("云端暂无参数") :
                            dev.guizhou.util.lang.ClientLanguage.text("云端参数：") + String.join("、", reply.names())) : "{red} " + reply.message()));
            return success(dev.guizhou.util.lang.ClientLanguage.text("正在读取云参数…"));
        }));
        branch.then(literal("save").then(argument("name", StringArgumentType.word()).executes(ctx -> {
            String name = StringArgumentType.getString(ctx, "name");
            try {
                String encoded = GuizhouWare.configManager.exportBundle(null, publicPreset);
                CloudConfigClient.request(scope, "save", name, encoded, reply ->
                        sendMessage(reply.ok() ? dev.guizhou.util.lang.ClientLanguage.text("云参数已保存：") + reply.message() : "{red} " + reply.message()));
                return success(publicPreset ? dev.guizhou.util.lang.ClientLanguage.text("正在发布公开参数…") : dev.guizhou.util.lang.ClientLanguage.text("正在同步私人参数…"));
            } catch (Exception invalid) { return fail(dev.guizhou.util.lang.ClientLanguage.text("保存失败：%s"), invalid.getMessage()); }
        })));
        branch.then(literal("load").then(argument("name", publicPreset
                ? StringArgumentType.greedyString() : StringArgumentType.word()).executes(ctx -> {
            String name = StringArgumentType.getString(ctx, "name");
            CloudConfigClient.request(scope, "load", name, null, reply -> {
                if (!reply.ok()) { sendMessage("{red} " + reply.message()); return; }
                ConfigManager configs = GuizhouWare.configManager;
                if (!configs.applyBundle(reply.bundle(), publicPreset)) {
                    sendMessage(dev.guizhou.util.lang.ClientLanguage.text("{red} 云参数格式不兼容")); return;
                }
                configs.save();
                sendMessage(dev.guizhou.util.lang.ClientLanguage.text("已应用云参数：") + name);
            });
            return success(dev.guizhou.util.lang.ClientLanguage.text("正在载入云参数…"));
        })));
        branch.then(literal("delete").then(argument("name", StringArgumentType.word()).executes(ctx -> {
            String name = StringArgumentType.getString(ctx, "name");
            CloudConfigClient.request(scope, "delete", name, null, reply ->
                    sendMessage(reply.ok() ? dev.guizhou.util.lang.ClientLanguage.text("云参数已删除：") + name : "{red} " + reply.message()));
            return success(dev.guizhou.util.lang.ClientLanguage.text("正在删除云参数…"));
        })));
        return branch;
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
