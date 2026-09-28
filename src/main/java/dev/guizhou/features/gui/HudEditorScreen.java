package dev.guizhou.features.gui;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.client.HudClientModule;
import dev.guizhou.features.modules.client.HudModule;
import dev.guizhou.util.render.HudDraw;
import dev.guizhou.util.render.ShadcnDraw;
import dev.guizhou.util.render.font.HudFonts;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Native HUD layout editor: drag any element to reposition it (persisted as
 * normalized per-element settings), toggle elements, reset the layout. The
 * world HUD keeps rendering through the normal pipeline, so this screen only
 * draws editor chrome plus explicitly-marked previews for elements that did
 * not render this frame — never a second copy of live elements. The toolbar
 * is drawn last and hit-tested first, so it always stays on top.
 *
 * Dragging snaps element edges and centres to the screen edges, the screen
 * centre lines and other elements within {@link #SNAP} px (hold Alt to place
 * freely); the matched lines are drawn as guides. The wheel scales the
 * hovered element and a percentage chip confirms the new size.
 */
public class HudEditorScreen extends Screen {

    private static final float PANEL_W = 136f;
    private static final float ROW_H = 16f;
    private static final float PAD = 7f;
    private static final float TEXT = 8.5f;
    private static final float BTN_H = 17f;
    private static final float HEADER = 32f;
    private static final int SNAP = 4, EDGE_MARGIN = 4;
    private static final long SCALE_CHIP_MS = 1200;

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("Totems", "图腾"), Map.entry("Armor", "护甲"), Map.entry("Counts", "物资"),
            Map.entry("Coordinates", "坐标"), Map.entry("Ping", "网络"), Map.entry("Radar", "附近玩家"),
            Map.entry("Players", "玩家表"),
            Map.entry("ActiveModules", "模块列表"), Map.entry("Notifier", "通知"), Map.entry("Speed", "速度"),
            Map.entry("TargetHud", "空战 HUD"), Map.entry("Diagnostics", "诊断"));

    private final Screen parent;
    private final HudClientModule hud;

    private HudModule dragging;
    private int grabOffX, grabOffY;
    private HudModule selected;
    private HudModule hovered;
    private int guideX = Integer.MIN_VALUE, guideY = Integer.MIN_VALUE;
    private HudModule scaled;
    private long scaledAt;

    private final List<Row> rows = new ArrayList<>();
    private float panelX, panelY = 8f, panelW, panelH, visibleBottom;
    private float resetX, doneX, btnY, btnW;
    private int firstVisibleRow;

    private record Row(HudModule module, float y) {}

    public HudEditorScreen(Screen parent) {
        super(Component.literal("HUD Editor"));
        this.parent = parent;
        this.hud = GuizhouWare.moduleManager == null ? null
                : GuizhouWare.moduleManager.getModuleByClass(HudClientModule.class);
    }

    public static String label(HudModule module) {
        return dev.guizhou.util.lang.ClientLanguage.text(LABELS.getOrDefault(module.getName(), module.getName()));
    }

    @Override
    protected void init() {
        layout();
    }

    private void layout() {
        rows.clear();
        if (hud == null) return;
        // Small GUI sizes: the toolbar never exceeds the viewport, and rows
        // that do not fit are skipped in both drawing and hit testing.
        panelW = Math.min(PANEL_W, this.width - 12f);
        float maxPanelH = this.height - 20f;
        float footer = BTN_H + 34f;
        int maxRows = Math.max(1, (int) ((maxPanelH - HEADER - footer) / ROW_H));
        firstVisibleRow = Math.clamp(firstVisibleRow, 0, Math.max(0, hud.elementList().size() - maxRows));
        panelH = Math.min(maxPanelH, HEADER + hud.elementList().size() * ROW_H + footer);
        float y = panelY + HEADER;
        List<HudModule> list = hud.elementList();
        for (int i = firstVisibleRow; i < list.size() && rows.size() < maxRows; i++) {
            rows.add(new Row(list.get(i), y));
            y += ROW_H;
        }
        visibleBottom = panelY + HEADER + rows.size() * ROW_H;
        panelX = this.width - panelW - 8f;
        btnY = Math.max(visibleBottom + 5f, panelY + panelH - footer + 5f);
        btnW = (panelW - PAD * 3f) / 2f;
        resetX = panelX + PAD;
        doneX = resetX + btnW + PAD;
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        if (hud != null) hud.endDrag();
        dragging = null;
        layout();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (hud == null) {
            onClose();
            return;
        }
        int accent = ShadcnDraw.accent();
        g.fill(0, 0, this.width, this.height, 0x6605070C);
        int grid = 24;
        for (int gx = grid; gx < this.width; gx += grid) g.fill(gx, 0, gx + 1, this.height, 0x0AFFFFFF);
        for (int gy = grid; gy < this.height; gy += grid) g.fill(0, gy, this.width, gy + 1, 0x0AFFFFFF);
        int centre = ShadcnDraw.alpha(accent, dragging != null ? .28f : .14f);
        g.fill(this.width / 2, 0, this.width / 2 + 1, this.height, centre);
        g.fill(0, this.height / 2, this.width, this.height / 2 + 1, centre);

        hovered = null;
        if (dragging != null) {
            hovered = dragging;
        } else {
            List<HudModule> list = hud.elementList();
            for (int i = list.size() - 1; i >= 0; i--) {
                int[] r = elementRect(list.get(i));
                if (ShadcnDraw.contains(mouseX, mouseY, r[0], r[1], r[2], r[3])) {
                    hovered = list.get(i);
                    break;
                }
            }
        }

        // Live drag with snapping: the world HUD renders before this screen, so the override applies next frame.
        guideX = guideY = Integer.MIN_VALUE;
        if (dragging != null) {
            int[] r = elementRect(dragging);
            int x = Math.max(0, Math.min(mouseX - grabOffX, this.width - r[2]));
            int y = Math.max(0, Math.min(mouseY - grabOffY, this.height - r[3]));
            if (!altDown()) {
                int[] snapped = snap(dragging, x, y, r[2], r[3]);
                x = snapped[0];
                y = snapped[1];
            }
            hud.updateDrag(dragging, x, y);
        }

        // 1) element previews and outlines first — toolbar stays on top
        for (HudModule module : hud.elementList()) {
            boolean on = hud.elementToggle(module).getValue();
            int[] r = elementRect(module);
            boolean live = hud.isEnabled() && !minecraft.options.hideGui && on && module.wasRendered();
            if (!live) {
                ShadcnDraw.round(g, r[0], r[1], r[2], r[3], 2f, 0xA0151820);
                ShadcnDraw.outline(g, r[0], r[1], r[2], r[3], 2f, 1f, 0x803A4254);
                ShadcnDraw.text(g, ShadcnDraw.fit(label(module) + (on ? dev.guizhou.util.lang.ClientLanguage.text(" · 预览") : dev.guizhou.util.lang.ClientLanguage.text(" · 已关闭")), r[2] - 12, 8f),
                        r[0] + 6f, r[1] + 4f, 8f, HudFonts.SEMI, ShadcnDraw.SUBTLE);
            }
            boolean active = module == dragging || module == selected;
            if (!active && module != hovered) {
                ShadcnDraw.outline(g, r[0] - 1, r[1] - 1, r[2] + 2, r[3] + 2, 2f, 1f, 0x26FFFFFF);
                continue;
            }
            int outline = active ? accent : 0x80FFFFFF;
            ShadcnDraw.outline(g, r[0] - 1, r[1] - 1, r[2] + 2, r[3] + 2, 2f, 1f, outline);
            if (active) handles(g, r, accent);
            String name = label(module) + (module.isCustomBound() ? "" : dev.guizhou.util.lang.ClientLanguage.text(" · 锚点"));
            String meta = module == dragging ? r[0] + ", " + r[1] + "  " + r[2] + "×" + r[3]
                    : Math.round(hud.scaleOf(module) * 100) + "%";
            float nw = ShadcnDraw.width(name, 8f, HudFonts.SEMI), mw = ShadcnDraw.width(meta, 7f);
            float lw = nw + mw + 18f;
            float ly = r[1] - 18f < 2 ? r[1] + r[3] + 5f : r[1] - 18f;
            float lx = Math.max(2, Math.min(r[0] - 2, this.width - lw - 2));
            ShadcnDraw.round(g, lx, ly, lw, 13, 2f, active ? accent : 0xF0252A37);
            if (!active) ShadcnDraw.outline(g, lx, ly, lw, 13, 2f, 1f, ShadcnDraw.BORDER);
            int fg = active ? 0xFF0B0C10 : ShadcnDraw.TEXT;
            HudDraw.textMid(g, name, lx + 7f, ly + 6.5f, 8f, HudFonts.SEMI, fg);
            HudDraw.textMid(g, meta, lx + 11f + nw, ly + 6.5f, 7f, HudFonts.REGULAR,
                    active ? ShadcnDraw.alpha(0xFF0B0C10, .7f) : ShadcnDraw.MUTED);
        }

        // snap guides over the previews
        int guide = ShadcnDraw.alpha(ShadcnDraw.ACCENT_2, .85f);
        if (guideX != Integer.MIN_VALUE) g.fill(guideX, 0, guideX + 1, this.height, guide);
        if (guideY != Integer.MIN_VALUE) g.fill(0, guideY, this.width, guideY + 1, guide);

        // wheel-scale confirmation chip
        if (scaled != null) {
            long age = System.currentTimeMillis() - scaledAt;
            if (age > SCALE_CHIP_MS) scaled = null;
            else {
                float a = Math.min(1f, (SCALE_CHIP_MS - age) / 250f);
                int[] r = elementRect(scaled);
                String pct = Math.round(hud.scaleOf(scaled) * 100) + "%";
                float w = ShadcnDraw.width(pct, 9f, HudFonts.BOLD) + 14f;
                float cx = Math.max(2, Math.min(r[0] + r[2] - w, this.width - w - 2));
                float cy = Math.min(this.height - 18f, r[1] + r[3] + 5f);
                ShadcnDraw.round(g, cx, cy, w, 15f, 2f, ShadcnDraw.alpha(0xF0151820, a));
                ShadcnDraw.outline(g, cx, cy, w, 15f, 2f, 1f, ShadcnDraw.alpha(accent, a));
                HudDraw.textMidCentered(g, pct, cx + w / 2f, cy + 7.5f, 9f, HudFonts.BOLD, ShadcnDraw.alpha(ShadcnDraw.TEXT, a));
            }
        }

        // 2) toolbar last — always the topmost layer
        drawToolbar(g, accent);
        super.render(g, mouseX, mouseY, delta);
    }

    private void drawToolbar(GuiGraphics g, int accent) {
        ShadcnDraw.round(g, panelX, panelY, panelW, panelH, 2f, 0xF5151820);
        ShadcnDraw.outline(g, panelX, panelY, panelW, panelH, 2f, 1f, ShadcnDraw.BORDER);
        int enabled = 0;
        for (HudModule m : hud.elementList()) if (hud.elementToggle(m).getValue()) enabled++;
        ShadcnDraw.text(g, hud.isEnabled() ? dev.guizhou.util.lang.ClientLanguage.text("HUD 布局") : dev.guizhou.util.lang.ClientLanguage.text("HUD 已关闭"), panelX + PAD, panelY + 8f, 9f,
                HudFonts.SEMI, ShadcnDraw.TEXT);
        ShadcnDraw.text(g, enabled + " / " + hud.elementList().size() + dev.guizhou.util.lang.ClientLanguage.text(" 已启用"), panelX + PAD, panelY + 18f, 7f,
                ShadcnDraw.SUBTLE);
        switchShape(g, panelX + panelW - 27f, panelY + 10.5f, hud.isEnabled(), accent);
        HudDraw.rule(g, panelX + PAD, panelY + HEADER - 3f, panelW - PAD * 2);

        for (Row row : rows) {
            boolean on = hud.elementToggle(row.module()).getValue();
            boolean hot = row.module() == dragging || row.module() == selected || row.module() == hovered;
            if (hot) ShadcnDraw.round(g, panelX + 3f, row.y(), panelW - 6f, ROW_H - 1f, 2f, ShadcnDraw.RAISED);
            if (row.module() == selected) ShadcnDraw.round(g, panelX + 3f, row.y() + 3.5f, 2f, ROW_H - 8f, 0, accent);
            float mid = row.y() + (ROW_H - 1f) / 2f;
            String pct = Math.round(hud.scaleOf(row.module()) * 100) + "%";
            float pw = ShadcnDraw.width(pct, 6.5f);
            HudDraw.textMid(g, ShadcnDraw.fit(label(row.module()), panelW - 50f - pw, TEXT), panelX + PAD + 2f, mid, TEXT,
                    HudFonts.REGULAR, hot ? ShadcnDraw.TEXT : on ? ShadcnDraw.MUTED : ShadcnDraw.SUBTLE);
            HudDraw.textMidRight(g, pct, panelX + panelW - 32f, mid, 6.5f, HudFonts.REGULAR, ShadcnDraw.SUBTLE);
            switchShape(g, panelX + panelW - 27f, mid - 5.5f, on, accent);
        }

        ShadcnDraw.round(g, resetX, btnY, btnW, BTN_H, 2f, ShadcnDraw.SURFACE);
        ShadcnDraw.outline(g, resetX, btnY, btnW, BTN_H, 2f, 1f, ShadcnDraw.BORDER);
        HudDraw.textMidCentered(g, dev.guizhou.util.lang.ClientLanguage.text("重置布局"), resetX + btnW / 2f, btnY + BTN_H / 2f, 8.5f, HudFonts.SEMI, ShadcnDraw.TEXT);
        ShadcnDraw.round(g, doneX, btnY, btnW, BTN_H, 2f, accent);
        HudDraw.textMidCentered(g, dev.guizhou.util.lang.ClientLanguage.text("完成"), doneX + btnW / 2f, btnY + BTN_H / 2f, 8.5f, HudFonts.SEMI, 0xFF0B0C10);
        float hint = btnY + BTN_H + 9f;
        HudDraw.textMid(g, ShadcnDraw.fit(dev.guizhou.util.lang.ClientLanguage.text("拖动移动 · 滚轮缩放 · 右键外观"), panelW - 2 * PAD, 7f), panelX + PAD, hint, 7f,
                HudFonts.REGULAR, ShadcnDraw.SUBTLE);
        HudDraw.textMid(g, ShadcnDraw.fit(dev.guizhou.util.lang.ClientLanguage.text("方向键微调 · R 复位 · Alt 自由放置"), panelW - 2 * PAD, 7f), panelX + PAD, hint + 10f, 7f,
                HudFonts.REGULAR, ShadcnDraw.SUBTLE);
    }

    /** Corner handles on the active selection frame. */
    private static void handles(GuiGraphics g, int[] r, int accent) {
        float[][] corners = {{r[0] - 2, r[1] - 2}, {r[0] + r[2] + 2, r[1] - 2},
                {r[0] - 2, r[1] + r[3] + 2}, {r[0] + r[2] + 2, r[1] + r[3] + 2}};
        for (float[] c : corners) {
            ShadcnDraw.round(g, c[0] - 2f, c[1] - 2f, 4f, 4f, 0, accent);
        }
    }

    /**
     * Snaps the dragged rectangle's left/centre/right and top/centre/bottom to
     * screen edges (and the standard margin), screen centre lines and other
     * visible elements. The closest match per axis within {@link #SNAP} wins.
     */
    private int[] snap(HudModule module, int x, int y, int w, int h) {
        int[] bestX = {SNAP + 1, Integer.MIN_VALUE}, bestY = {SNAP + 1, Integer.MIN_VALUE};
        int sw = this.width, sh = this.height;
        for (int line : new int[]{0, EDGE_MARGIN, sw / 2, sw - EDGE_MARGIN, sw}) consider(bestX, line, x, w);
        for (int line : new int[]{0, EDGE_MARGIN, sh / 2, sh - EDGE_MARGIN, sh}) consider(bestY, line, y, h);
        for (HudModule other : hud.elementList()) {
            if (other == module || !hud.elementToggle(other).getValue()) continue;
            int[] o = elementRect(other);
            for (int line : new int[]{o[0], o[0] + o[2] / 2, o[0] + o[2], o[0] - EDGE_MARGIN, o[0] + o[2] + EDGE_MARGIN})
                consider(bestX, line, x, w);
            for (int line : new int[]{o[1], o[1] + o[3] / 2, o[1] + o[3], o[1] - EDGE_MARGIN, o[1] + o[3] + EDGE_MARGIN})
                consider(bestY, line, y, h);
        }
        if (bestX[1] != Integer.MIN_VALUE) { x += bestX[0]; guideX = bestX[1]; }
        if (bestY[1] != Integer.MIN_VALUE) { y += bestY[0]; guideY = bestY[1]; }
        return new int[]{Math.max(0, Math.min(x, sw - w)), Math.max(0, Math.min(y, sh - h))};
    }

    /** best = {offset, line}; tests the start, centre and end of [pos, pos+size) against one line. */
    private static void consider(int[] best, int line, int pos, int size) {
        for (int point : new int[]{pos, pos + size / 2, pos + size}) {
            int d = line - point;
            if (Math.abs(d) <= SNAP && Math.abs(d) < Math.abs(best[0])) {
                best[0] = d;
                best[1] = line;
            }
        }
    }

    private boolean altDown() {
        long handle = minecraft.getWindow().handle();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
    }

    /**
     * Draggable rectangle. Resolution order matters for not-rendered elements:
     * live drag override, then the saved normalized position, then the anchor's
     * last-known bounds — a stale rect must never pin a repositioned preview.
     */
    private int[] elementRect(HudModule module) {
        int w = module.wasRendered() ? module.boundW() : module.previewWidth(hud.scaleOf(module));
        int h = module.wasRendered() ? module.boundH() : module.previewHeight(hud.scaleOf(module));
        int[] live = hud.livePosition(module);
        if (live != null) return clamped(live[0], live[1], w, h);
        if (hud.isCustomPosition(module)) {
            return clamped(Math.round(hud.normalizedX(module) * this.width),
                    Math.round(hud.normalizedY(module) * this.height), w, h);
        }
        if (module.boundW() > 0) return clamped(module.boundX(), module.boundY(), w, h);
        int idx = hud.elementList().indexOf(module);
        return clamped(24, 24 + Math.max(0, idx) * 24, w, h);
    }

    private int[] clamped(int x, int y, int w, int h) {
        return new int[]{
                Math.max(0, Math.min(x, Math.max(0, this.width - w))),
                Math.max(0, Math.min(y, Math.max(0, this.height - h))),
                w, h};
    }

    private static void switchShape(GuiGraphics g, float x, float y, boolean on, int accent) {
        ShadcnDraw.round(g, x, y, 20, 11, 5.5f, on ? accent : 0xFF2E3444);
        ShadcnDraw.round(g, x + (on ? 10.5f : 1.5f), y + 1.5f, 8, 8, 4, on ? 0xFFF4F5F8 : 0xFFB8BECC);
    }

    private boolean inToolbar(double mx, double my) {
        return mx >= panelX && mx < panelX + panelW && my >= panelY && my < panelY + panelH;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (inToolbar(mx, my)) {
            firstVisibleRow -= (int) Math.signum(vertical);
            layout();
            return true;
        }
        if (hovered != null && dragging == null) {
            selected = hovered;
            hud.resizeElement(selected, Math.round(hud.scaleOf(selected)*100) + Math.signum(vertical)*5);
            scaled = selected;
            scaledAt = System.currentTimeMillis();
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics g, int mx, int my, float delta) {}

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (hud == null) return super.mouseClicked(click, doubled);
        double mx = click.x();
        double my = click.y();
        if (ShadcnDraw.contains(mx, my, panelX + panelW - 30, panelY + 6, 26, 19)) {
            hud.toggle();
            return true;
        }
        if (ShadcnDraw.contains(mx, my, resetX, btnY, btnW, BTN_H)) {
            hud.resetLayout();
            return true;
        }
        if (ShadcnDraw.contains(mx, my, doneX, btnY, btnW, BTN_H)) {
            onClose();
            return true;
        }
        if (inToolbar(mx, my)) {
            for (Row row : rows) {
                if (ShadcnDraw.contains(mx, my, panelX, row.y(), panelW, ROW_H)) {
                    selected = row.module();
                    if (click.button() == 1) openAppearance(selected);
                    else if (mx >= panelX + panelW - 30) {
                        var toggle = hud.elementToggle(row.module());
                        toggle.setValue(!toggle.getValue());
                    }
                    return true;
                }
            }
            return true;
        }
        List<HudModule> list = hud.elementList();
        for (int i = list.size() - 1; i >= 0; i--) {
            HudModule module = list.get(i);
            int[] r = elementRect(module);
            if (ShadcnDraw.contains(mx, my, r[0], r[1], r[2], r[3])) {
                if (click.button() == 1) {openAppearance(module);return true;}
                if (click.button() != 0) return true;
                dragging = module;
                selected = module;
                grabOffX = (int) Math.round(mx) - r[0];
                grabOffY = (int) Math.round(my) - r[1];
                return true;
            }
        }
        selected = null;
        return super.mouseClicked(click, doubled);
    }

    private void openAppearance(HudModule module) {
        onClose();
        GuizhouWareGui gui = parent instanceof GuizhouWareGui p ? p : GuizhouWareGui.getClickGui();
        minecraft.setScreen(gui);
        gui.openHudAppearance(hud, module.getName());
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (dragging != null && hud != null) {
            int[] live = hud.livePosition(dragging);
            int[] r = elementRect(dragging);
            if (live != null && r != null) {
                hud.savePosition(dragging, live[0], live[1], r[2], r[3]);
            }
            hud.endDrag();
            dragging = null;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (hud == null) return super.keyPressed(input);
        int k = input.input();
        if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) {
            onClose();
            return true;
        }
        if (k == GLFW.GLFW_KEY_TAB) {
            List<HudModule> list = hud.elementList();
            if (!list.isEmpty()) {
                int idx = selected == null ? 0 : Math.floorMod(list.indexOf(selected)
                        + ((input.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1), list.size());
                selected = list.get(idx);
            }
            return true;
        }
        if (k == GLFW.GLFW_KEY_R && selected != null) {
            // return only the selected element to its default anchor
            hud.clearPosition(selected);
            selected = null;
            return true;
        }
        int dx = 0, dy = 0;
        int step = (input.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 ? 10 : 1;
        if (k == GLFW.GLFW_KEY_LEFT) dx = -step;
        else if (k == GLFW.GLFW_KEY_RIGHT) dx = step;
        else if (k == GLFW.GLFW_KEY_UP) dy = -step;
        else if (k == GLFW.GLFW_KEY_DOWN) dy = step;
        if ((dx != 0 || dy != 0) && selected != null) {
            // Persist immediately; the element picks the saved position up next
            // frame through origin(), so no live drag override is leaked here.
            int[] r = elementRect(selected);
            hud.savePosition(selected, r[0] + dx, r[1] + dy, r[2], r[3]);
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void onClose() {
        if (hud != null) {
            hud.endDrag();
            hud.markEditorClosed();
        }
        if (GuizhouWare.configManager != null) GuizhouWare.configManager.save();
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        // Covers disconnect and any teardown that bypasses onClose.
        if (hud != null) hud.markEditorClosed();
        if (GuizhouWare.configManager != null) GuizhouWare.configManager.save();
        super.removed();
    }
}
