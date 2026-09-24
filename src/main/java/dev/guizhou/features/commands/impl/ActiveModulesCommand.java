package dev.guizhou.features.commands.impl;

import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.commands.ui.ChatFeed;
import dev.guizhou.features.commands.ui.ChatPalette;
import dev.guizhou.features.commands.ui.ChatTone;
import dev.guizhou.features.commands.ui.CommandCategory;
import dev.guizhou.features.commands.ui.FuzzyMatch;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.hud.ActiveModulesHudModule;
import dev.guizhou.manager.CommandManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;

public class ActiveModulesCommand extends Command {
    public ActiveModulesCommand() {
        super("activemodules", "am");
        setDescription("Manages the ActiveModules HUD list");
        category(CommandCategory.MODULES).summary("管理 ActiveModules HUD 的显示列表")
                .usage("list", "查看列表")
                .usage("add <模块>", "加入列表")
                .usage("remove <模块>", "移出列表")
                .usage("clear", "清空列表");
    }

    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        builder.then(literal("add")
                        .then(argument("module", word())
                                .suggests(addSuggestions())
                                .executes((ctx) -> {
                                    String name = getString(ctx, "module");
                                    Module module = GuizhouWare.moduleManager.getModuleByName(name);
                                    if (module == null) return fail("找不到模块 %s", name);
                                    ActiveModulesHudModule h = ActiveModulesHudModule.getInstance();
                                    if (h == null) return fail("未找到 ActiveModules HUD 元素");
                                    if (!h.add(name)) {
                                        return warn("%s 已在列表中", module.getDisplayName());
                                    }
                                    return success("已将 {text} %s {} 加入 ActiveModules", module.getDisplayName());
                                })))
                .then(literal("remove")
                        .then(argument("module", word())
                                .suggests(removeSuggestions())
                                .executes((ctx) -> {
                                    String name = getString(ctx, "module");
                                    ActiveModulesHudModule h = ActiveModulesHudModule.getInstance();
                                    if (h == null) return fail("未找到 ActiveModules HUD 元素");
                                    if (!h.remove(name)) {
                                        return warn("%s 不在列表中", name);
                                    }
                                    return success("已将 {text} %s {} 移出 ActiveModules", name);
                                })))
                .then(literal("clear")
                        .executes((ctx) -> {
                            ActiveModulesHudModule h = ActiveModulesHudModule.getInstance();
                            if (h == null) return fail("未找到 ActiveModules HUD 元素");
                            h.clear();
                            return success("已清空 ActiveModules 列表");
                        }))
                .then(literal("list")
                        .executes((ctx) -> {
                            ActiveModulesHudModule h = ActiveModulesHudModule.getInstance();
                            if (h == null) return fail("未找到 ActiveModules HUD 元素");
                            List<String> entries = h.getEntries();
                            if (entries.isEmpty()) {
                                return replyKeyed("list", ChatTone.MUTED, Component.literal("ActiveModules 列表为空"));
                            }
                            String prefix = ctx.getSource().getCommandPrefix();
                            MutableComponent body = Component.empty()
                                    .append(ChatFeed.text("ActiveModules " + entries.size() + "  ", ChatPalette.TEXT));
                            for (int i = 0; i < entries.size(); i++) {
                                if (i > 0) body.append(ChatFeed.text(" · ", ChatPalette.SUBTLE));
                                String entry = entries.get(i);
                                body.append(ChatFeed.suggest(entry, prefix + "am remove " + entry,
                                        ChatFeed.text("移出列表", ChatPalette.MUTED), ChatPalette.ACCENT_2));
                            }
                            return replyKeyed("list", ChatTone.INFO, body);
                        }));
    }

    private static SuggestionProvider<CommandManager> addSuggestions() {
        return (ctx, builder) -> {
            String input = builder.getRemaining();
            ActiveModulesHudModule h = ActiveModulesHudModule.getInstance();
            List<String> entries = h != null ? h.getEntries() : List.of();
            for (Module module : GuizhouWare.moduleManager.getModules()) {
                String name = module.getName();
                if (entries.stream().anyMatch(e -> e.equalsIgnoreCase(name))) continue;
                if (FuzzyMatch.bestScore(input, name, module.getDisplayName()) != FuzzyMatch.NO_MATCH) {
                    builder.suggest(name, new LiteralMessage(module.getDisplayName()));
                }
            }
            return builder.buildFuture();
        };
    }

    private static SuggestionProvider<CommandManager> removeSuggestions() {
        return (ctx, builder) -> {
            String input = builder.getRemaining();
            ActiveModulesHudModule h = ActiveModulesHudModule.getInstance();
            if (h == null) return builder.buildFuture();
            for (String entry : h.getEntries()) {
                if (FuzzyMatch.matches(input, entry)) {
                    builder.suggest(entry);
                }
            }
            return builder.buildFuture();
        };
    }
}
