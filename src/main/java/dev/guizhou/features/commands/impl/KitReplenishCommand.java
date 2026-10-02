package dev.guizhou.features.commands.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.commands.ui.CommandCategory;
import dev.guizhou.features.commands.ui.FuzzyMatch;
import dev.guizhou.features.modules.player.KitReplenishModule;
import dev.guizhou.manager.CommandManager;
import dev.guizhou.util.lang.ClientLanguage;

import java.util.Set;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;

public class KitReplenishCommand extends Command {
    public KitReplenishCommand() {
        super("kitreplenish", "kr");
        setDescription("Saves kits and runs KitReplenish resupply passes");
        category(CommandCategory.CONFIG).summary("套装补给：保存套装清单并按缺口分阶段补给")
                .usage("save [名称]", "把当前背包保存为套装清单")
                .usage("use <名称>", "选择要补给的套装")
                .usage("list", "列出已保存的套装")
                .usage("run", "立即执行一次补给")
                .usage("stop", "停止正在进行的补给链");
    }

    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        builder.executes(ctx -> run());
        builder.then(literal("run").executes(ctx -> run()));
        builder.then(literal("stop").executes(ctx -> stop()));
        builder.then(literal("list").executes(ctx -> list()));
        builder.then(literal("save").executes(ctx -> save(KitReplenishModule.DEFAULT_KIT)));
        builder.then(literal("save").then(argument("name", word())
                .executes(ctx -> save(getString(ctx, "name")))));
        builder.then(literal("use").then(argument("name", word())
                .suggests((ctx, suggestions) -> {
                    KitReplenishModule module = module();
                    if (module != null) {
                        for (String kit : module.kitNames()) {
                            if (FuzzyMatch.matches(suggestions.getRemaining(), kit)) suggestions.suggest(kit);
                        }
                    }
                    return suggestions.buildFuture();
                })
                .executes(ctx -> use(getString(ctx, "name")))));
    }

    private KitReplenishModule module() {
        return GuizhouWare.moduleManager.getModuleByClass(KitReplenishModule.class);
    }

    private int save(String name) {
        KitReplenishModule module = module();
        if (module == null) return fail(ClientLanguage.text("KitReplenish 模块未注册"));
        int saved = module.saveKit(name);
        if (saved == 0) return fail(ClientLanguage.text("背包为空，没有可保存的物品"));
        return success(ClientLanguage.text("已保存套装 {cyan} %s {}，共 {cyan} %s {} 种物品"), name, saved);
    }

    private int use(String name) {
        KitReplenishModule module = module();
        if (module == null) return fail(ClientLanguage.text("KitReplenish 模块未注册"));
        if (!module.loadKit(name)) return fail(ClientLanguage.text("找不到套装 %s"), name);
        return success(ClientLanguage.text("已选择套装 {cyan} %s {}，缺货时自动或手动补给"), name);
    }

    private int list() {
        KitReplenishModule module = module();
        if (module == null) return fail(ClientLanguage.text("KitReplenish 模块未注册"));
        Set<String> names = module.kitNames();
        if (names.isEmpty()) return fail(ClientLanguage.text("还没有保存任何套装"));
        return success(ClientLanguage.text("已保存套装：%s（当前 {cyan} %s {}）"),
                String.join(", ", names), module.activeKit());
    }

    private int run() {
        KitReplenishModule module = module();
        if (module == null) return fail(ClientLanguage.text("KitReplenish 模块未注册"));
        if (!module.isEnabled()) module.enable();
        module.requestRun();
        return success(ClientLanguage.text("套装补给：开始检查缺口"));
    }

    private int stop() {
        KitReplenishModule module = module();
        if (module == null) return fail(ClientLanguage.text("KitReplenish 模块未注册"));
        module.stopChain();
        return success(ClientLanguage.text("套装补给：已停止"));
    }
}
