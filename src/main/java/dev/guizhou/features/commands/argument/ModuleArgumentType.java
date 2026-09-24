package dev.guizhou.features.commands.argument;

import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.commands.CommandExceptions;
import dev.guizhou.features.commands.ui.FuzzyMatch;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.ModuleNames;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public record ModuleArgumentType(boolean fullName) implements ArgumentType<Module> {
    @Override
    public Module parse(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        String value = reader.readString();

        var match = ModuleNames.resolve(GuizhouWare.moduleManager.getModules(), Module::getName,
                Module::getDisplayName, value, !fullName);
        if (match.ambiguous()) {
            reader.setCursor(start);
            throw CommandExceptions.invalidArgument("模块名 %s 有歧义，请输入完整的模块 ID 或标准名称", value)
                    .createWithContext(reader);
        }
        if (match.value() != null) return match.value();

        List<String> names = new ArrayList<>();
        for (Module module : GuizhouWare.moduleManager.getModules()) {
            names.add(ModuleNames.displayName(module.getName()));
        }
        List<String> guesses = FuzzyMatch.didYouMean(value, names, 1);
        reader.setCursor(start);
        throw (guesses.isEmpty()
                ? CommandExceptions.invalidArgument("找不到模块 %s", value)
                : CommandExceptions.invalidArgument("找不到模块 %s，你是不是想输入 %s？", value, guesses.get(0)))
                .createWithContext(reader);
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        String input = builder.getRemaining();

        for (Module module : GuizhouWare.moduleManager.getModules()) {
            String name = module.getName().toLowerCase(Locale.ROOT);
            String canonical = ModuleNames.displayName(module.getName()).toLowerCase(Locale.ROOT);
            if (FuzzyMatch.bestScore(input, name, canonical, module.getDisplayName()) == FuzzyMatch.NO_MATCH) continue;
            LiteralMessage tooltip = new LiteralMessage(module.getDisplayName() + " · "
                    + module.getCategory().getName() + " · " + (module.isEnabled() ? "已开启" : "未开启"));
            builder.suggest(canonical, tooltip);
            if (!name.equals(canonical) && FuzzyMatch.matches(input, name)) builder.suggest(name, tooltip);
        }

        return builder.buildFuture();
    }

    public static ModuleArgumentType module() {
        return module(false);
    }

    public static ModuleArgumentType module(boolean fullName) {
        return new ModuleArgumentType(fullName);
    }

    public static Module getModule(CommandContext<?> ctx, String name) {
        return ctx.getArgument(name, Module.class);
    }
}
