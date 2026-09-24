package dev.guizhou.features.commands.argument;

import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandExceptionType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.commands.CommandExceptions;
import dev.guizhou.features.commands.ui.CommandRunner;
import dev.guizhou.features.commands.ui.FuzzyMatch;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class CommandArgumentType implements ArgumentType<Command>, CommandExceptionType {
    @Override
    public Command parse(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        String value = reader.readString().toLowerCase(Locale.ROOT);

        Command command = GuizhouWare.commandManager.getCommand(value);
        if (command != null) return command;

        List<String> guesses = FuzzyMatch.didYouMean(value, GuizhouWare.commandManager.getCommandAliases(), 1);
        reader.setCursor(start);
        throw (guesses.isEmpty()
                ? CommandExceptions.invalidArgument("找不到命令 %s", value)
                : CommandExceptions.invalidArgument("找不到命令 %s，你是不是想输入 %s？", value, guesses.get(0)))
                .createWithContext(reader);
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        String input = builder.getRemaining();
        boolean browsing = input.isBlank();

        for (String alias : GuizhouWare.commandManager.getCommandAliases()) {
            Command command = GuizhouWare.commandManager.getCommand(alias);
            if (command == null) continue;
            // An empty argument lists the visible commands once; typing searches every alias and module.
            if (browsing && (!command.isShown() || !alias.equals(command.getName()))) continue;
            if (FuzzyMatch.matches(input, alias)) {
                builder.suggest(alias, new LiteralMessage(CommandRunner.summaryOf(command)));
            }
        }

        return builder.buildFuture();
    }

    public static Command getCommand(CommandContext<?> ctx, String name) {
        return ctx.getArgument(name, Command.class);
    }

    public static CommandArgumentType command() {
        return new CommandArgumentType();
    }
}
