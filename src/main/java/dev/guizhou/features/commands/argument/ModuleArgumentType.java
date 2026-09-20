package dev.guizhou.features.commands.argument;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.commands.CommandExceptions;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.ModuleNames;

import java.util.Locale;

import java.util.concurrent.CompletableFuture;

public record ModuleArgumentType(boolean fullName) implements ArgumentType<Module> {
    @Override
    public Module parse(StringReader reader) throws CommandSyntaxException {
        String value = reader.readString();

        var match = ModuleNames.resolve(GuizhouWare.moduleManager.getModules(), Module::getName,
                Module::getDisplayName, value, !fullName);
        if (match.ambiguous()) throw CommandExceptions.invalidArgument(
                "Ambiguous module name; use a full module ID or standard name").createWithContext(reader);
        if (match.value() != null) return match.value();

        throw CommandExceptions.invalidArgument("Invalid module").createWithContext(reader);
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        String input = builder.getRemainingLowerCase();

        for (Module module : GuizhouWare.moduleManager.getModules()) {
            String name = module.getName().toLowerCase(Locale.ROOT);
            String displayName = module.getDisplayName().toLowerCase(Locale.ROOT);
            String canonical = ModuleNames.displayName(module.getName()).toLowerCase(Locale.ROOT);
            if (name.contains(input) || displayName.contains(input) || canonical.contains(input)) {
                builder.suggest(canonical);
                if (!name.equals(canonical)) builder.suggest(name);
            }
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
