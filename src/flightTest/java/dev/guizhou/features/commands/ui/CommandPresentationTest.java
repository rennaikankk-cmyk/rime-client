package dev.guizhou.features.commands.ui;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import java.util.ArrayList;
import java.util.List;

public final class CommandPresentationTest {
    private static int checks;

    public static void main(String[] args) {
        legacyTones();
        repeatCollapsing();
        pagination();
        syntaxErrors();
        metadata();
        palette();
        System.out.println("CommandPresentationTest passed: " + checks + " checks");
    }

    private static void legacyTones() {
        expect("{red} Failed to execute command", ChatTone.INFO, ChatTone.ERROR, "Failed to execute command");
        expect("  {green} saved", ChatTone.INFO, ChatTone.SUCCESS, "saved");
        expect("{yellow}careful", ChatTone.INFO, ChatTone.WARN, "careful");
        expect("{gray} hint", ChatTone.INFO, ChatTone.MUTED, "hint");
        expect("{green} %s {reset} is already on your friends list.", ChatTone.SUCCESS, ChatTone.SUCCESS,
                "{green} %s {reset} is already on your friends list.");
        expect("{global} %s", ChatTone.INFO, ChatTone.INFO, "{global} %s");
        expect("§c[Rime] 授权校验未通过", ChatTone.INFO, ChatTone.ERROR, "[Rime] 授权校验未通过");
        expect("§cA §7B", ChatTone.INFO, ChatTone.INFO, "§cA §7B");
        expect("plain text", ChatTone.WARN, ChatTone.WARN, "plain text");
        expect(null, ChatTone.INFO, ChatTone.INFO, "");
        check(LegacyMarkup.hasMarkup("a {} b"), "pop token is markup");
        check(LegacyMarkup.hasMarkup("{#FF0000}x"), "hex colour is markup");
        check(!LegacyMarkup.hasMarkup("coords {x, y}"), "braces around prose are not markup");
        check(!LegacyMarkup.hasMarkup("unterminated {red"), "unterminated token is not markup");
    }

    private static void repeatCollapsing() {
        RepeatCollapser collapser = new RepeatCollapser(4000);
        RepeatCollapser.Decision first = collapser.offer(null, "INFO|a", 0);
        RepeatCollapser.Decision second = collapser.offer(null, "INFO|a", 1000);
        check(first.count() == 1 && !first.replace(), "first line is new");
        check(second.key().equals(first.key()) && second.count() == 2 && second.replace(), "repeat merges with counter");
        RepeatCollapser.Decision third = collapser.offer(null, "INFO|a", 3500);
        check(third.count() == 3 && third.key().equals(first.key()), "window slides with each repeat");
        RepeatCollapser.Decision late = collapser.offer(null, "INFO|a", 9000);
        check(late.count() == 1 && !late.replace() && !late.key().equals(first.key()), "expired window starts a new line");
        RepeatCollapser.Decision other = collapser.offer(null, "INFO|b", 9100);
        check(other.count() == 1 && !other.key().equals(late.key()), "different text is a new line");
        check(collapser.offer(null, "ERROR|b", 9200).count() == 1, "tone is part of the identity");

        RepeatCollapser.Decision keyed = collapser.offer("module:Surround", "SUCCESS|Surround · 已开启", 9300);
        RepeatCollapser.Decision keyedAgain = collapser.offer("module:Surround", "SUCCESS|Surround · 已开启", 9400);
        check(keyed.key().equals("module:Surround") && keyed.replace() && keyed.count() == 1, "keyed line replaces in place");
        check(keyedAgain.count() == 1, "keyed status lines never show a counter");
        check(collapser.offer(null, "ERROR|b", 9500).count() == 1, "keyed line breaks unkeyed adjacency");

        collapser.offer(null, "INFO|c", 20000);
        check(collapser.offer(null, "INFO|c", 19000).count() == 1, "clock going backwards never merges");
        collapser.reset();
        check(collapser.offer(null, "INFO|c", 20100).count() == 1, "reset forgets the last line");
    }

