package dev.guizhou.features.commands.ui;

/** Help grouping; declaration order is display order. */
public enum CommandCategory {
    MODULES("模块", ChatPalette.ACCENT),
    SOCIAL("社交", ChatPalette.ACCENT_2),
    CONFIG("配置", ChatPalette.SUCCESS),
    TOOLS("工具", ChatPalette.WARNING),
    DIAGNOSTICS("诊断", ChatPalette.MUTED),
    MODULE_SETTINGS("模块设置", ChatPalette.ACCENT_SOFT),
    OTHER("其他", ChatPalette.MUTED);

    private final String label;
    private final int color;

    CommandCategory(String label, int color) {
        this.label = label;
        this.color = color;
    }

    public String label() {
        return label;
    }

    public int color() {
        return color;
    }
}
