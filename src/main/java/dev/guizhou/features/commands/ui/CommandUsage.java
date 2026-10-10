package dev.guizhou.features.commands.ui;

/**
 * One usage line: {@code syntax} follows the command name (e.g. "add &lt;玩家&gt;"), {@code note} explains it.
 * {@link #fill()} is the literal text worth pre-filling into the chat box (everything before the first
 * placeholder).
 */
public record CommandUsage(String syntax, String note) {
    public CommandUsage {
        syntax = syntax == null ? "" : syntax.strip();
        note = note == null ? "" : note.strip();
    }

    public String syntax() { return dev.guizhou.util.lang.ClientLanguage.text(syntax); }
    public String note() { return dev.guizhou.util.lang.ClientLanguage.text(note); }

    public String fill() {
        int cut = syntax.length();
        for (char marker : new char[]{'<', '[', '('}) {
            int index = syntax.indexOf(marker);
            if (index >= 0) cut = Math.min(cut, index);
        }
        return syntax.substring(0, cut);
    }

    public String render(String prefix, String command) {
        return syntax.isEmpty() ? prefix + command : prefix + command + " " + syntax();
    }
}
