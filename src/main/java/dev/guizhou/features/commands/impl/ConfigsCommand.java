package dev.guizhou.features.commands.impl;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.commands.Command;
import dev.guizhou.manager.CloudConfigClient;
import dev.guizhou.manager.CommandManager;
import dev.guizhou.manager.ConfigManager;
import dev.guizhou.util.ConfigFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.StringJoiner;

public class ConfigsCommand extends Command {

    public ConfigsCommand() {
        super("configs", "config", "cfg");
        setDescription("Named config presets: save/load/list/delete");
    }

    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        builder.then(literal("list")
                        .executes((ctx) -> {
                            List<String> names = GuizhouWare.configManager.listNamed();
                            if (names.isEmpty()) {
                                return success("No saved configs. Use %sconfigs save <name>",
                                        GuizhouWare.commandManager.getCommandPrefix());
                            }
                            StringJoiner joiner = new StringJoiner(", ");
                            names.forEach(joiner::add);
                            return success("Configs (%s): %s", names.size(), joiner);
                        }))
                .then(literal("save")
                        .then(argument("name", StringArgumentType.word())
                                .executes((ctx) -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    if (!GuizhouWare.configManager.saveNamed(name)) {
                                        return success("{red} Failed to save config '%s' (name must be A-Z a-z 0-9 _ -)", name);
                                    }
                                    return success("Saved config {green} %s", name);
                                })))
                .then(literal("load")
                        .then(argument("name", StringArgumentType.word())
                                .executes((ctx) -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    if (!GuizhouWare.configManager.loadNamed(name)) {
                                        return success("{red} Could not fully load config '%s'; check its name and the log for invalid files.", name);
                                    }
                                    return success("Loaded config {green} %s", name);
                                })))
                .then(literal("delete")
                        .then(argument("name", StringArgumentType.word())
                                .executes((ctx) -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    if (!GuizhouWare.configManager.deleteNamed(name)) {
                                        return success("{red} No config named '%s'", name);
                                    }
                                    return success("Deleted config {green} %s", name);
                                })))
                .then(literal("import").then(argument("name", StringArgumentType.word())
                        .then(argument("path", StringArgumentType.greedyString()).executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            String input = StringArgumentType.getString(ctx, "path").replaceAll("^\"|\"$", "");
                            try {
                                Path path = Path.of(input).toAbsolutePath().normalize();
                                if (!Files.isRegularFile(path) || Files.size(path) > 512 * 1024)
                                    return fail("文件不存在或超过 512 KiB");
                                return GuizhouWare.configManager.importBundle(name, Files.readString(path), false)
                                        ? success("已导入参数 %s；使用 configs load %s 应用", name, name)
                                        : fail("参数格式不兼容：需要 GuizhouWare JSON 预设");
                            } catch (Exception invalid) { return fail("导入失败：%s", invalid.getMessage()); }
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
                                return success("参数已导出：%s", path);
                            } catch (Exception invalid) { return fail("导出失败：%s", invalid.getMessage()); }
                        }))))
                .then(literal("cloud").then(cloudScope("private")).then(cloudScope("public")));
    }

    private LiteralArgumentBuilder<CommandManager> cloudScope(String scope) {
        boolean publicPreset = "public".equals(scope);
        LiteralArgumentBuilder<CommandManager> branch = literal(scope);
        branch.then(literal("list").executes(ctx -> {
            CloudConfigClient.request(scope, "list", null, null, reply ->
                    sendMessage(reply.ok() ? (reply.names().isEmpty() ? "云端暂无参数" :
                            "云端参数：" + String.join("、", reply.names())) : "{red} " + reply.message()));
            return success("正在读取云参数…");
        }));
        branch.then(literal("save").then(argument("name", StringArgumentType.word()).executes(ctx -> {
            String name = StringArgumentType.getString(ctx, "name");
            try {
                String encoded = GuizhouWare.configManager.exportBundle(null, publicPreset);
                CloudConfigClient.request(scope, "save", name, encoded, reply ->
                        sendMessage(reply.ok() ? "云参数已保存：" + reply.message() : "{red} " + reply.message()));
                return success(publicPreset ? "正在发布公开参数…" : "正在同步私人参数…");
            } catch (Exception invalid) { return fail("保存失败：%s", invalid.getMessage()); }
        })));
        branch.then(literal("load").then(argument("name", publicPreset
                ? StringArgumentType.greedyString() : StringArgumentType.word()).executes(ctx -> {
            String name = StringArgumentType.getString(ctx, "name");
            CloudConfigClient.request(scope, "load", name, null, reply -> {
                if (!reply.ok()) { sendMessage("{red} " + reply.message()); return; }
                ConfigManager configs = GuizhouWare.configManager;
                if (!configs.applyBundle(reply.bundle(), publicPreset)) {
                    sendMessage("{red} 云参数格式不兼容"); return;
                }
                configs.save();
                sendMessage("已应用云参数：" + name);
            });
            return success("正在载入云参数…");
        })));
        branch.then(literal("delete").then(argument("name", StringArgumentType.word()).executes(ctx -> {
            String name = StringArgumentType.getString(ctx, "name");
            CloudConfigClient.request(scope, "delete", name, null, reply ->
                    sendMessage(reply.ok() ? "云参数已删除：" + name : "{red} " + reply.message()));
            return success("正在删除云参数…");
        })));
        return branch;
    }
}
