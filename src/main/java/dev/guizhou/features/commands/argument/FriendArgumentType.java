package dev.guizhou.features.commands.argument;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.LiteralMessage;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.commands.ui.FuzzyMatch;

import java.util.concurrent.CompletableFuture;

public class FriendArgumentType implements ArgumentType<String> {
    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        return reader.readString();
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        String input = builder.getRemaining();
        for (String name : GuizhouWare.friendManager.getFriends()) {
            if (FuzzyMatch.matches(input, name)) {
                builder.suggest(name, new LiteralMessage("好友"));
            }
        }
        return builder.buildFuture();
    }

    public static FriendArgumentType friend() {
        return new FriendArgumentType();
    }

    public static String getFriend(CommandContext<?> ctx, String name) {
        return ctx.getArgument(name, String.class);
    }
}
