package dev.guizhou.features.commands.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.commands.ui.ChatFeed;
import dev.guizhou.features.commands.ui.ChatPalette;
import dev.guizhou.features.commands.ui.ChatTone;
import dev.guizhou.features.commands.ui.CommandCategory;
import dev.guizhou.features.commands.ui.FuzzyMatch;
import dev.guizhou.features.modules.player.InstantRekitModule;
import dev.guizhou.manager.CommandManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;

public class LoadKitCommand extends Command {
    public LoadKitCommand() {
        super("loadkit");
        setDescription("Selects which saved kit InstantRekit will apply");
        category(CommandCategory.CONFIG).summary("选择 InstantRekit 要补给的套装")
                .usage("", "使用默认套装")
                .usage("<名称>", "使用指定套装");
    }

    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        builder.executes(ctx -> load(InstantRekitModule.DEFAULT_KIT));
        builder.then(argument("name", word())
                .suggests(kitSuggestions())
                .executes(ctx -> load(getString(ctx, "name"))));
    }

    static SuggestionProvider<CommandManager> kitSuggestions() {
        return (ctx, builder) -> {
            InstantRekitModule module = GuizhouWare.moduleManager.getModuleByClass(InstantRekitModule.class);
            if (module != null) {
                for (String kit : module.listKits()) {
                    if (FuzzyMatch.matches(builder.getRemaining(), kit)) builder.suggest(kit);
                }
            }
            return builder.buildFuture();
        };
    }

    private int load(String name) {
        InstantRekitModule module = GuizhouWare.moduleManager.getModuleByClass(InstantRekitModule.class);
        if (module == null) return fail("InstantRekit 模块未注册");
        if (!module.loadKit(name)) {
            List<String> kits = List.copyOf(module.listKits());
            String prefix = GuizhouWare.commandManager.getCommandPrefix();
            MutableComponent body = Component.empty().append(ChatFeed.text("没有名为 " + name + " 的套装", ChatPalette.DANGER_SOFT));
            if (kits.isEmpty()) {
                body.append(ChatFeed.text("   还没有保存过套装，先用 ", ChatPalette.MUTED))
                        .append(ChatFeed.command("savekit ", null));
            } else {
                body.append(ChatFeed.text("   已保存：", ChatPalette.MUTED));
                for (int i = 0; i < kits.size(); i++) {
                    if (i > 0) body.append(ChatFeed.text(" · ", ChatPalette.SUBTLE));
                    body.append(ChatFeed.suggest(kits.get(i), prefix + "loadkit " + kits.get(i), null, ChatPalette.ACCENT_2));
                }
            }
            return reply(ChatTone.ERROR, body);
        }
        return success("已选择套装 {cyan} %s {}，打开容器时自动补给", name);
    }
}
