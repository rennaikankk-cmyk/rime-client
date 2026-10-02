package dev.guizhou.features.gui;

import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.render.RenderUtil;
import dev.guizhou.util.render.font.HudFonts;
import net.minecraft.client.gui.GuiGraphics;

import java.awt.Color;

import static dev.guizhou.util.render.ShadcnDraw.*;

/** HSV popover shared by every ClickGUI style; writes the colour setting live while dragging. */
final class ColorPicker {
    static final float W = 250, H = 236;
    private static final float SV_W = 190, SV_H = 120, ALPHA_W = SV_W + 28;

    private Setting<?> target;
    private float x, y, hue, sat, bri, alpha;
    private int drag;
    private float svX, svY, hueX, alphaY, rowY, presetY, hexX;
    private final Runnable onHex;

    ColorPicker(Runnable onHex) {
        this.onHex = onHex;
    }

    Setting<?> target() { return target; }

    boolean active() { return target != null; }

    void close() {
        target = null;
        drag = 0;
    }

    void open(Setting<?> setting, Color c, float ax, float ay) {
        target = setting;
        float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
        hue = hsb[0];
        sat = hsb[1];
        bri = hsb[2];
        alpha = c.getAlpha() / 255f;
        x = ax;
        y = ay;
        drag = 0;
    }

    /** Keeps the popover inside [minX,maxX]x[minY,maxY] before drawing. */
    void draw(GuiGraphics g, float minX, float minY, float maxX, float maxY) {
        if (target == null) return;
        x = Math.clamp(x, minX, Math.max(minX, maxX - W));
        y = Math.clamp(y, minY, Math.max(minY, maxY - H));
        round(g, x + 3, y + 5, W, H, 12, alpha(0xFF000000, .45f));
        round(g, x, y, W, H, 12, RAISED);
        outline(g, x, y, W, H, 12, 1, 0xFF3A4254);
        svX = x + 14;
        svY = y + 14;
        int pure = Color.HSBtoRGB(hue, 1, 1) | 0xFF000000;
        RenderUtil.gradient(g, (int) svX, (int) svY, (int) (svX + SV_W), (int) (svY + SV_H), 0xFFFFFFFF, 0xFFFFFFFF, pure, pure);
        RenderUtil.gradient(g, (int) svX, (int) svY, (int) (svX + SV_W), (int) (svY + SV_H), 0x00000000, 0xFF000000, 0xFF000000, 0x00000000);
        outline(g, svX, svY, SV_W, SV_H, 3, 1, alpha(0xFFFFFFFF, .12f));
        float kx = svX + sat * SV_W, ky = svY + (1 - bri) * SV_H;
        outline(g, kx - 5, ky - 5, 10, 10, 5, 2, 0xFFFFFFFF);
        hueX = svX + SV_W + 12;
        int[] stops = {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};
        float seg = SV_H / 6f;
        for (int i = 0; i < 6; i++) {
            RenderUtil.gradient(g, (int) hueX, Math.round(svY + i * seg), (int) (hueX + 16), Math.round(svY + (i + 1) * seg),
                    stops[i], stops[i + 1], stops[i + 1], stops[i]);
        }
        round(g, hueX - 2, svY + hue * SV_H - 2, 20, 4, 2, 0xFFFFFFFF);
        alphaY = svY + SV_H + 12;
        checker(g, svX, alphaY, ALPHA_W, 12);
        int rgb = Color.HSBtoRGB(hue, sat, bri) & 0xFFFFFF;
        RenderUtil.gradient(g, (int) svX, (int) alphaY, (int) (svX + ALPHA_W), (int) (alphaY + 12), rgb, rgb, rgb | 0xFF000000, rgb | 0xFF000000);
        round(g, svX + alpha * ALPHA_W - 2, alphaY - 2, 4, 16, 2, 0xFFFFFFFF);
        Color current = current();
        rowY = alphaY + 24;
        checker(g, svX, rowY, 26, 26);
        round(g, svX, rowY, 26, 26, 7, current.getRGB());
        text(g, hex(current), svX + 36, rowY + 8.5f, 10, HudFonts.SEMI, TEXT);
        hexX = x + W - 96;
        round(g, hexX, rowY, 82, 26, 8, SURFACE);
        outline(g, hexX, rowY, 82, 26, 8, 1, BORDER);
        textCentered(g, dev.guizhou.util.lang.ClientLanguage.text("输入 HEX"), hexX + 41, rowY + 8, 9.5f, HudFonts.SEMI, TEXT);
        presetY = rowY + 36;
        float px = svX, gap = (ALPHA_W - 18 * presets().length) / (presets().length - 1);
        for (int preset : presets()) {
            round(g, px, presetY, 18, 18, 6, preset);
            outline(g, px, presetY, 18, 18, 6, 1, alpha(0xFFFFFFFF, .15f));
            px += 18 + gap;
        }
    }

