package dev.guizhou.features.commands.ui;

import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.Message;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.SuggestionContext;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.CommandManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Suggestions for prefixed client commands. The root token lists visible commands (empty input) or
 * fuzzy-matches every alias; deeper tokens use Brigadier's argument suggestions plus fuzzy literal
 * matches with Chinese tooltips. Display order is decided later by {@link #rank}.
 */
public final class CommandCompleter {
    private CommandCompleter() {}

    public static CompletableFuture<Suggestions> complete(CommandManager manager, ParseResults<CommandManager> parse,
                                                          String full, int start, int cursor) {
        if (cursor < start || cursor > full.length()) return Suggestions.empty();
        String typed = full.substring(start, cursor);
        if (typed.indexOf(' ') < 0) {
            return CompletableFuture.completedFuture(root(manager, full, start, cursor));
        }
        CompletableFuture<Suggestions> brigadier;
        try {
            brigadier = manager.getDispatcher().getCompletionSuggestions(parse, cursor);
        } catch (RuntimeException e) {
            brigadier = Suggestions.empty();
        }
        return brigadier.thenApply(suggestions -> withLiterals(manager, parse, suggestions, full, cursor));
    }

    /** Fuzzy display order for a finished suggestion list; ties keep shorter, then alphabetical. */
    public static List<Suggestion> rank(String input, List<Suggestion> suggestions) {
        if (suggestions.isEmpty()) return suggestions;
        int start = Math.min(input.length(), suggestions.get(0).getRange().getStart());
        int end = Math.min(input.length(), Math.max(start, suggestions.get(0).getRange().getEnd()));
        String token = input.substring(start, end);
        List<Suggestion> ranked = FuzzyMatch.rank(token, suggestions, Suggestion::getText);
        if (ranked.size() == suggestions.size()) return ranked;
        List<Suggestion> out = new ArrayList<>(ranked);
        for (Suggestion suggestion : suggestions) if (!ranked.contains(suggestion)) out.add(suggestion);
        return out;
    }

    private static Suggestions root(CommandManager manager, String full, int start, int cursor) {
        String query = full.substring(start, cursor);
        SuggestionsBuilder builder = new SuggestionsBuilder(full, start);
        if (query.isBlank()) {
            for (Command command : manager.getCommands()) {
                if (command.isShown()) builder.suggest(command.getName(), tooltip(command, command.getName()));
            }
            return builder.build();
        }
        Map<Command, String> bestAlias = new IdentityHashMap<>();
        Map<Command, Integer> bestScore = new IdentityHashMap<>();
        for (String alias : manager.getCommandAliases()) {
            Command command = manager.getCommand(alias);
            int score = FuzzyMatch.score(query, alias);
            if (command == null || score == FuzzyMatch.NO_MATCH) continue;
            if (score > bestScore.getOrDefault(command, FuzzyMatch.NO_MATCH)) {
                bestScore.put(command, score);
                bestAlias.put(command, alias);
            }
        }
        bestAlias.forEach((command, alias) -> builder.suggest(alias, tooltip(command, alias)));
        return builder.build();
    }

    private static Suggestions withLiterals(CommandManager manager, ParseResults<CommandManager> parse,
                                            Suggestions brigadier, String full, int cursor) {
        SuggestionContext<CommandManager> context;
        try {
            context = parse.getContext().findSuggestionContext(cursor);
        } catch (RuntimeException e) {
            return brigadier;
        }
        if (context.startPos > cursor) return brigadier;
        String token = full.substring(context.startPos, cursor);
        Module module = moduleOf(manager, context.parent);

        Set<String> literals = new HashSet<>();
        SuggestionsBuilder builder = new SuggestionsBuilder(full, context.startPos);
        for (CommandNode<CommandManager> child : context.parent.getChildren()) {
            if (!(child instanceof LiteralCommandNode<CommandManager> literal)) continue;
            literals.add(literal.getLiteral());
            if (!FuzzyMatch.matches(token, literal.getLiteral())) continue;
            builder.suggest(literal.getLiteral(), module == null ? null : settingTooltip(module, literal.getLiteral()));
        }
        if (literals.isEmpty()) return brigadier;

        List<Suggestion> merged = new ArrayList<>(builder.build().getList());
        for (Suggestion suggestion : brigadier.getList()) {
            if (!literals.contains(suggestion.getText())) merged.add(suggestion);
        }
        return merged.isEmpty() ? brigadier : Suggestions.create(full, merged);
    }

    private static Module moduleOf(CommandManager manager, CommandNode<CommandManager> parent) {
        if (!(parent instanceof LiteralCommandNode<CommandManager> literal)) return null;
        Command command = manager.getCommand(literal.getLiteral());
        return command == null ? null : CommandHelp.moduleFor(command);
    }

    private static Message settingTooltip(Module module, String literal) {
        Setting<?> setting = null;
        for (Setting<?> candidate : module.getSettings()) {
            if (CommandHelp.settingLiteral(candidate).equals(literal)) setting = candidate;
        }
        if (setting == null) return null;
        return new LiteralMessage("当前 " + CommandHelp.formatValue(setting.getValue()) + " · " + CommandHelp.typeHint(setting));
    }

    private static Message tooltip(Command command, String alias) {
        String summary = CommandRunner.summaryOf(command);
        return new LiteralMessage(alias.equals(command.getName()) ? summary : command.getName() + " · " + summary);
    }
}
