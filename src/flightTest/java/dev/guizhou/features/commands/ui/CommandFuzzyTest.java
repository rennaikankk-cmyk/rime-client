package dev.guizhou.features.commands.ui;

import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class CommandFuzzyTest {
    private static int checks;

    public static void main(String[] args) {
        scoreBands();
        highlightPositions();
        ranking();
        didYouMean();
        editDistance();
        System.out.println("CommandFuzzyTest passed: " + checks + " checks");
    }

    private static void scoreBands() {
        int exact = FuzzyMatch.score("toggle", "toggle");
        int prefix = FuzzyMatch.score("tog", "toggle");
        int initials = FuzzyMatch.score("ac", "AutoCrystal");
        int substring = FuzzyMatch.score("crystal", "AutoCrystal");
        int subsequence = FuzzyMatch.score("tgl", "toggle");
        int typo = FuzzyMatch.score("toggel", "toggle");
        check(exact == 1000, "exact match scores highest");
        check(prefix > initials && prefix < exact, "prefix beats word initials");
        check(initials == 750, "camel-case initials match");
        check(substring > subsequence && substring < initials, "substring beats scattered subsequence");
        check(subsequence > 200 && subsequence <= 400, "subsequence band");
        check(typo > 0 && typo < subsequence, "single transposition still matches, below subsequence");
        check(FuzzyMatch.score("frined", "friend") > 0, "transposed letters in a longer word match");
        check(FuzzyMatch.score("xyz", "toggle") == FuzzyMatch.NO_MATCH, "unrelated input does not match");
        check(FuzzyMatch.score("", "anything") == 1 && FuzzyMatch.score("   ", "x") == 1, "blank query lists everything");
        check(FuzzyMatch.score("a", null) == FuzzyMatch.NO_MATCH, "null candidate never matches");
        check(FuzzyMatch.score("tog", "toggle") > FuzzyMatch.score("tog", "toggleable"), "shorter prefix completion ranks first");
        check(FuzzyMatch.bestScore("pm", "guangximine", "PacketMine") == 750, "best of several names is used");
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            check(FuzzyMatch.score("ITEMS", "items") == 1000, "matching is locale independent");
        } finally {
            Locale.setDefault(previous);
        }
        check(FuzzyMatch.initials("AutoCrystal").equals("ac"), "initials from camel case");
        check(FuzzyMatch.initials("anchor_aura").equals("aa"), "initials from separators");
        check(FuzzyMatch.initials("hud-editor2").equals("he2"), "digits start a new word");
    }

    private static void highlightPositions() {
        check(Arrays.equals(FuzzyMatch.positions("tog", "toggle"), new int[]{0, 1, 2}), "prefix highlight");
        check(Arrays.equals(FuzzyMatch.positions("GL", "toggle"), new int[]{3, 4}), "substring highlight ignores case");
        check(Arrays.equals(FuzzyMatch.positions("tge", "toggle"), new int[]{0, 2, 5}), "subsequence highlight");
        check(FuzzyMatch.positions("zz", "toggle").length == 0, "no highlight without a match");
        check(FuzzyMatch.positions("", "toggle").length == 0, "blank query highlights nothing");
    }

    private static void ranking() {
        List<String> ranked = FuzzyMatch.rank("t", List.of("help", "telemetry", "timings", "t", "toggle"));
        check(ranked.equals(List.of("t", "toggle", "timings", "telemetry")), "exact, then shortest prefix; misses dropped: " + ranked);
        check(FuzzyMatch.rank("fr", List.of("config", "fakeplayer", "prefix", "friend"))
                .equals(List.of("friend", "fakeplayer")), "prefix before subsequence");
        check(FuzzyMatch.rank("", List.of("b", "A", "c")).equals(List.of("A", "b", "c")), "blank query is alphabetical");

        StringRange range = StringRange.between(1, 3);
        List<Suggestion> suggestions = List.of(new Suggestion(range, "enemy"), new Suggestion(range, "fakeplayer"),
                new Suggestion(range, "friend"), new Suggestion(range, "funny"));
        List<Suggestion> order = FuzzyMatch.rank("fr", suggestions, Suggestion::getText);
        check(order.size() == 2 && order.get(0).getText().equals("friend") && order.get(1).getText().equals("fakeplayer"),
                "completion popup order follows fuzzy rank");
    }

    private static void didYouMean() {
        List<String> aliases = List.of("toggle", "t", "timings", "help", "friend", "telemetry", "enemy");
        check(FuzzyMatch.didYouMean("togle", aliases, 3).get(0).equals("toggle"), "missing letter suggests toggle");
        check(FuzzyMatch.didYouMean("hepl", aliases, 3).equals(List.of("help")), "transposition suggests help");
        check(FuzzyMatch.didYouMean("e", aliases, 3).equals(List.of("enemy")), "one letter only suggests prefixes");
        check(FuzzyMatch.didYouMean("help", List.of("help"), 3).isEmpty(), "known commands are not suggested");
        check(FuzzyMatch.didYouMean("qqqq", aliases, 3).isEmpty(), "gibberish has no suggestion");
        check(FuzzyMatch.didYouMean("t", aliases, 2).size() == 2, "limit is respected");
        check(FuzzyMatch.didYouMean("FRIEND ", List.of("friend", "Friend"), 3).isEmpty(), "exact match in any case is known");
    }

    private static void editDistance() {
        check(FuzzyMatch.distance("kitten", "sitting") == 3, "classic edit distance");
        check(FuzzyMatch.distance("ab", "ba") == 1, "adjacent transposition is one edit");
        check(FuzzyMatch.distance("", "abc") == 3, "insertions from empty");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
