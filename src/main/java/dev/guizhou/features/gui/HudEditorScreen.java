package dev.guizhou.features.gui;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.client.HudClientModule;
import dev.guizhou.features.modules.client.HudModule;
import dev.guizhou.util.render.ShadcnDraw;
import dev.guizhou.util.render.font.HudFonts;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import dev.guizhou.util.compat.input.KeyEvent;
import dev.guizhou.util.compat.input.MouseButtonEvent;
import dev.guizhou.util.compat.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Native HUD layout editor: drag any element to reposition it (persisted as
 * normalized per-element settings), toggle elements, reset the layout. The
 * world HUD keeps rendering through the normal pipeline, so this screen only
 * draws editor chrome plus explicitly-marked previews for elements that did
 * not render this frame — never a second copy of live elements. The toolbar
 * is drawn last and hit-tested first, so it always stays on top.
 */
public class HudEditorScreen extends Screen {

    private static final float PANEL_W = 118f;
    private static final float ROW_H = 16f;
    private static final float PAD = 6f;
    private static final float TEXT = 11f;
    private static final float BTN_H = 16f;

    private final Screen parent;
    private final HudClientModule hud;

    private HudModule dragging;
    private int grabOffX, grabOffY;
    private HudModule selected;
    private HudModule hovered;

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
        float header = 26f;
        float footer = BTN_H + 22f;
        int maxRows = Math.max(1, (int) ((maxPanelH - header - footer) / ROW_H));
        firstVisibleRow = Math.clamp(firstVisibleRow, 0, Math.max(0, hud.elementList().size() - maxRows));
        panelH = Math.min(maxPanelH, header + hud.elementList().size() * ROW_H + footer);
        float y = 8f + header;
        List<HudModule> list = hud.elementList();
        for (int i = firstVisibleRow; i < list.size() && rows.size() < maxRows; i++) {
            rows.add(new Row(list.get(i), y));
            y += ROW_H;
        }
        visibleBottom = 8f + header + rows.size() * ROW_H;
        panelX = this.width - panelW - 8f;
        btnY = Math.max(visibleBottom + 4f, 8f + panelH - footer + 2f);
        btnW = (panelW - PAD * 3f) / 2f;
        resetX = panelX + PAD;
        doneX = resetX + btnW + PAD;
    }

    @Override
    public void resize(net.minecraft.client.Minecraft minecraft, int width, int height) {
        super.resize(minecraft, width, height);
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
        g.fill(0, 0, this.width, this.height, 0x500A0A0A);

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

        // 1) element previews and outlines first — toolbar stays on top
        for (HudModule module : hud.elementList()) {
            boolean on = hud.elementToggle(module).getValue();
            int[] r = elementRect(module);
            if (!(hud.isEnabled() && !minecraft.options.hideGui && on && module.wasRendered())) {
                ShadcnDraw.panel(g, r[0], r[1], r[2], r[3], 6f);
                ShadcnDraw.text(g, ShadcnDraw.fit(module.getName() + " · 预览", r[2]-12, 10f), r[0] + 6f, r[1] + 4f, 10f, ShadcnDraw.MUTED);
            }
            int outline = module == dragging ? ShadcnDraw.ACCENT
                    : module == selected ? 0xB0E5E5E5
                    : module == hovered ? 0x80E5E5E5 : 0x66343434;
            outlineRect(g, r[0] - 2, r[1] - 2, r[2] + 4, r[3] + 4, outline);
            if (module == dragging || module == selected || module == hovered) {
                String label = module.getName() + (module.isCustomBound() ? "" : " · 锚点");
                ShadcnDraw.text(g, label, r[0], Math.max(2, r[1] - 13f), 10f, ShadcnDraw.MUTED);
            }
        }

        // 2) toolbar last — always the topmost layer
        ShadcnDraw.panel(g, panelX, panelY, panelW, panelH, 8f);
        ShadcnDraw.text(g, hud.isEnabled()?"HUD 布局":"HUD 已关闭", panelX + PAD, panelY + 6f, 10f, HudFonts.SEMI, ShadcnDraw.TEXT);
        ShadcnDraw.round(g,panelX+panelW-26,panelY+7,18,10,5,hud.isEnabled()?ShadcnDraw.ACCENT:ShadcnDraw.SURFACE);
        ShadcnDraw.round(g,panelX+panelW-(hud.isEnabled()?14:22),panelY+9,6,6,3,hud.isEnabled()?ShadcnDraw.BG:ShadcnDraw.MUTED);
        for (Row row : rows) {
            boolean on = hud.elementToggle(row.module()).getValue();
            boolean hot = row.module() == dragging || row.module() == selected || row.module() == hovered;
            if (hot) ShadcnDraw.round(g, panelX + 3f, row.y() - 1f, panelW - 6f, ROW_H, 4f, ShadcnDraw.SURFACE);
            ShadcnDraw.text(g, ShadcnDraw.fit(row.module().getName(), panelW - 34f, TEXT),
                    panelX + PAD, row.y() + 3f, TEXT, hot ? ShadcnDraw.TEXT : ShadcnDraw.MUTED);
            float bx = panelX + panelW - 26f;
            ShadcnDraw.round(g, bx, row.y() + 3f, 18f, 10f, 5f, on ? ShadcnDraw.ACCENT : ShadcnDraw.SURFACE);
            if (on) ShadcnDraw.round(g, bx + 10f, row.y() + 5f, 6f, 6f, 3f, 0xFF0A0A0A);
            else ShadcnDraw.round(g, bx + 2f, row.y() + 5f, 6f, 6f, 3f, ShadcnDraw.MUTED);
        }

        ShadcnDraw.round(g, resetX, btnY, btnW, BTN_H, 5f, ShadcnDraw.SURFACE);
        ShadcnDraw.text(g, "重置布局", resetX + 8f, btnY + 3f, TEXT, ShadcnDraw.TEXT);
        ShadcnDraw.round(g, doneX, btnY, btnW, BTN_H, 5f, ShadcnDraw.ACCENT);
        ShadcnDraw.text(g, "完成", doneX + 10f, btnY + 3f, TEXT, 0xFF0A0A0A);
        ShadcnDraw.text(g, ShadcnDraw.fit("拖动移动 · 滚轮缩放 · 右键外观",panelW-2*PAD,8f), panelX + PAD, btnY + BTN_H + 6f, 8f, ShadcnDraw.MUTED);

        // 3) live drag: world HUD renders before this screen, override applies next frame
        if (dragging != null) {
            int[] r = elementRect(dragging);
            int x = Math.max(0, Math.min(mouseX - grabOffX, this.width - r[2]));
            int y = Math.max(0, Math.min(mouseY - grabOffY, this.height - r[3]));
            hud.updateDrag(dragging, x, y);
        }
        super.render(g, mouseX, mouseY, delta);
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

    private void outlineRect(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
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
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics g, int mx, int my, float delta) {}

    @Override
    public boolean mouseClicked(double mxRaw, double myRaw, int button) {
        return mouseClicked(new MouseButtonEvent(mxRaw, myRaw, new MouseButtonInfo(button, 0)), false);
    }

    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (hud == null) return super.mouseClicked(click.x(), click.y(), click.button());
        double mx = click.x();
        double my = click.y();
        if (ShadcnDraw.contains(mx,my,panelX+panelW-29,panelY+3,25,19)) {
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
                if (ShadcnDraw.contains(mx, my, panelX, row.y() - 1f, panelW, ROW_H)) {
                    selected = row.module();
                    if (click.button() == 1) openAppearance(selected);
                    else if (mx >= panelX + panelW - 29) {
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
        return super.mouseClicked(click.x(), click.y(), click.button());
    }

    private void openAppearance(HudModule module) {
        onClose();
        GuizhouWareGui gui = parent instanceof GuizhouWareGui p ? p : GuizhouWareGui.getClickGui();
        minecraft.setScreen(gui);
        gui.openHudAppearance(hud, module.getName());
    }

    @Override
    public boolean mouseReleased(double mxRaw, double myRaw, int button) {
        return mouseReleased(new MouseButtonEvent(mxRaw, myRaw, new MouseButtonInfo(button, 0)));
    }

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
        return super.mouseReleased(click.x(), click.y(), click.button());
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return keyPressed(new KeyEvent(keyCode, scanCode, modifiers));
    }

    public boolean keyPressed(KeyEvent input) {
        if (hud == null) return super.keyPressed(input.input(), input.scanCode(), input.modifiers());
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
        return super.keyPressed(input.input(), input.scanCode(), input.modifiers());
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
