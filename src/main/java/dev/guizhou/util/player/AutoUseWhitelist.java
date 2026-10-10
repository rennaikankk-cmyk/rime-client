package dev.guizhou.util.player;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Cached item-id whitelist. The pattern compiles once per distinct regex string;
 * an invalid pattern compiles once, reports {@link #valid()} == false and then
 * matches nothing, so a broken setting can neither crash the tick loop nor
 * trigger a recompile-per-tick storm.
 */
public final class AutoUseWhitelist {
    private static Entry cache;

    private final String source;
    private final Pattern pattern;

    private AutoUseWhitelist(String source, Pattern pattern) {
        this.source = source;
        this.pattern = pattern;
    }

    public static AutoUseWhitelist of(String regex) {
        if (regex == null) regex = "";
        Entry cached = cache;
        if (cached != null && cached.regex.equals(regex)) return cached.whitelist;
        AutoUseWhitelist compiled = compile(regex);
        cache = new Entry(regex, compiled);
        return compiled;
    }

    private static AutoUseWhitelist compile(String regex) {
        try {
            return new AutoUseWhitelist(regex, Pattern.compile(regex));
        } catch (PatternSyntaxException invalid) {
            return new AutoUseWhitelist(regex, null);
        }
    }

    public boolean valid() {
        return pattern != null;
    }

    public boolean matches(String itemId) {
        return pattern != null && itemId != null && pattern.matcher(itemId).matches();
    }

    public String source() {
        return source;
    }

    private static final class Entry {
        final String regex;
        final AutoUseWhitelist whitelist;

        Entry(String regex, AutoUseWhitelist whitelist) {
            this.regex = regex;
            this.whitelist = whitelist;
        }
    }
}