    private static void pagination() {
        List<Integer> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) items.add(i);
        HelpPager.Page<Integer> first = HelpPager.page(items, 1, 8);
        check(first.items().equals(items.subList(0, 8)) && first.pages() == 3 && first.total() == 20, "first page");
        check(!first.hasPrevious() && first.hasNext(), "first page navigation");
        HelpPager.Page<Integer> last = HelpPager.page(items, 3, 8);
        check(last.items().equals(items.subList(16, 20)) && last.hasPrevious() && !last.hasNext(), "last page is partial");
        check(HelpPager.page(items, 99, 8).page() == 3, "too-large page clamps to last");
        check(HelpPager.page(items, 0, 8).page() == 1, "non-positive page clamps to first");
        HelpPager.Page<Integer> empty = HelpPager.page(List.of(), 2, 8);
        check(empty.pages() == 1 && empty.items().isEmpty() && empty.page() == 1, "empty listing has one empty page");

        record Named(String name, CommandCategory category) {}
        List<Named> grouped = HelpPager.grouped(List.of(
                new Named("timings", CommandCategory.DIAGNOSTICS), new Named("toggle", CommandCategory.MODULES),
                new Named("Bind", CommandCategory.MODULES), new Named("friend", CommandCategory.SOCIAL)),
                Named::category, Named::name);
        check(grouped.stream().map(Named::name).toList().equals(List.of("Bind", "toggle", "friend", "timings")),
                "grouped by category order, then name");
    }

    private static void syntaxErrors() {
        SyntaxErrors.Snippet snippet = SyntaxErrors.snippet("friend add Bob extra", 11, 28);
        check(snippet.before().equals("friend add ") && snippet.error().equals("Bob") && snippet.after().equals(" extra"),
                "snippet isolates the failing token");
        SyntaxErrors.Snippet cut = SyntaxErrors.snippet("a".repeat(40) + " x", 41, 10);
        check(cut.before().startsWith("…") && cut.before().length() == 11 && cut.error().equals("x"), "long prefix is elided");
        check(SyntaxErrors.snippet("abc", 100, 5).error().isEmpty(), "cursor past the end clamps");
        check(SyntaxErrors.snippet(null, 0, 5) == null && SyntaxErrors.snippet("x", -1, 5) == null, "invalid input");
        check(SyntaxErrors.snippet("x " + "y".repeat(30), 0, 5).after().endsWith("…"), "long remainder is elided");
        check(SyntaxErrors.firstToken("  toggle surround ").equals("toggle") && SyntaxErrors.firstToken(null).isEmpty(),
                "first token");

        var builtIn = CommandSyntaxException.BUILT_IN_EXCEPTIONS;
        check(SyntaxErrors.describe(builtIn.readerExpectedInt().create()).equals("这里需要一个整数"), "expected int");
        check(SyntaxErrors.describe(builtIn.readerExpectedBool().create()).equals("这里需要 true 或 false"), "expected bool");
        check(SyntaxErrors.describe(builtIn.integerTooLow().create(0, 1)).equals("数值不能小于 1"), "lower bound is extracted");
        check(SyntaxErrors.describe(builtIn.doubleTooHigh().create(9.5, 6.0)).equals("数值不能大于 6.0"), "upper bound");
        check(SyntaxErrors.describe(builtIn.dispatcherUnknownArgument().create()).equals("参数不正确"), "unknown argument");
        check(SyntaxErrors.describe(new SimpleCommandExceptionType(new LiteralMessage("找不到模块 x")).create())
                .equals("找不到模块 x"), "custom messages pass through");

        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.literal("friend")
                .then(LiteralArgumentBuilder.literal("add")
                        .then(RequiredArgumentBuilder.argument("name", StringArgumentType.word()).executes(c -> 1))));
        CommandSyntaxException unknown = thrown(() -> dispatcher.execute("frend add x", new Object()));
        check(SyntaxErrors.isUnknownCommand(unknown), "unknown root is detected");
        CommandSyntaxException incomplete = thrown(() -> dispatcher.execute("friend add", new Object()));
        check(SyntaxErrors.isUnknownCommand(incomplete) && SyntaxErrors.describe(incomplete).equals("命令不完整或不存在"),
                "incomplete known command reports incompleteness");
        CommandSyntaxException wrongLiteral = thrown(() -> dispatcher.execute("friend bogus", new Object()));
        check(!SyntaxErrors.isUnknownCommand(wrongLiteral), "wrong sub-command is not an unknown root");
        SyntaxErrors.Snippet at = SyntaxErrors.snippet(wrongLiteral.getInput(), wrongLiteral.getCursor(), 28);
        check(at.error().equals("bogus"), "snippet points at the bad sub-command: " + at);
    }

    private static void metadata() {
        CommandUsage add = new CommandUsage(" add <玩家> ", " 添加好友 ");
        check(add.syntax().equals("add <玩家>") && add.note().equals("添加好友"), "usage fields are trimmed");
        check(add.fill().equals("add "), "fill stops at the first placeholder");
        check(new CommandUsage("temp <天数> add <玩家>", "").fill().equals("temp "), "fill with several placeholders");
        check(new CommandUsage("start [秒数]", "").fill().equals("start "), "optional placeholder");
        check(new CommandUsage("list", null).fill().equals("list") && new CommandUsage(null, null).fill().isEmpty(),
                "literal-only usages fill completely");
        check(add.render(".", "friend").equals(".friend add <玩家>"), "rendered usage");
        check(new CommandUsage("", "").render("!", "help").equals("!help"), "empty syntax renders the bare command");

        CommandCatalog.Entry friend = CommandCatalog.lookup("FRIEND");
        check(friend != null && friend.category() == CommandCategory.SOCIAL && !friend.usages().isEmpty(),
                "catalog covers the friend command");
        check(CommandCatalog.lookup("prefix").category() == CommandCategory.CONFIG, "catalog covers prefix");
        check(CommandCatalog.lookup("nope") == null && CommandCatalog.lookup(null) == null, "unknown commands have no entry");
    }

    private static void palette() {
        check(ChatPalette.mix(0x000000, 0xFFFFFF, 0.5f) == 0x808080, "midpoint mix");
        check(ChatPalette.mix(0x123456, 0xFFFFFF, -1f) == 0x123456 && ChatPalette.mix(0, 0xABCDEF, 2f) == 0xABCDEF,
                "mix clamps t");
        check(ChatPalette.LEGACY.get("green") == ChatPalette.SUCCESS && ChatPalette.LEGACY.get("red") == ChatPalette.DANGER
                && ChatPalette.LEGACY.get("reset") == ChatPalette.TEXT, "legacy names map onto the palette");
        for (String vanilla : List.of("black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold",
                "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white")) {
            check(ChatPalette.LEGACY.containsKey(vanilla), "vanilla colour name kept: " + vanilla);
        }
        for (ChatTone tone : ChatTone.values()) {
            check(!tone.glyph().isBlank() && !tone.label().isBlank() && (tone.color() & 0xFF000000) == 0,
                    "tone has glyph, label and RGB colour: " + tone);
        }
    }

    private static void expect(String template, ChatTone fallback, ChatTone tone, String body) {
        LegacyMarkup.Parsed parsed = LegacyMarkup.detect(template, fallback);
        check(parsed.tone() == tone && parsed.body().equals(body),
                "legacy '" + template + "' -> " + parsed.tone() + " '" + parsed.body() + "'");
    }

    private interface Failing {
        void run() throws CommandSyntaxException;
    }

    private static CommandSyntaxException thrown(Failing action) {
        try {
            action.run();
        } catch (CommandSyntaxException e) {
            return e;
        }
        throw new AssertionError("expected a syntax error");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
