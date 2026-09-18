package dev.guizhou.features.commands.impl;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.commands.Command;
import dev.guizhou.manager.CommandManager;

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
                                })));
    }
}
