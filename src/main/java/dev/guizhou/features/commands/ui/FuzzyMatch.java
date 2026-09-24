package dev.guizhou.features.commands.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Case-insensitive fuzzy matching for command names, module names and argument values.
 * Score bands (higher is better): exact 1000, prefix ~900, word initials 750, substring ~600,
 * subsequence 201..400, small typo 70..110, no match -1.
 */
public final class FuzzyMatch {
    public static final int NO_MATCH = -1;

    private FuzzyMatch() {}

    public static int score(String query, String candidate) {
        if (candidate == null || candidate.isEmpty()) return NO_MATCH;
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        String c = candidate.toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return 1;
        if (c.equals(q)) return 1000;
        if (c.startsWith(q)) return 900 - Math.min(99, c.length() - q.length());
        if (initials(candidate).startsWith(q) && q.length() >= 2) return 750;
        int index = c.indexOf(q);
        if (index >= 0) return 600 - Math.min(99, index);
        int gaps = subsequenceGaps(q, c);
        if (gaps >= 0) return 400 - Math.min(199, gaps * 10 + (c.length() - q.length()));
        if (q.length() >= 3) {
            int distance = typoDistance(q, c);
            int allowed = q.length() >= 6 ? 2 : 1;
            if (distance <= allowed) return 150 - distance * 40;
        }
        return NO_MATCH;
    }

    public static boolean matches(String query, String candidate) {
        return score(query, candidate) != NO_MATCH;
    }

    /** Best score among several names of the same item (alias, display name, ID). */
    public static int bestScore(String query, String... names) {
        int best = NO_MATCH;
        for (String name : names) best = Math.max(best, score(query, name));
        return best;
    }

    /** Indices of candidate characters that explain the match, for highlighting. */
    public static int[] positions(String query, String candidate) {
        if (candidate == null || query == null) return new int[0];
        String q = query.strip().toLowerCase(Locale.ROOT);
        String c = candidate.toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return new int[0];
        if (c.startsWith(q)) return range(0, q.length());
        int index = c.indexOf(q);
        if (index >= 0) return range(index, index + q.length());
        int[] out = new int[q.length()];
        int qi = 0;
        for (int ci = 0; ci < c.length() && qi < q.length(); ci++) {
            if (c.charAt(ci) == q.charAt(qi)) out[qi++] = ci;
        }
        return qi == q.length() ? out : new int[0];
    }

    /** Items that match, best first; ties prefer shorter keys, then alphabetical order. */
    public static <T> List<T> rank(String query, Collection<T> items, Function<T, String> key) {
        List<Map.Entry<T, Integer>> scored = new ArrayList<>();
        for (T item : items) {
            int score = score(query, key.apply(item));
            if (score != NO_MATCH) scored.add(Map.entry(item, score));
        }
        scored.sort(Comparator.<Map.Entry<T, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparingInt(entry -> key.apply(entry.getKey()).length())
                .thenComparing(entry -> key.apply(entry.getKey()), String.CASE_INSENSITIVE_ORDER));
        List<T> out = new ArrayList<>(scored.size());
        for (Map.Entry<T, Integer> entry : scored) out.add(entry.getKey());
        return out;
    }

    public static List<String> rank(String query, Collection<String> candidates) {
        return rank(query, candidates, Function.identity());
    }

    /**
     * "Did you mean" candidates for a complete but unknown token. Weak subsequence hits on very short
     * input are ignored so a stray letter does not suggest half the command list.
     */
    public static List<String> didYouMean(String query, Collection<String> candidates, int limit) {
        String q = query == null ? "" : query.strip();
        if (q.isEmpty() || limit <= 0) return List.of();
        Map<String, Integer> unique = new LinkedHashMap<>();
        for (String candidate : candidates) {
            int score = score(q, candidate);
            if (score == NO_MATCH || score == 1000) continue;
            if (score < 600 && score > 150 && q.length() < 3) continue;
            unique.merge(candidate.toLowerCase(Locale.ROOT), score, Math::max);
        }
        List<String> ranked = rank(q, unique.keySet());
        return ranked.size() > limit ? List.copyOf(ranked.subList(0, limit)) : ranked;
    }

    /** Optimal-string-alignment edit distance (adjacent transpositions count as one edit). */
    public static int distance(String a, String b) {
        int n = a.length(), m = b.length();
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) d[i][0] = i;
        for (int j = 0; j <= m; j++) d[0][j] = j;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[n][m];
    }

    /** Word initials: "AutoCrystal" -> "ac", "anchor_aura" -> "aa", "hud-editor2" -> "he2". */
    static String initials(String text) {
        StringBuilder out = new StringBuilder();
        char previous = ' ';
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            boolean boundary = i == 0
                    || (Character.isUpperCase(ch) && Character.isLowerCase(previous))
                    || (Character.isLetterOrDigit(ch) && !Character.isLetterOrDigit(previous))
                    || (Character.isDigit(ch) && !Character.isDigit(previous));
            if (boundary && Character.isLetterOrDigit(ch)) out.append(Character.toLowerCase(ch));
            previous = ch;
        }
        return out.toString();
    }

    private static int subsequenceGaps(String q, String c) {
        int qi = 0, gaps = 0, last = -1;
        for (int ci = 0; ci < c.length() && qi < q.length(); ci++) {
            if (c.charAt(ci) == q.charAt(qi)) {
                if (last >= 0 && ci - last > 1) gaps++;
                last = ci;
                qi++;
            }
        }
        return qi == q.length() ? gaps : -1;
    }

    /** Distance to the candidate itself or to a prefix of similar length (typo while still typing). */
    private static int typoDistance(String q, String c) {
        int best = distance(q, c);
        for (int len = Math.max(1, q.length() - 1); len <= Math.min(c.length(), q.length() + 1); len++) {
            best = Math.min(best, distance(q, c.substring(0, len)));
        }
        return best;
    }

    private static int[] range(int from, int to) {
        int[] out = new int[to - from];
        for (int i = 0; i < out.length; i++) out[i] = from + i;
        return out;
    }
}
