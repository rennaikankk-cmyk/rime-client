package dev.guizhou.features.commands.ui;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.guizhou.features.commands.Command;
import dev.guizhou.manager.CommandManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Locale;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;
import static dev.guizhou.features.commands.Command.SINGLE_FAILURE;

/** Executes a prefixed chat line and turns every failure into a readable, actionable reply. */
public final class CommandRunner {
    private static final Logger LOGGER = LogManager.getLogger("Commands");

    private CommandRunner() {}

    public static void run(CommandManager manager, String message) {
        String prefix = manager.matchedPrefix(message);
        if (prefix == null) prefix = manager.getCommandPrefix();
        String input = message.substring(Math.min(message.length(), prefix.length())).trim();
        if (input.isEmpty()) {
            ChatFeed.send(ChatTone.MUTED, Component.empty()
                    .append(ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text("输入 "), ChatPalette.MUTED))
                    .append(ChatFeed.command("help", null))
                    .append(ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text(" 查看全部命令"), ChatPalette.MUTED)));
            return;
        }
        try {
            int result = manager.getDispatcher().execute(input, manager);
            if (result == SINGLE_SUCCESS) {
                ChatFeed.send(ChatTone.SUCCESS, dev.guizhou.util.lang.ClientLanguage.text("已执行"));
            } else if (result == SINGLE_FAILURE) {
                ChatFeed.send(ChatTone.ERROR, dev.guizhou.util.lang.ClientLanguage.text("命令执行失败"));
            }
        } catch (CommandSyntaxException e) {
            LOGGER.debug("Command syntax error in '{}': {}", input, e.getMessage());
            report(manager, input, e);
        } catch (RuntimeException e) {
            LOGGER.error("Command '{}' threw", input, e);
            ChatFeed.send(ChatTone.ERROR, dev.guizhou.util.lang.ClientLanguage.text("命令运行出错（") + e.getClass().getSimpleName() + dev.guizhou.util.lang.ClientLanguage.text("），详情见日志"));
        }
    }

    private static void report(CommandManager manager, String input, CommandSyntaxException e) {
        String prefix = manager.getCommandPrefix();
        String token = SyntaxErrors.firstToken(input).toLowerCase(Locale.ROOT);
        Command command = manager.getCommand(token);

        if (command == null) {
            MutableComponent body = Component.empty()
                    .append(ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text("未知命令 "), ChatPalette.DANGER_SOFT))
                    .append(ChatFeed.text(prefix + token, ChatPalette.TEXT));
            List<String> guesses = FuzzyMatch.didYouMean(token, manager.getCommandAliases(), 3);
            if (!guesses.isEmpty()) {
                body.append(ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text("   你是不是想输入 "), ChatPalette.MUTED));
                for (int i = 0; i < guesses.size(); i++) {
                    if (i > 0) body.append(ChatFeed.text(" / ", ChatPalette.SUBTLE));
                    Command guess = manager.getCommand(guesses.get(i));
                    body.append(ChatFeed.command(guesses.get(i) + " ", guess == null ? null
                            : ChatFeed.text(summaryOf(guess), ChatPalette.MUTED)));
                }
            }
            body.append(ChatFeed.text("   · ", ChatPalette.SUBTLE))
                    .append(ChatFeed.suggest(dev.guizhou.util.lang.ClientLanguage.text("全部命令"), prefix + "help", null, ChatPalette.ACCENT_2));
            ChatFeed.replace("command-error", ChatTone.ERROR, body);
            return;
        }

        MutableComponent body = Component.empty()
                .append(ChatFeed.text(SyntaxErrors.describe(e), ChatPalette.DANGER_SOFT));
        SyntaxErrors.Snippet snippet = SyntaxErrors.snippet(e.getInput(), e.getCursor(), 28);
        if (snippet != null && e.getCursor() > token.length()) {
            body.append(ChatFeed.text("   " + (snippet.before().startsWith("…") ? "" : prefix) + snippet.before(),
                            ChatPalette.MUTED))
                    .append(Component.literal(snippet.error().isEmpty() ? "▁" : snippet.error())
                            .withStyle(style -> style.withColor(ChatPalette.DANGER).withUnderlined(true)))
                    .append(ChatFeed.text(snippet.after(), ChatPalette.SUBTLE));
        }
        body.append("\n  ").append(ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text("用法"), ChatPalette.SUBTLE));
        CommandHelp.appendUsages(body, manager, command, 4);
        ChatFeed.replace("command-error", ChatTone.ERROR, body);
    }

    public static String summaryOf(Command command) {
        var module = CommandHelp.moduleFor(command);
        return module != null ? CommandHelp.moduleSummary(module) : command.getSummary();
    }
}
