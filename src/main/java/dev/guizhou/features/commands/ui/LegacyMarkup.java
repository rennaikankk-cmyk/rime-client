package dev.guizhou.features.commands.ui;

import java.util.Locale;

/**
 * Maps legacy single-colour templates ("{red} Failed ...", "§cDenied") onto a tone so old call sites
 * render with the new glyph/indicator instead of a raw colour. Templates that colour only part of the
 * text keep their markup and fall back to the caller's tone.
 */
public final class LegacyMarkup {
    public record Parsed(ChatTone tone, String body) {}

    private LegacyMarkup() {}

    public static Parsed detect(String template, ChatTone fallback) {
        if (template == null) return new Parsed(fallback, "");
        String trimmed = template.stripLeading();

        if (trimmed.length() >= 2 && trimmed.charAt(0) == '§') {
            ChatTone tone = toneForCode(trimmed.charAt(1));
            String rest = trimmed.substring(2);
            if (tone != null && rest.indexOf('§') < 0) return new Parsed(tone, rest.stripLeading());
            return new Parsed(fallback, template);
        }

        if (trimmed.startsWith("{")) {
            int end = trimmed.indexOf('}');
            if (end > 1) {
                ChatTone tone = toneForName(trimmed.substring(1, end));
                String rest = trimmed.substring(end + 1);
                if (tone != null && !hasMarkup(rest)) return new Parsed(tone, rest.stripLeading());
            }
        }
        return new Parsed(fallback, template);
    }

    /** True when the text still contains a CatFormat colour/pop token such as {green} or {}. */
    public static boolean hasMarkup(String text) {
        int open = text.indexOf('{');
        while (open >= 0) {
            int close = text.indexOf('}', open + 1);
            if (close < 0) return false;
            String token = text.substring(open + 1, close);
            if (token.isEmpty() || token.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_' || c == '#' || c == ':')) {
                return true;
            }
            open = text.indexOf('{', close + 1);
        }
        return false;
    }

    private static ChatTone toneForName(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "red", "dark_red", "err" -> ChatTone.ERROR;
            case "green", "dark_green", "ok" -> ChatTone.SUCCESS;
            case "yellow", "gold", "warn" -> ChatTone.WARN;
            case "gray", "dark_gray", "muted" -> ChatTone.MUTED;
            default -> null;
        };
    }

    private static ChatTone toneForCode(char code) {
        return switch (Character.toLowerCase(code)) {
            case 'c', '4' -> ChatTone.ERROR;
            case 'a', '2' -> ChatTone.SUCCESS;
            case 'e', '6' -> ChatTone.WARN;
            case '7', '8' -> ChatTone.MUTED;
            default -> null;
        };
    }
}
