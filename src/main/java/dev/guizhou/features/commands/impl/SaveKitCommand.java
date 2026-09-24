package dev.guizhou.features.commands.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.commands.ui.CommandCategory;
import dev.guizhou.features.modules.player.InstantRekitModule;
import dev.guizhou.manager.CommandManager;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;

public class SaveKitCommand extends Command {
    public SaveKitCommand() {
        super("savekit");
        setDescription("Snapshots your current inventory as a named InstantRekit kit");
        category(CommandCategory.CONFIG).summary("把当前背包保存为 InstantRekit 套装")
                .usage("", "保存为默认套装")
                .usage("<名称>", "保存为指定名称的套装");
    }

    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        builder.executes(ctx -> save(InstantRekitModule.DEFAULT_KIT));
        builder.then(argument("name", word())
                .suggests(LoadKitCommand.kitSuggestions())
                .executes(ctx -> save(getString(ctx, "name"))));
    }

    private int save(String name) {
        InstantRekitModule module = GuizhouWare.moduleManager.getModuleByClass(InstantRekitModule.class);
        if (module == null) return fail("InstantRekit 模块未注册");
        int saved = module.saveKit(name);
        if (saved == 0) return fail("背包为空，没有可保存的物品");
        GuizhouWare.configManager.save();
        return success("已保存套装 {cyan} %s {}，共 {cyan} %s {} 个物品格", name, saved);
    }
}
