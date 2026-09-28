package dev.guizhou.features.commands.ui;

/** Semantic level of a client chat line: drives the glyph, the indicator bar and the body tint. */
public enum ChatTone {
    INFO(ChatPalette.ACCENT, "•", "信息"),
    SUCCESS(ChatPalette.SUCCESS, "✔", "成功"),
    WARN(ChatPalette.WARNING, "⚠", "注意"),
    ERROR(ChatPalette.DANGER, "✖", "错误"),
    MUTED(ChatPalette.MUTED, "·", "提示");

    private final int color;
    private final String glyph;
    private final String label;

    ChatTone(int color, String glyph, String label) {
        this.color = color;
        this.glyph = glyph;
        this.label = label;
    }

    public int color() {
        return color;
    }

    public String glyph() {
        return glyph;
    }

    public String label() {
        return label;
    }
}
