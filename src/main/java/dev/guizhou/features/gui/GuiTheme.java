package dev.guizhou.features.gui;

import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.client.ClickGuiModule;
import dev.guizhou.util.ColorUtil;

import java.awt.Color;

public final class GuiTheme {
    private GuiTheme() {}

    public static final int PANEL_WIDTH    = 110;
    public static final int HEADER_HEIGHT  = 15;
    public static final int PANEL_SPACING  = 5;
    public static final int MODULE_HEIGHT  = 13;
    public static final int SETTING_HEIGHT = 12;
    public static final int SETTING_INDENT = 6;
    public static final int SETTING_RIGHT_PAD = 2;

    /* IB (InternalBeyond) Infernal 色板：深夜蓝玻璃底 + 雾白文字 */
    public static final int BODY_BG          = new Color(14,  22,  42, 170).getRGB(); // --ibs-side

    public static final int SETTINGS_TRAY_BG = new Color(10,  16,  32, 160).getRGB();

    public static final int SCREEN_DIM       = new Color(0, 0, 0, 110).getRGB();

    public static final int SEPARATOR        = new Color(190, 210, 240,  40).getRGB(); // 雾蓝分隔线
    public static final int OUTLINE          = new Color(0, 0, 0, 160).getRGB();
    public static final int OUTLINE_INNER    = new Color(0, 0, 0, 90).getRGB();
    public static final int HIGHLIGHT_TOP    = new Color(200, 220, 250,  32).getRGB(); // 玻璃顶缘雾蓝高光

    public static final int TEXT_HEADER         = 0xFFF0F4FB; // --ibr-fg 偏亮
    public static final int TEXT_MODULE_ON      = 0xFFE0E6F2; // --ibr-fg #e0e6f2
    public static final int TEXT_MODULE_OFF     = 0xFFADB8D0; // --ibr-fg2 #adb8d0
    public static final int TEXT_SETTING        = 0xFFE0E6F2;
    public static final int TEXT_SETTING_VALUE  = 0xFFADB8D0;

    public static final int SLIDER_TRACK        = new Color(190, 210, 240,  38).getRGB();
    public static final int SLIDER_TRACK_HOVER  = new Color(190, 210, 240,  62).getRGB();
    public static final int CHECKBOX_OFF_BG     = new Color(190, 210, 240,  28).getRGB();
    public static final int CHECKBOX_BORDER     = new Color(0, 0, 0, 140).getRGB();

    public static Color categoryColor(Module.Category category) {
        return categoryColor(category, 0f);
    }

    public static Color categoryColor(Module.Category category, float yOffset) {
        ClickGuiModule m = ClickGuiModule.getInstance();
        return m == null ? new Color(90, 105, 135) : m.categoryAccent(category, yOffset);
    }

    public static Color moduleColor(Module.Category category, float yOffset) {
        ClickGuiModule m = ClickGuiModule.getInstance();
        return m == null ? new Color(90, 105, 135) : m.moduleAccent(category, yOffset);
    }

    public static Color darken(Color c, float f) {
        return new Color(
                (int) Math.max(0, c.getRed() * f),
                (int) Math.max(0, c.getGreen() * f),
                (int) Math.max(0, c.getBlue() * f),
                c.getAlpha());
    }

    public static Color brighten(Color c, float amount) {
        return new Color(
                (int) Math.min(255, c.getRed() + (255 - c.getRed()) * amount),
                (int) Math.min(255, c.getGreen() + (255 - c.getGreen()) * amount),
                (int) Math.min(255, c.getBlue() + (255 - c.getBlue()) * amount),
                c.getAlpha());
    }

    public static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }
}