    /** Returns true when the click lands inside the popover (and is consumed). */
    boolean press(double mx, double my) {
        if (target == null || !contains(mx, my, x, y, W, H)) return false;
        if (contains(mx, my, svX, svY, SV_W, SV_H)) drag = 1;
        else if (contains(mx, my, hueX - 2, svY, 20, SV_H)) drag = 2;
        else if (contains(mx, my, svX, alphaY - 2, ALPHA_W, 16)) drag = 3;
        else if (contains(mx, my, hexX, rowY, 82, 26)) { onHex.run(); return true; }
        else if (my >= presetY && my < presetY + 18) {
            int[] presets = presets();
            float gap = (ALPHA_W - 18 * presets.length) / (presets.length - 1);
            int index = (int) ((mx - svX) / (18 + gap));
            if (index >= 0 && index < presets.length && mx - svX - index * (18 + gap) <= 18) {
                int value = presets[index];
                float[] hsb = Color.RGBtoHSB((value >> 16) & 255, (value >> 8) & 255, value & 255, null);
                hue = hsb[0];
                sat = hsb[1];
                bri = hsb[2];
                apply();
            }
            return true;
        } else return true;
        drag(mx, my);
        return true;
    }

    void drag(double mx, double my) {
        switch (drag) {
            case 1 -> {
                sat = (float) Math.clamp((mx - svX) / SV_W, 0, 1);
                bri = 1 - (float) Math.clamp((my - svY) / SV_H, 0, 1);
            }
            case 2 -> hue = (float) Math.clamp((my - svY) / SV_H, 0, .999);
            case 3 -> alpha = (float) Math.clamp((mx - svX) / ALPHA_W, 0, 1);
            default -> { return; }
        }
        apply();
    }

    boolean dragging() { return drag != 0; }

    void release() { drag = 0; }

    float x() { return x; }

    float y() { return y; }

    private Color current() {
        int rgb = Color.HSBtoRGB(hue, sat, bri);
        return new Color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, Math.round(alpha * 255));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void apply() {
        if (target != null) ((Setting) target).setValue(current());
    }

    private static int[] presets() {
        return new int[]{accent(), ACCENT_2, 0xFFE39A92, 0xFFE0B15C, 0xFF9BBFA6, 0xFFC2C0B6, 0xFFFAF9F5, 0xFF30302E, 0xFF262624};
    }

    static void checker(GuiGraphics g, float cx, float cy, float cw, float ch) {
        int cell = 4;
        for (int yy = 0; yy < ch; yy += cell)
            for (int xx = 0; xx < cw; xx += cell)
                g.fill((int) (cx + xx), (int) (cy + yy), (int) Math.min(cx + xx + cell, cx + cw), (int) Math.min(cy + yy + cell, cy + ch),
                        ((xx / cell + yy / cell) & 1) == 0 ? 0xFFBFC3CC : 0xFF8D929C);
    }

    static String hex(Color c) {
        return String.format("#%02X%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha());
    }
}
