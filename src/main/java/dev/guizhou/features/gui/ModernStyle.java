package dev.guizhou.features.gui;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.client.ClickGuiModule;
import dev.guizhou.features.modules.client.HudClientModule;
import dev.guizhou.features.settings.Bind;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.HeartbeatManager;
import dev.guizhou.util.BuildConfig;
import dev.guizhou.util.render.Anim;
import dev.guizhou.util.render.RenderUtil;
import dev.guizhou.util.render.UiSounds;
import dev.guizhou.util.render.font.HudFonts;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.joml.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntConsumer;

import static dev.guizhou.util.render.ShadcnDraw.*;
import static org.lwjgl.glfw.GLFW.*;

/**
 * Shell control centre: a category rail, module cards and a settings drawer.
 * Every control writes through the normal Setting path, so events, visibility
 * rules and persistence behave exactly as with commands and config files.
 */
final class ModernStyle extends ShellStyle {
    private enum View { CATEGORY, FAVORITES, CONFIGS, BINDS }
    private enum Filter {
        ALL("全部"), ON("已启用"), OFF("未启用");
        final String label;
        Filter(String label) { this.label = label; }
    }

    private static final float SIDEBAR = 200, TOPBAR = 68, DRAWER = 384, CARD_H = 72, GAP = 10, FOOTER = 32;
    private static final int SIDE_BG = 0xFF12141B, DRAWER_BG = 0xFF161922, ROW_LINE = 0xFF20242F, KNOB = 0xFFF4F5F8;

    private final Anim anim = new Anim();
    private final List<Hit> hits = new ArrayList<>();
    private int modalFrom = -1;
    private Object hoverTarget;

    private View view = View.CATEGORY;
    private Module.Category category = Module.Category.COMBAT;
    private Module selected, lastShown;
    private Filter filter = Filter.ALL;
    private String query = "", page = "General", hint = "", validation = "";
    private ShadcnTextField search, editor, configName;
    private Setting<?> editing, binding, dragging, dropdown;
    private final ColorPicker picker = new ColorPicker(this::editPickedHex);
    private float popX, popY, popW;
    private int dropScroll, dropIndex;

    private float scale = 1, openScale = 1, openShift, x, y, w, h;
    private float gridTarget, gridMax, drawerTarget, drawerMax, listTarget, listMax, tabTarget, tabMax;
    private float dragX, dragW;
    private long opened;
    private String pendingDelete, toast;
    private long pendingDeleteAt, toastAt;

    private record Hit(float x, float y, float w, float h, IntConsumer action) {
        boolean at(double mx, double my) { return contains(mx, my, x, y, w, h); }
    }

    ModernStyle(GuizhouWareGui screen) {
        super(screen);
    }

    @Override
    float scale() { return scale; }

    @Override
    void reload() {
        selected = lastShown = null;
        gridTarget = drawerTarget = 0;
    }

    private Runnable back;

    void clearBack() {
        back = null;
    }

    /** Entered from another style; a back button and Esc return there. */
    void openConfigs(Runnable back) {
        this.back = back;
        view = View.CONFIGS;
        listTarget = 0;
        selected = null;
    }

    void openHudAppearance(HudClientModule hud, String element, Runnable back) {
        this.back = back;
        openHudAppearance(hud, element);
    }

    void openHudAppearance(HudClientModule hud, String element) {
        view = View.CATEGORY;
        category = Module.Category.CLIENT;
        query = "";
        if (search != null) search = newSearch();
        select(hud);
        page = element;
    }

    @Override
    boolean capturesMovementKeys() {
        return editor != null || binding != null || dropdown != null || picker.active()
                || search != null && search.focused || configName != null && configName.focused;
    }

    @Override
    void init() {
        opened = System.currentTimeMillis();
        anim.clear();
        anim.set("open", 0);
        search = newSearch();
        search.padLeft = 18;
        configName = new ShadcnTextField("", s -> validation = "");
        closePopovers();
        layout();
    }

    private ShadcnTextField newSearch() {
        ShadcnTextField field = new ShadcnTextField(query, s -> { query = s; gridTarget = listTarget = 0; });
        field.padLeft = 18;
        return field;
    }

    private void closePopovers() {
        editing = binding = dragging = dropdown = null;
        editor = null;
        picker.close();
    }

    private void layout() {
        float fit = Math.min(1, Math.min(width / 780f, height / 480f));
        w = Math.min(980, width / fit - 28);
        h = Math.min(600, height / fit - 28);
        ClickGuiModule gui = ClickGuiModule.getInstance();
        double requested = gui == null ? 1 : gui.menuScale.getValue() / 100;
        requested = Double.isFinite(requested) ? Math.clamp(requested, .6, 1.5) : 1;
        scale = Math.min(fit * (float) requested, Math.min((width - 12) / w, (height - 12) / h));
        x = (width / scale - w) / 2;
        y = (height / scale - h) / 2;
    }

    private boolean smooth() {
        ClickGuiModule gui = ClickGuiModule.getInstance();
        return gui == null || gui.smooth.getValue();
    }

    private float ease(Object key, float target, float speed) {
        return smooth() ? anim.to(key, target, speed) : target;
    }

    private final double[] localPoint = new double[2];
    private List<Module> frameModules;
    /** Built once per frame. Toggles land on the next frame, so the open GUI does not re-filter. */
    private List<Module> visibleFrame;
    private EnumMap<Module.Category, List<Module>> categoryFrame;
    private Set<String> favoriteFrame;

    /** Screen pixels to window-local units, undoing the entrance transform. */
    private double[] local(double sx, double sy) {
        double mx = sx / scale, my = sy / scale;
        float cx = x + w / 2, cy = y + h / 2;
        localPoint[0] = cx + (mx - cx) / openScale;
        localPoint[1] = cy + (my - cy - openShift) / openScale;
        return localPoint;
    }

    // ------------------------------------------------------------ frame

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        layout();
        if (frameModules == null) {
            frameModules = GuizhouWare.moduleManager.stream().filter(m -> !m.hidden)
                    .filter(m -> m.getCategory() != Module.Category.HUD)
                    .filter(m -> m.getCategory() != Module.Category.FUNNY || GuizhouWare.commandManager.isFunnyVisible())
                    .toList();
        }
        visibleFrame = null;
        categoryFrame = null;
        favoriteFrame = null;
        hits.clear();
        modalFrom = -1;
        hoverTarget = null;
        hint = dev.guizhou.util.lang.ClientLanguage.text("左键打开设置 · 右键切换开关 · 中键收藏 · Ctrl+F 搜索 · Esc 返回");
        boolean world = mc.level != null;
        int top = world ? 0x9005070C : 0xF60B0D12, bottom = world ? 0xC805070C : 0xF6090A0E;
        RenderUtil.gradient(g, 0, 0, width, height, top, bottom, bottom, top);

        float open = ease("open", 1, 11);
        openScale = 0.965f + 0.035f * open;
        openShift = (1 - open) * 10;
        double[] m = local(mouseX, mouseY);
        double mx = m[0], my = m[1];

        var pose = g.pose();
        pose.pushMatrix();
        pose.scale(scale, scale);
        float cx = x + w / 2, cy = y + h / 2;
        pose.translate(cx, cy + openShift);
        pose.scale(openScale, openScale);
        pose.translate(-cx, -cy);

        shadow(g, x, y, w, h, 18, 30, 0xFF000000);
        round(g, x, y, w, h, 18, BG);
        round(g, x, y, SIDEBAR + 18, h, 18, SIDE_BG);
        g.fill((int) (x + SIDEBAR), (int) y + 1, (int) (x + SIDEBAR + 18), (int) (y + h - 1), BG);
        g.fill((int) (x + SIDEBAR), (int) y + 1, (int) (x + SIDEBAR + 1), (int) (y + h - 1), BORDER);
        outline(g, x, y, w, h, 18, 1, BORDER);

        drawSidebar(g, mx, my);
        drawTopBar(g, mx, my);
        switch (view) {
            case CATEGORY, FAVORITES -> {
                drawGrid(g, mx, my);
                drawDrawer(g, mx, my);
            }
            case CONFIGS -> drawConfigs(g, mx, my);
            case BINDS -> drawBinds(g, mx, my);
        }
        text(g, fit(hint, w - SIDEBAR - 48, 8.5f), x + SIDEBAR + 24, y + h - 21, 8.5f, SUBTLE);
        drawToast(g);

        modalFrom = hits.size();
        if (dropdown != null) drawDropdown(g, mx, my);
        else if (picker.active()) drawPicker(g, mx, my);
        else if (editor != null) drawEditor(g, mx, my);
        else if (binding != null) drawBinding(g);
        else modalFrom = -1;
        pose.popMatrix();
        UiSounds.hover(hoverTarget);
    }

    // ------------------------------------------------------------ sidebar

    private void drawSidebar(GuiGraphics g, double mx, double my) {
        float sx = x + 18, sy = y + 20;
        shadow(g, sx, sy, 32, 32, 10, 8, alpha(accent(), .5f));
        roundGradient(g, sx, sy, 32, 32, 10, accent(), ACCENT_2);
        textCentered(g, "G", sx + 16, sy + 8.5f, 15, HudFonts.BOLD, 0xFF0B0C10);
        text(g, "Rime", sx + 43, sy + 4, 13.5f, HudFonts.SEMI, TEXT);
        text(g, "v" + BuildConfig.VERSION, sx + 43, sy + 20, 8, MUTED);

        List<Module.Category> cats = GuizhouWare.moduleManager.getCategories().stream()
                .filter(c -> c != Module.Category.HUD)
                .filter(c -> c != Module.Category.FUNNY || GuizhouWare.commandManager.isFunnyVisible()).toList();
        float statusTop = y + h - 64;
        float first = y + 80;
        int items = cats.size() + 4;
        float ih = Math.clamp((statusTop - 12 - first - 44) / items, 24, 34);
        float ny = first;
        sectionLabel(g, dev.guizhou.util.lang.ClientLanguage.text("模块"), ny);
        ny += 18;
        for (Module.Category c : cats) {
            List<Module> mods = modulesIn(c);
            long on = mods.stream().filter(Module::isEnabled).count();
            boolean active = view == View.CATEGORY && category == c && query.isBlank();
            navItem(g, iconFor(c), categoryName(c), on + "/" + mods.size(), active, true, ny, ih, mx, my, () -> {
                view = View.CATEGORY;
                category = c;
                query = "";
                search = newSearch();
                gridTarget = 0;
                selected = null;
            });
            ny += ih;
        }
        ny += 8;
        sectionLabel(g, dev.guizhou.util.lang.ClientLanguage.text("工具"), ny);
        ny += 18;
        int favs = favorites().size();
        navItem(g, Icon.STAR, dev.guizhou.util.lang.ClientLanguage.text("收藏"), favs == 0 ? null : String.valueOf(favs), view == View.FAVORITES, true, ny, ih, mx, my,
                () -> { view = View.FAVORITES; gridTarget = 0; selected = null; });
        ny += ih;
        navItem(g, Icon.FOLDER, dev.guizhou.util.lang.ClientLanguage.text("配置"), null, view == View.CONFIGS, true, ny, ih, mx, my,
                () -> { view = View.CONFIGS; listTarget = 0; selected = null; });
        ny += ih;
        navItem(g, Icon.KEYBOARD, dev.guizhou.util.lang.ClientLanguage.text("快捷键"), null, view == View.BINDS, true, ny, ih, mx, my,
                () -> { view = View.BINDS; listTarget = 0; selected = null; });
        ny += ih;
        boolean inWorld = mc.level != null;
        navItem(g, Icon.HUD, dev.guizhou.util.lang.ClientLanguage.text("编辑 HUD"), inWorld ? null : dev.guizhou.util.lang.ClientLanguage.text("进入世界后"), false, inWorld, ny, ih, mx, my, () -> {
            save();
            HudClientModule hud = GuizhouWare.moduleManager.getModuleByClass(HudClientModule.class);
            if (hud != null) hud.openEditor(screen);
        });

        boolean ok = HeartbeatManager.authorized();
        round(g, x + 12, statusTop, SIDEBAR - 24, 48, 12, SURFACE);
        outline(g, x + 12, statusTop, SIDEBAR - 24, 48, 12, 1, BORDER);
        round(g, x + 22, statusTop + 11, 26, 26, 8, alpha(ok ? SUCCESS : WARNING, .14f));
        icon(g, Icon.SHIELD, x + 27, statusTop + 16, 16, ok ? SUCCESS : WARNING);
        text(g, ok ? dev.guizhou.util.lang.ClientLanguage.text("授权有效") : dev.guizhou.util.lang.ClientLanguage.text("未授权"), x + 56, statusTop + 10, 10, HudFonts.SEMI, TEXT);
        text(g, fit(mc.getUser().getName(), SIDEBAR - 80, 8.5f), x + 56, statusTop + 26, 8.5f, MUTED);
    }

    private void sectionLabel(GuiGraphics g, String label, float ly) {
        text(g, label, x + 22, ly + 3, 8, HudFonts.SEMI, SUBTLE);
    }

    private void navItem(GuiGraphics g, Icon icon, String label, String badge, boolean active, boolean enabled,
                         float iy, float ih, double mx, double my, Runnable action) {
        float ix = x + 12, iw = SIDEBAR - 24, bh = ih - 3;
        boolean hover = enabled && contains(mx, my, ix, iy, iw, bh);
        if (hover) hoverTarget = "nav:" + label;
        float hv = ease("nav:" + label, active ? 1 : hover ? .55f : 0, 16);
        if (hv > .01f) round(g, ix, iy, iw, bh, 9, alpha(RAISED, hv));
        if (active) round(g, ix, iy + bh / 2 - 7, 3, 14, 1.5f, accent());
        int ic = active ? accent() : enabled ? mix(MUTED, TEXT, hv) : SUBTLE;
        icon(g, icon, ix + 12, iy + bh / 2 - 8, 16, ic);
        text(g, label, ix + 37, iy + bh / 2 - 5.5f, 10.5f, active ? HudFonts.SEMI : HudFonts.REGULAR,
                active ? TEXT : enabled ? mix(MUTED, TEXT, hv) : SUBTLE);
        if (badge != null) {
            float bw = width(badge, 8, HudFonts.SEMI) + 12;
            round(g, ix + iw - bw - 8, iy + bh / 2 - 7, bw, 14, 7, active ? alpha(accent(), .18f) : SURFACE);
            textCentered(g, badge, ix + iw - 8 - bw / 2, iy + bh / 2 - 4.5f, 8, HudFonts.SEMI, active ? accent() : MUTED);
        }
        if (enabled) hit(ix, iy, iw, bh, b -> { if (b == 0) { UiSounds.click(); action.run(); } });
    }

    // ------------------------------------------------------------ top bar

    private void drawTopBar(GuiGraphics g, double mx, double my) {
        float tx = x + SIDEBAR + 24, ty = y + 20;
        List<Module> list = view == View.CATEGORY || view == View.FAVORITES ? visibleModules() : List.of();
        String title = switch (view) {
            case CATEGORY -> query.isBlank() ? categoryName(category) : dev.guizhou.util.lang.ClientLanguage.text("搜索结果");
            case FAVORITES -> dev.guizhou.util.lang.ClientLanguage.text("收藏");
            case CONFIGS -> dev.guizhou.util.lang.ClientLanguage.text("配置");
            case BINDS -> dev.guizhou.util.lang.ClientLanguage.text("快捷键");
        };
        String sub = switch (view) {
            case CATEGORY, FAVORITES -> list.size() + dev.guizhou.util.lang.ClientLanguage.text(" 个模块 · ") + list.stream().filter(Module::isEnabled).count() + dev.guizhou.util.lang.ClientLanguage.text(" 已启用");
            case CONFIGS -> dev.guizhou.util.lang.ClientLanguage.text("保存、切换或删除整套设置");
            case BINDS -> dev.guizhou.util.lang.ClientLanguage.text("点击按键即可改绑，Delete 清除");
        };
        text(g, title, tx, ty, 18, HudFonts.SEMI, TEXT);
        text(g, sub, tx, ty + 25, 9, MUTED);

        float closeX = x + w - 46;
        iconButton(g, Icon.CLOSE, closeX, y + 20, 28, 28, mx, my, screen::onClose);
        if (back != null) {
            button(g, dev.guizhou.util.lang.ClientLanguage.text("返回面板"), Icon.GRID, closeX - 104, y + 20, 96, 28, mx, my, false, 0, this::goBack);
            closeX -= 106;
        }
        if (view == View.CONFIGS) return;
        float sw = Math.min(240, (w - SIDEBAR) * .32f), sx = closeX - 10 - sw;
        search.bounds(sx, y + 20, sw, 28);
        search.draw(g, view == View.BINDS ? dev.guizhou.util.lang.ClientLanguage.text("搜索快捷键…") : dev.guizhou.util.lang.ClientLanguage.text("搜索全部模块…"));
        icon(g, Icon.SEARCH, sx + 9, y + 27, 14, search.focused ? accent() : SUBTLE);
        hit(sx, y + 20, sw, 28, b -> { if (b == 0) search.focused = true; });
        if (view == View.BINDS) return;
        float segW = 0;
        for (Filter f : Filter.values()) segW += width(dev.guizhou.util.lang.ClientLanguage.text(f.label), 9.5f) + 22;
        float fx = sx - 10 - segW - 6;
        if (fx < tx + width(title, 18, HudFonts.SEMI) + 20) return;
        round(g, fx, y + 20, segW + 6, 28, 9, SURFACE);
        outline(g, fx, y + 20, segW + 6, 28, 9, 1, BORDER);
        float ox = fx + 3;
        for (Filter f : Filter.values()) {
            float fw = width(dev.guizhou.util.lang.ClientLanguage.text(f.label), 9.5f) + 22;
            float on = ease("filter:" + f, filter == f ? 1 : 0, 18);
            if (on > .01f) round(g, ox, y + 23, fw, 22, 7, alpha(RAISED, on));
            textCentered(g, dev.guizhou.util.lang.ClientLanguage.text(f.label), ox + fw / 2, y + 29, 9.5f, filter == f ? HudFonts.SEMI : HudFonts.REGULAR,
                    mix(MUTED, TEXT, on));
            final Filter target = f;
            hit(ox, y + 23, fw, 22, b -> { if (b == 0) { filter = target; gridTarget = 0; } });
            ox += fw;
        }
    }

    // ------------------------------------------------------------ module grid

    private float drawerProgress() {
        return ease("drawer", selected != null ? 1 : 0, 14);
    }

    private void drawGrid(GuiGraphics g, double mx, double my) {
        List<Module> list = visibleModules();
        float p = drawerProgress();
        float gx = x + SIDEBAR + 20, gy = y + TOPBAR + 14;
        float gw = w - SIDEBAR - 40 - p * (DRAWER + 12), gh = h - TOPBAR - 14 - FOOTER - 6;
        int cols = Math.max(1, (int) ((gw + GAP) / (224 + GAP)));
        float cw = (gw - GAP * (cols - 1)) / cols;
        int rows = (list.size() + cols - 1) / cols;
        gridMax = Math.max(0, rows * (CARD_H + GAP) - GAP - gh);
        gridTarget = Math.clamp(gridTarget, 0, gridMax);
        float scroll = ease("gridScroll", gridTarget, 18);
        g.enableScissor((int) gx - 3, (int) gy, (int) (gx + gw + 3), (int) (gy + gh));
        for (int i = 0; i < list.size(); i++) {
            float cx = gx + i % cols * (cw + GAP), cy = gy + (float) (i / cols) * (CARD_H + GAP) - scroll;
            if (cy + CARD_H < gy || cy > gy + gh) continue;
            drawCard(g, list.get(i), cx, cy, cw, mx, my, gy, gy + gh);
        }
        g.disableScissor();
        if (list.isEmpty()) {
            float ex = gx + gw / 2;
            icon(g, view == View.FAVORITES ? Icon.STAR : Icon.SEARCH, ex - 14, gy + gh / 2 - 34, 28, SUBTLE);
            textCentered(g, view == View.FAVORITES ? dev.guizhou.util.lang.ClientLanguage.text("还没有收藏的模块") : dev.guizhou.util.lang.ClientLanguage.text("没有匹配的模块"), ex, gy + gh / 2, 11, HudFonts.SEMI, MUTED);
            textCentered(g, view == View.FAVORITES ? dev.guizhou.util.lang.ClientLanguage.text("在模块卡片上点击星标或按鼠标中键收藏") : dev.guizhou.util.lang.ClientLanguage.text("换个关键词或筛选条件试试"),
                    ex, gy + gh / 2 + 17, 8.5f, HudFonts.REGULAR, SUBTLE);
        }
        scrollbar(g, gx + gw + 5, gy, gh, scroll, gridMax);
    }

    private void drawCard(GuiGraphics g, Module m, float cx, float cy, float cw, double mx, double my, float clipTop, float clipBottom) {
        boolean visible = my >= clipTop && my < clipBottom;
        boolean hover = visible && contains(mx, my, cx, cy, cw, CARD_H);
        if (hover) hoverTarget = m;
        boolean on = m.isEnabled(), sel = m == selected, fav = isFavorite(m);
        float hv = ease(m, hover ? 1 : 0, 18);
        float onP = ease(m.enabled, on ? 1 : 0, 16);
        if (visible || cy + CARD_H > clipTop) {
            hit(cx, Math.max(cy, clipTop), cw, Math.max(0, Math.min(cy + CARD_H, clipBottom) - Math.max(cy, clipTop)), b -> {
                if (b == 0) select(selected == m ? null : m);
                else if (b == 1) m.toggleByUser();
                else if (b == 2) toggleFavorite(m);
            });
        }
        round(g, cx, cy, cw, CARD_H, 13, mix(SURFACE, RAISED, hv));
        if (onP > .01f) roundGradient(g, cx, cy, cw, CARD_H, 13, alpha(accent(), .17f * onP), alpha(accent(), 0));
        outline(g, cx, cy, cw, CARD_H, 13, 1, sel ? accent() : mix(BORDER, 0xFF3A4254, hv));
        round(g, cx + 15, cy + 17, 6, 6, 3, mix(SUBTLE, accent(), onP));
        text(g, fit(m.getDisplayName(), cw - 96, 11.5f), cx + 28, cy + 13, 11.5f, HudFonts.SEMI, TEXT);
        text(g, fit(m.getDescription(), cw - 30, 8.5f), cx + 15, cy + 33, 8.5f, MUTED);
        float chipX = cx + 15, chipY = cy + CARD_H - 21;
        String bind = bindLabel(m.bind.getValue());
        if (bind != null) chipX += chip(g, Icon.KEYBOARD, bind, chipX, chipY) + 5;
        if (!query.isBlank() || view == View.FAVORITES) chip(g, iconFor(m.getCategory()), categoryName(m.getCategory()), chipX, chipY);
        if (fav || hv > .05f) {
            icon(g, fav ? Icon.STAR_FILL : Icon.STAR, cx + cw - 64, cy + 14, 13, fav ? WARNING : alpha(MUTED, hv));
            if (visible) hit(cx + cw - 68, cy + 10, 21, 21, b -> { if (b == 0) toggleFavorite(m); });
        }
        if (visible) toggle(g, cx + cw - 44, cy + 12, m.enabled, on, m::toggleByUser);
        else switchShape(g, cx + cw - 44, cy + 12, ease(m.enabled, on ? 1 : 0, 16));
    }

    private float chip(GuiGraphics g, Icon icon, String label, float cx, float cy) {
        float cw = width(label, 8) + (icon == null ? 12 : 26);
        round(g, cx, cy, cw, 15, 5, alpha(0xFF0B0D12, .55f));
        outline(g, cx, cy, cw, 15, 5, 1, alpha(BORDER, .8f));
        if (icon != null) icon(g, icon, cx + 5, cy + 2.5f, 10, SUBTLE);
        text(g, label, cx + (icon == null ? 6 : 19), cy + 3.5f, 8, MUTED);
        return cw;
    }

    private void switchShape(GuiGraphics g, float sx, float sy, float p) {
        round(g, sx, sy, 30, 17, 8.5f, mix(0xFF2E3444, accent(), p));
        round(g, sx + 2 + p * 13, sy + 2, 13, 13, 6.5f, mix(0xFFB8BECC, KNOB, p));
    }

    private void toggle(GuiGraphics g, float sx, float sy, Object key, boolean on, Runnable action) {
        switchShape(g, sx, sy, ease(key, on ? 1 : 0, 18));
        // Module switches already sound through Module.toggleByUser; setting switches sound here.
        boolean moduleSwitch = key instanceof Setting<?> s && s.getFeature() instanceof Module owner && s == owner.enabled;
        hit(sx - 4, sy - 4, 38, 25, b -> { if (b != 0) return; if (!moduleSwitch) UiSounds.toggle(!on); action.run(); });
    }

    private void iconButton(GuiGraphics g, Icon icon, float bx, float by, float bw, float bh, double mx, double my, Runnable action) {
        boolean hover = contains(mx, my, bx, by, bw, bh);
        float hv = ease("ib:" + icon + bx + ":" + by, hover ? 1 : 0, 18);
        round(g, bx, by, bw, bh, 8, mix(SURFACE, RAISED, hv));
        outline(g, bx, by, bw, bh, 8, 1, BORDER);
        float s = Math.min(bw, bh) * .5f;
        icon(g, icon, bx + (bw - s) / 2, by + (bh - s) / 2, s, mix(MUTED, TEXT, hv));
        hit(bx, by, bw, bh, b -> { if (b == 0) { UiSounds.click(); action.run(); } });
    }

    private void button(GuiGraphics g, String label, Icon icon, float bx, float by, float bw, float bh, double mx, double my,
                        boolean primary, int danger, Runnable action) {
        boolean hover = contains(mx, my, bx, by, bw, bh);
        float hv = ease("btn:" + label + bx + ":" + by, hover ? 1 : 0, 18);
        int fill = danger != 0 ? mix(alpha(danger, .14f), alpha(danger, .24f), hv)
                : primary ? mix(accent(), mix(accent(), 0xFFFFFFFF, .12f), hv) : mix(SURFACE, RAISED, hv);
        round(g, bx, by, bw, bh, 8, fill);
        if (!primary) outline(g, bx, by, bw, bh, 8, 1, danger != 0 ? alpha(danger, .45f) : BORDER);
        int fg = danger != 0 ? danger : primary ? 0xFF0B0C10 : TEXT;
        float tw = width(label, 9.5f, HudFonts.SEMI) + (icon == null ? 0 : 17);
        float tx = bx + (bw - tw) / 2;
        if (icon != null) {
            icon(g, icon, tx, by + (bh - 12) / 2, 12, fg);
            tx += 17;
        }
        text(g, label, tx, by + (bh - 9.5f) / 2, 9.5f, HudFonts.SEMI, fg);
        hit(bx, by, bw, bh, b -> { if (b == 0) { UiSounds.click(); action.run(); } });
    }

    // ------------------------------------------------------------ settings drawer

    private void drawDrawer(GuiGraphics g, double mx, double my) {
        float p = drawerProgress();
        if (selected != null) lastShown = selected;
        Module m = lastShown;
        if (m == null || p < .01f) return;
        float dw = DRAWER, dx = x + w - 20 - dw + (1 - p) * (dw * .35f);
        float dy = y + TOPBAR + 14, dh = h - TOPBAR - 14 - FOOTER - 6;
        g.enableScissor((int) (x + SIDEBAR + 1), (int) (y + TOPBAR - 4), (int) (x + w - 1), (int) (y + h - 1));
        shadow(g, dx, dy, dw, dh, 16, 16, alpha(0xFF000000, .7f * p));
        round(g, dx, dy, dw, dh, 16, DRAWER_BG);
        outline(g, dx, dy, dw, dh, 16, 1, BORDER);
        if (selected == null) { g.disableScissor(); return; }

        float px = dx + 20, pw = dw - 40, py = dy + 18;
        text(g, fit(m.getDisplayName(), pw - 70, 15.5f), px, py, 15.5f, HudFonts.SEMI, TEXT);
        iconButton(g, Icon.CLOSE, dx + dw - 44, py - 3, 26, 26, mx, my, () -> select(null));
        float descY = py + 24;
        List<String> desc = wrap(m.getDescription(), pw, 9, 2);
        for (String line : desc) { text(g, line, px, descY, 9, MUTED); descY += 13; }
        float ctlY = Math.max(descY + 6, py + 44);
        toggle(g, px, ctlY + 5, m.enabled, m.isEnabled(), m::toggleByUser);
        text(g, m.isEnabled() ? dev.guizhou.util.lang.ClientLanguage.text("已启用") : dev.guizhou.util.lang.ClientLanguage.text("未启用"), px + 38, ctlY + 8, 9.5f, HudFonts.SEMI, m.isEnabled() ? accent() : MUTED);
        String bind = bindLabel(m.bind.getValue());
        float bindW = 104;
        button(g, bind == null ? dev.guizhou.util.lang.ClientLanguage.text("绑定按键") : bind, Icon.KEYBOARD, dx + dw - 20 - bindW - 34, ctlY, bindW, 26, mx, my, false, 0,
                () -> { closePopovers(); binding = m.bind; search.focused = false; });
        iconButton(g, isFavorite(m) ? Icon.STAR_FILL : Icon.STAR, dx + dw - 46, ctlY, 26, 26, mx, my, () -> toggleFavorite(m));

        List<Setting<?>> settings = m.getSettings().stream()
                .filter(s -> s != m.enabled && s != m.bind && s.isVisible()).toList();
        List<String> pages = settings.stream().map(Setting::getPage).distinct().toList();
        if (!pages.contains(page)) page = pages.isEmpty() ? "General" : pages.getFirst();
        float tabsY = ctlY + 38;
        if (pages.size() > 1) {
            float tl = px, tr = px + pw;
            float tabScroll = ease("tabScroll", tabTarget, 18), tx = tl - tabScroll;
            g.enableScissor((int) tl, (int) tabsY, (int) tr, (int) tabsY + 26);
            for (String pg : pages) {
                float tw = width(pageName(pg), 9, HudFonts.SEMI) + 20;
                boolean act = pg.equals(page);
                float on = ease("tab:" + pg, act ? 1 : 0, 18);
                round(g, tx, tabsY, tw, 24, 7, mix(SURFACE, alpha(accent(), .18f), on));
                if (!act) outline(g, tx, tabsY, tw, 24, 7, 1, BORDER);
                textCentered(g, pageName(pg), tx + tw / 2, tabsY + 7.5f, 9, HudFonts.SEMI, act ? accent() : MUTED);
                float left = Math.max(tx, tl), right = Math.min(tx + tw, tr);
                if (right > left) hit(left, tabsY, right - left, 24, b -> {
                    if (b == 0) { page = pg; drawerTarget = 0; closePopovers(); }
                });
                tx += tw + 6;
            }
            tabMax = Math.max(0, tx + tabScroll - 6 - tr);
            tabTarget = Math.clamp(tabTarget, 0, tabMax);
            g.disableScissor();
            tabsY += 34;
        }
        float top = tabsY, bottom = dy + dh - 12;
        List<Setting<?>> rows = settings.stream().filter(s -> s.getPage().equals(page)).toList();
        float content = 0;
        for (Setting<?> s : rows) content += rowHeight(s);
        drawerMax = Math.max(0, content - (bottom - top));
        drawerTarget = Math.clamp(drawerTarget, 0, drawerMax);
        float scroll = ease("drawerScroll:" + m.getName() + page, drawerTarget, 18);
        g.enableScissor((int) dx + 2, (int) top, (int) (dx + dw - 2), (int) bottom);
        float ry = top - scroll;
        for (Setting<?> s : rows) {
            float rh = rowHeight(s);
            if (ry + rh > top && ry < bottom) drawSetting(g, m, s, px, ry, pw, rh, mx, my, top, bottom);
            ry += rh;
        }
        if (rows.isEmpty()) textCentered(g, dev.guizhou.util.lang.ClientLanguage.text("这个模块没有可调整的设置"), dx + dw / 2, top + 24, 9.5f, HudFonts.REGULAR, SUBTLE);
        g.disableScissor();
        scrollbar(g, dx + dw - 8, top, bottom - top, scroll, drawerMax);
        g.disableScissor();
    }

    private float rowHeight(Setting<?> s) {
        if (s.isNumberSetting() && s.hasRestriction()) return 56;
        return s.getDescription().isBlank() ? 40 : 48;
    }

    private void drawSetting(GuiGraphics g, Module owner, Setting<?> s, float sx, float sy, float sw, float sh,
                             double mx, double my, float top, float bottom) {
        Object v = s.getValue();
        boolean visible = my >= top && my < bottom;
        boolean hover = visible && contains(mx, my, sx - 8, sy, sw + 16, sh);
        boolean fully = sy >= top - 1 && sy + sh <= bottom + 1;
        if (hover) { hint = s.hoverHint(); hoverTarget = s; }
        float hv = ease(s, hover ? 1 : 0, 18);
        if (hv > .01f) round(g, sx - 8, sy + 2, sw + 16, sh - 4, 8, alpha(SURFACE, hv * .7f));
        if (fully) hit(sx - 8, sy, sw * .6f, sh, b -> {
            if (b == 1) s.reset();
            else if (b == 0 && v instanceof Boolean on) assign(s, !on);
        });
        String label = settingLabel(owner, s);
        boolean slider = v instanceof Number && s.hasRestriction();
        float labelY = slider ? sy + 9 : sy + (s.getDescription().isBlank() ? sh / 2 - 5.5f : 10);
        text(g, fit(label, sw * .56f, 10), sx, labelY, 10, TEXT);
        if (!s.getDescription().isBlank()) text(g, fit(s.getDescription(), sw * .56f, 8), sx, labelY + 14, 8, SUBTLE);
        g.fill((int) sx, (int) (sy + sh - 1), (int) (sx + sw), (int) (sy + sh), ROW_LINE);

        if (v instanceof Boolean on) {
            float ty = sy + (sh - 17) / 2;
            if (fully) toggle(g, sx + sw - 30, ty, s, on, () -> assign(s, !on));
            else switchShape(g, sx + sw - 30, ty, on ? 1 : 0);
            return;
        }
        if (slider) {
            Number n = (Number) v;
            float a = ((Number) s.getMin()).floatValue(), b = ((Number) s.getMax()).floatValue();
            float ratio = b <= a ? 0 : Math.clamp((n.floatValue() - a) / (b - a), 0, 1);
            float shown = ease("slider:" + System.identityHashCode(s), ratio, 22);
            String value = number(n);
            float vw = width(value, 9, HudFonts.SEMI) + 16;
            round(g, sx + sw - vw, sy + 6, vw, 20, 6, SURFACE);
            outline(g, sx + sw - vw, sy + 6, vw, 20, 6, 1, BORDER);
            textCentered(g, value, sx + sw - vw / 2, sy + 11.5f, 9, HudFonts.SEMI, TEXT);
            float ty = sy + 38;
            round(g, sx, ty, sw, 5, 2.5f, 0xFF262B38);
            roundGradient(g, sx, ty, Math.max(5, sw * shown), 5, 2.5f, accent(), mix(accent(), ACCENT_2, .6f));
            boolean hot = dragging == s || hover && my > sy + 30;
            float kr = hot ? 7 : 6;
            round(g, sx + sw * shown - kr, ty + 2.5f - kr, kr * 2, kr * 2, kr, KNOB);
            outline(g, sx + sw * shown - kr, ty + 2.5f - kr, kr * 2, kr * 2, kr, 1.5f, accent());
            if (fully) {
                hit(sx - 6, sy + 30, sw + 12, 20, button -> {
                    if (button == 0) { dragging = s; dragX = sx; dragW = sw; setSlider(mx); }
                    else if (button == 1) s.reset();
                });
                hit(sx + sw - vw, sy + 6, vw, 20, button -> {
                    if (button == 0) beginEdit(s, sx + sw - 160, sy + 30, 160);
                    else if (button == 1) s.reset();
                });
            }
            return;
        }
        float cw = Math.min(150, sw * .42f), cx = sx + sw - cw, cy = sy + (sh - 26) / 2;
        boolean ctrlHover = visible && contains(mx, my, cx, cy, cw, 26);
        float ch = ease("ctl:" + System.identityHashCode(s), ctrlHover ? 1 : 0, 18);
        round(g, cx, cy, cw, 26, 8, mix(SURFACE, RAISED, ch));
        outline(g, cx, cy, cw, 26, 8, 1, (dropdown == s || picker.target() == s || editing == s || binding == s) ? accent() : BORDER);
        if (v instanceof Color c) {
            ColorPicker.checker(g, cx + 6, cy + 5, 16, 16);
            round(g, cx + 6, cy + 5, 16, 16, 5, c.getRGB());
            outline(g, cx + 6, cy + 5, 16, 16, 5, 1, alpha(0xFFFFFFFF, .2f));
            text(g, hex(c), cx + 29, cy + 8.5f, 9, HudFonts.SEMI, TEXT);
        } else if (v instanceof Enum<?> e) {
            text(g, fit(labelOf(e), cw - 34, 9.5f), cx + 10, cy + 8.5f, 9.5f, TEXT);
            icon(g, Icon.CHEVRON, cx + cw - 20, cy + 7, 12, MUTED);
        } else if (v instanceof Bind bind) {
            icon(g, Icon.KEYBOARD, cx + 9, cy + 7, 12, MUTED);
            String label2 = binding == s ? dev.guizhou.util.lang.ClientLanguage.text("按下按键…") : bindLabel(bind) == null ? dev.guizhou.util.lang.ClientLanguage.text("未绑定") : bindLabel(bind);
            text(g, fit(label2, cw - 36, 9.5f), cx + 27, cy + 8.5f, 9.5f, binding == s ? accent() : TEXT);
        } else {
            String value = v instanceof Vector2f vec ? number(vec.x) + ", " + number(vec.y) : v instanceof Number n ? number(n) : String.valueOf(v);
            text(g, fit(value.isEmpty() ? dev.guizhou.util.lang.ClientLanguage.text("（空）") : value, cw - 20, 9.5f), cx + 10, cy + 8.5f, 9.5f, value.isEmpty() ? SUBTLE : TEXT);
        }
        if (fully) hit(cx, cy, cw, 26, b -> {
            if (b == 1) { s.reset(); return; }
            if (b != 0) return;
            closePopovers();
            if (v instanceof Bind) { binding = s; search.focused = false; }
            else if (v instanceof Enum<?> e) {
                dropdown = s;
                popX = cx; popY = cy + 30; popW = Math.max(160, cw);
                dropIndex = e.ordinal();
                dropScroll = Math.max(0, dropIndex - 4);
            } else if (v instanceof Color c) beginPick(s, c, cx + cw - 250, cy + 32);
            else beginEdit(s, cx + cw - 250, cy + 32, 250);
        });
    }

    // ------------------------------------------------------------ configs & binds

    private void drawConfigs(GuiGraphics g, double mx, double my) {
        float gx = x + SIDEBAR + 20, gy = y + TOPBAR + 16, gw = w - SIDEBAR - 40;
        round(g, gx, gy, gw, 66, 14, SURFACE);
        outline(g, gx, gy, gw, 66, 14, 1, BORDER);
        text(g, dev.guizhou.util.lang.ClientLanguage.text("保存当前设置为新配置"), gx + 16, gy + 12, 10.5f, HudFonts.SEMI, TEXT);
        text(g, validation.isEmpty() ? dev.guizhou.util.lang.ClientLanguage.text("名称可用中文、字母、数字、下划线和短横线") : validation, gx + 16, gy + 28, 8.5f,
                validation.isEmpty() ? SUBTLE : DANGER);
        float fieldW = Math.min(260, gw * .4f);
        configName.bounds(gx + gw - fieldW - 124, gy + 19, fieldW, 28);
        configName.draw(g, dev.guizhou.util.lang.ClientLanguage.text("配置名称"));
        hit(configName.x, configName.y, configName.w, configName.h, b -> { if (b == 0) configName.focused = true; });
        button(g, dev.guizhou.util.lang.ClientLanguage.text("保存"), Icon.SAVE, gx + gw - 110, gy + 19, 94, 28, mx, my, true, 0, this::saveNewConfig);

        List<String> names = GuizhouWare.configManager == null ? List.of() : GuizhouWare.configManager.listNamed();
        float ly = gy + 82, lh = y + h - FOOTER - 6 - ly;
        text(g, dev.guizhou.util.lang.ClientLanguage.text("已保存的配置 · ") + names.size(), gx + 2, ly, 9, HudFonts.SEMI, SUBTLE);
        button(g, dev.guizhou.util.lang.ClientLanguage.text("云参数"), Icon.EXTERNAL, gx + gw - 108, ly - 4, 108, 20, mx, my, false, 0, screen::openCloudConfigs);
        ly += 18;
        lh -= 18;
        float rowH = 54;
        listMax = Math.max(0, names.size() * (rowH + 8) - 8 - lh);
        listTarget = Math.clamp(listTarget, 0, listMax);
        float scroll = ease("listScroll:configs", listTarget, 18);
        g.enableScissor((int) gx - 2, (int) ly, (int) (gx + gw + 2), (int) (ly + lh));
        float ry = ly - scroll;
        for (String name : names) {
            if (ry + rowH > ly && ry < ly + lh) {
                boolean hover = contains(mx, my, gx, ry, gw, rowH) && my >= ly && my < ly + lh;
                float hv = ease("cfg:" + name, hover ? 1 : 0, 18);
                round(g, gx, ry, gw, rowH, 12, mix(SURFACE, RAISED, hv));
                outline(g, gx, ry, gw, rowH, 12, 1, BORDER);
                round(g, gx + 14, ry + 13, 28, 28, 8, alpha(accent(), .14f));
                icon(g, Icon.FOLDER, gx + 20, ry + 19, 16, accent());
                text(g, fit(name, gw - 360, 11), gx + 54, ry + 13, 11, HudFonts.SEMI, TEXT);
                text(g, dev.guizhou.util.lang.ClientLanguage.text("包含全部模块、HUD 和快捷键"), gx + 54, ry + 30, 8.5f, SUBTLE);
                if (my >= ly && my < ly + lh) {
                    boolean armed = name.equals(pendingDelete) && System.currentTimeMillis() - pendingDeleteAt < 3000;
                    float bx = gx + gw - 14;
                    bx -= 92;
                    button(g, armed ? dev.guizhou.util.lang.ClientLanguage.text("确认删除") : dev.guizhou.util.lang.ClientLanguage.text("删除"), Icon.TRASH, bx, ry + 13, 92, 28, mx, my, false, DANGER, () -> deleteConfig(name));
                    bx -= 100;
                    button(g, dev.guizhou.util.lang.ClientLanguage.text("覆盖"), Icon.SAVE, bx, ry + 13, 92, 28, mx, my, false, 0, () -> overwriteConfig(name));
                    bx -= 100;
                    button(g, dev.guizhou.util.lang.ClientLanguage.text("加载"), Icon.PLAY, bx, ry + 13, 92, 28, mx, my, true, 0, () -> loadConfig(name));
                }
            }
            ry += rowH + 8;
        }
        g.disableScissor();
        if (names.isEmpty()) {
            icon(g, Icon.FOLDER, gx + gw / 2 - 14, ly + lh / 2 - 36, 28, SUBTLE);
            textCentered(g, dev.guizhou.util.lang.ClientLanguage.text("还没有保存的配置"), gx + gw / 2, ly + lh / 2, 11, HudFonts.SEMI, MUTED);
            textCentered(g, dev.guizhou.util.lang.ClientLanguage.text("在上方输入名称并保存，就能随时切换整套设置"), gx + gw / 2, ly + lh / 2 + 17, 8.5f, HudFonts.REGULAR, SUBTLE);
        }
        scrollbar(g, gx + gw + 5, ly, lh, scroll, listMax);
    }

    private void drawBinds(GuiGraphics g, double mx, double my) {
        float gx = x + SIDEBAR + 20, gy = y + TOPBAR + 16, gw = w - SIDEBAR - 40, gh = h - TOPBAR - 16 - FOOTER - 6;
        String q = query.toLowerCase(Locale.ROOT).trim();
        List<Module> bound = allModules().stream()
                .filter(m -> !m.bind.getValue().isEmpty())
                .filter(m -> q.isEmpty() || (m.getName() + " " + m.getDisplayName()).toLowerCase(Locale.ROOT).contains(q))
                .sorted(Comparator.comparing(Module::getDisplayName)).toList();
        float rowH = 44;
        listMax = Math.max(0, bound.size() * (rowH + 6) - 6 - gh);
        listTarget = Math.clamp(listTarget, 0, listMax);
        float scroll = ease("listScroll:binds", listTarget, 18);
        g.enableScissor((int) gx - 2, (int) gy, (int) (gx + gw + 2), (int) (gy + gh));
        float ry = gy - scroll;
        for (Module m : bound) {
            if (ry + rowH > gy && ry < gy + gh) {
                boolean inside = my >= gy && my < gy + gh;
                boolean hover = inside && contains(mx, my, gx, ry, gw, rowH);
                float hv = ease("bind:" + m.getName(), hover ? 1 : 0, 18);
                round(g, gx, ry, gw, rowH, 11, mix(SURFACE, RAISED, hv));
                outline(g, gx, ry, gw, rowH, 11, 1, BORDER);
                icon(g, iconFor(m.getCategory()), gx + 14, ry + 14, 16, m.isEnabled() ? accent() : MUTED);
                text(g, fit(m.getDisplayName(), gw * .45f, 10.5f), gx + 40, ry + 10, 10.5f, HudFonts.SEMI, TEXT);
                text(g, categoryName(m.getCategory()), gx + 40, ry + 25, 8, SUBTLE);
                if (inside) {
                    button(g, binding == m.bind ? dev.guizhou.util.lang.ClientLanguage.text("按下按键…") : bindLabel(m.bind.getValue()), Icon.KEYBOARD,
                            gx + gw - 176, ry + 9, 124, 26, mx, my, false, 0, () -> { closePopovers(); binding = m.bind; search.focused = false; });
                    iconButton(g, Icon.CLOSE, gx + gw - 44, ry + 9, 26, 26, mx, my, () -> assign(m.bind, Bind.none()));
                }
            }
            ry += rowH + 6;
        }
        g.disableScissor();
        if (bound.isEmpty()) {
            icon(g, Icon.KEYBOARD, gx + gw / 2 - 14, gy + gh / 2 - 36, 28, SUBTLE);
            textCentered(g, q.isEmpty() ? dev.guizhou.util.lang.ClientLanguage.text("还没有绑定快捷键") : dev.guizhou.util.lang.ClientLanguage.text("没有匹配的快捷键"), gx + gw / 2, gy + gh / 2, 11, HudFonts.SEMI, MUTED);
            textCentered(g, dev.guizhou.util.lang.ClientLanguage.text("打开模块设置，点击「绑定按键」即可添加"), gx + gw / 2, gy + gh / 2 + 17, 8.5f, HudFonts.REGULAR, SUBTLE);
        }
        scrollbar(g, gx + gw + 5, gy, gh, scroll, listMax);
    }

    private void saveNewConfig() {
        String name = configName.value().trim();
        if (!name.matches("[\\w\\-\\u4e00-\\u9fa5]{1,32}")) { validation = dev.guizhou.util.lang.ClientLanguage.text("名称无效：1–32 个中文、字母、数字、_ 或 -"); return; }
        if (GuizhouWare.configManager.listNamed().contains(name)) { validation = dev.guizhou.util.lang.ClientLanguage.text("已存在同名配置，请在列表中使用「覆盖」"); return; }
        if (GuizhouWare.configManager.saveNamed(name)) {
            showToast(dev.guizhou.util.lang.ClientLanguage.text("已保存配置「") + name + "」");
            UiSounds.success();
            configName = new ShadcnTextField("", s -> validation = "");
            validation = "";
        } else { validation = dev.guizhou.util.lang.ClientLanguage.text("保存失败，请检查磁盘权限"); UiSounds.error(); }
    }

    private void overwriteConfig(String name) {
        showToast(GuizhouWare.configManager.saveNamed(name) ? dev.guizhou.util.lang.ClientLanguage.text("已覆盖配置「") + name + "」" : dev.guizhou.util.lang.ClientLanguage.text("覆盖失败"));
    }

    private void loadConfig(String name) {
        boolean ok = GuizhouWare.configManager.loadNamed(name);
        if (ok) GuizhouWare.configManager.save();
        showToast(ok ? dev.guizhou.util.lang.ClientLanguage.text("已加载配置「") + name + "」" : dev.guizhou.util.lang.ClientLanguage.text("加载失败"));
        if (ok) UiSounds.success(); else UiSounds.error();
    }

    private void deleteConfig(String name) {
        long now = System.currentTimeMillis();
        if (!name.equals(pendingDelete) || now - pendingDeleteAt > 3000) {
            pendingDelete = name;
            pendingDeleteAt = now;
            return;
        }
        pendingDelete = null;
        showToast(GuizhouWare.configManager.deleteNamed(name) ? dev.guizhou.util.lang.ClientLanguage.text("已删除配置「") + name + "」" : dev.guizhou.util.lang.ClientLanguage.text("删除失败"));
    }

    private void showToast(String message) {
        toast = message;
        toastAt = System.currentTimeMillis();
    }

    private void drawToast(GuiGraphics g) {
        if (toast == null) return;
        long age = System.currentTimeMillis() - toastAt;
        if (age > 2600) { toast = null; return; }
        float a = Math.min(1, Math.min(age / 150f, (2600 - age) / 300f));
        float tw = width(toast, 9.5f, HudFonts.SEMI) + 44, tx = x + SIDEBAR + (w - SIDEBAR - tw) / 2, ty = y + h - FOOTER - 40 + (1 - a) * 8;
        shadow(g, tx, ty, tw, 30, 10, 10, alpha(0xFF000000, .6f * a));
        round(g, tx, ty, tw, 30, 10, alpha(RAISED, a));
        outline(g, tx, ty, tw, 30, 10, 1, alpha(BORDER, a));
        icon(g, Icon.CHECK, tx + 12, ty + 9, 12, alpha(SUCCESS, a));
        text(g, toast, tx + 30, ty + 10, 9.5f, HudFonts.SEMI, alpha(TEXT, a));
    }

    // ------------------------------------------------------------ popovers

    private void drawDropdown(GuiGraphics g, double mx, double my) {
        Object[] values = dropdown.getValue().getClass().getEnumConstants();
        int count = Math.min(8, values.length);
        dropScroll = Math.clamp(dropScroll, 0, Math.max(0, values.length - count));
        float dh = count * 28 + 8;
        float dy = Math.min(popY, y + h - dh - 12), dx = Math.min(popX, x + w - popW - 12);
        popX = dx; popY = dy;
        shadow(g, dx, dy, popW, dh, 11, 14, alpha(0xFF000000, .7f));
        round(g, dx, dy, popW, dh, 11, RAISED);
        outline(g, dx, dy, popW, dh, 11, 1, 0xFF3A4254);
        hit(dx, dy, popW, dh, b -> { });
        for (int i = 0; i < count; i++) {
            int index = i + dropScroll;
            Object value = values[index];
            float iy = dy + 4 + i * 28;
            boolean current = value == dropdown.getValue();
            boolean hover = contains(mx, my, dx + 4, iy, popW - 8, 27);
            if (hover || index == dropIndex) round(g, dx + 4, iy, popW - 8, 27, 7, hover ? alpha(accent(), .16f) : alpha(SURFACE, .9f));
            text(g, fit(labelOf(value), popW - 44, 10), dx + 14, iy + 8.5f, 10, current ? accent() : TEXT);
            if (current) icon(g, Icon.CHECK, dx + popW - 26, iy + 7.5f, 12, accent());
            hit(dx + 4, iy, popW - 8, 27, b -> { if (b == 0) { assign(dropdown, value); dropdown = null; } });
        }
    }

    private void beginEdit(Setting<?> s, float ax, float ay, float aw) {
        closePopovers();
        editing = s;
        validation = "";
        search.focused = false;
        Object v = s.getValue();
        String value = v instanceof Color c ? hex(c) : v instanceof Vector2f p ? p.x + "," + p.y : String.valueOf(v);
        editor = new ShadcnTextField(value, t -> validation = "");
        editor.focused = true;
        editor.selectAll();
        popX = ax; popY = ay; popW = Math.max(250, aw);
    }

    private void drawEditor(GuiGraphics g, double mx, double my) {
        float ew = popW, eh = 122;
        float ex = Math.clamp(popX, x + SIDEBAR + 8, x + w - ew - 12), ey = Math.min(popY, y + h - eh - 12);
        shadow(g, ex, ey, ew, eh, 12, 14, alpha(0xFF000000, .7f));
        round(g, ex, ey, ew, eh, 12, RAISED);
        outline(g, ex, ey, ew, eh, 12, 1, 0xFF3A4254);
        hit(ex, ey, ew, eh, b -> { });
        text(g, fit(editing.getDisplayName(), ew - 32, 10.5f), ex + 14, ey + 12, 10.5f, HudFonts.SEMI, TEXT);
        String help = editing.isColorSetting() ? dev.guizhou.util.lang.ClientLanguage.text("#RRGGBB 或 #RRGGBBAA")
                : editing.hasRestriction() ? dev.guizhou.util.lang.ClientLanguage.text("范围 ") + editing.getMin() + " – " + editing.getMax() : dev.guizhou.util.lang.ClientLanguage.text("Enter 应用 · Esc 取消");
        text(g, fit(validation.isEmpty() ? help : validation, ew - 28, 8.5f), ex + 14, ey + 28, 8.5f, validation.isEmpty() ? SUBTLE : DANGER);
        editor.bounds(ex + 14, ey + 44, ew - 28, 28);
        editor.draw(g, "");
        button(g, dev.guizhou.util.lang.ClientLanguage.text("取消"), null, ex + ew - 164, ey + 82, 70, 26, mx, my, false, 0, () -> { editor = null; editing = null; });
        button(g, dev.guizhou.util.lang.ClientLanguage.text("应用"), Icon.CHECK, ex + ew - 86, ey + 82, 72, 26, mx, my, true, 0, this::commitEdit);
    }

    private void commitEdit() {
        try {
            String text = editor.value().trim();
            Object old = editing.getValue(), next;
            if (old instanceof Integer) next = Integer.valueOf(text);
            else if (old instanceof Long) next = Long.valueOf(text);
            else if (old instanceof Float) { float v = Float.parseFloat(text); if (!Float.isFinite(v)) throw new IllegalArgumentException(); next = v; }
            else if (old instanceof Double) { double v = Double.parseDouble(text); if (!Double.isFinite(v)) throw new IllegalArgumentException(); next = v; }
            else if (old instanceof Color) next = parseHex(text);
            else if (old instanceof Vector2f) {
                String[] parts = text.split(",");
                if (parts.length != 2) throw new IllegalArgumentException();
                float a = Float.parseFloat(parts[0].trim()), b = Float.parseFloat(parts[1].trim());
                if (!Float.isFinite(a) || !Float.isFinite(b)) throw new IllegalArgumentException();
                next = new Vector2f(a, b);
            } else next = editor.value();
            if (next instanceof Number n && editing.hasRestriction()) {
                double min = ((Number) editing.getMin()).doubleValue(), max = ((Number) editing.getMax()).doubleValue();
                if (n.doubleValue() < min || n.doubleValue() > max) { validation = dev.guizhou.util.lang.ClientLanguage.text("超出范围 ") + number((Number) editing.getMin()) + " – " + number((Number) editing.getMax()); return; }
            }
            assign(editing, next);
            editor = null;
            editing = null;
        } catch (IllegalArgumentException exception) {
            validation = dev.guizhou.util.lang.ClientLanguage.text("格式无效，请检查输入");
            UiSounds.error();
        }
    }

    private void beginPick(Setting<?> s, Color c, float ax, float ay) {
        closePopovers();
        picker.open(s, c, ax, ay);
    }

    private void editPickedHex() {
        Setting<?> s = picker.target();
        float px = picker.x(), py = picker.y();
        beginEdit(s, px, py + 40, ColorPicker.W);
    }

    private void drawPicker(GuiGraphics g, double mx, double my) {
        picker.draw(g, x + SIDEBAR + 8, y + 8, x + w - 12, y + h - 12);
        hit(picker.x(), picker.y(), ColorPicker.W, ColorPicker.H, b -> { });
    }

    private void drawBinding(GuiGraphics g) {
        g.fill((int) x, (int) y, (int) (x + w), (int) (y + h), 0x99000000);
        float bw = 340, bh = 116, bx = x + (w - bw) / 2, by = y + (h - bh) / 2;
        shadow(g, bx, by, bw, bh, 14, 18, 0xFF000000);
        round(g, bx, by, bw, bh, 14, RAISED);
        outline(g, bx, by, bw, bh, 14, 1, 0xFF3A4254);
        round(g, bx + 18, by + 20, 34, 34, 10, alpha(accent(), .16f));
        icon(g, Icon.KEYBOARD, bx + 26, by + 28, 18, accent());
        text(g, dev.guizhou.util.lang.ClientLanguage.text("按下新的快捷键"), bx + 64, by + 22, 13, HudFonts.SEMI, TEXT);
        text(g, fit(binding.getFeature() instanceof Module m ? m.getDisplayName() : binding.getName(), bw - 90, 9), bx + 64, by + 41, 9, MUTED);
        text(g, dev.guizhou.util.lang.ClientLanguage.text("支持鼠标按键 · Delete 清除 · Esc 取消"), bx + 18, by + 80, 9, SUBTLE);
    }

    // ------------------------------------------------------------ helpers

    private void hit(float hx, float hy, float hw, float hh, IntConsumer action) {
        hits.add(new Hit(hx, hy, hw, hh, action));
    }

    private void select(Module m) {
        selected = m;
        page = "General";
        drawerTarget = tabTarget = 0;
        closePopovers();
    }

    private List<Module> allModules() {
        return frameModules == null ? List.of() : frameModules;
    }

    private List<Module> modulesIn(Module.Category c) {
        if (categoryFrame == null) categoryFrame = new EnumMap<>(Module.Category.class);
        List<Module> found = categoryFrame.get(c);
        if (found != null) return found;
        found = new ArrayList<>();
        for (Module module : frameModules) if (module.getCategory() == c) found.add(module);
        categoryFrame.put(c, found);
        return found;
    }

    private List<Module> visibleModules() {
        if (visibleFrame != null) return visibleFrame;
        String q = query.toLowerCase(Locale.ROOT).trim();
        Set<String> favs = favorites();
        visibleFrame = allModules().stream()
                .filter(m -> view == View.FAVORITES ? favs.contains(m.getName()) : !q.isEmpty() || m.getCategory() == category)
                .filter(m -> q.isEmpty() || (m.getName() + " " + m.getDisplayName() + " " + m.getDescription()).toLowerCase(Locale.ROOT).contains(q))
                .filter(m -> filter == Filter.ALL || (filter == Filter.ON) == m.isEnabled())
                .sorted(Comparator.comparing(Module::getName)).toList();
        return visibleFrame;
    }

    private Set<String> favorites() {
        if (favoriteFrame != null) return favoriteFrame;
        ClickGuiModule gui = ClickGuiModule.getInstance();
        Set<String> set = new LinkedHashSet<>();
        if (gui != null) {
            for (String part : gui.favorites.getValue().split(",")) if (!part.isBlank()) set.add(part.trim());
        }
        favoriteFrame = set;
        return set;
    }

    private boolean isFavorite(Module m) {
        return favorites().contains(m.getName());
    }

    private void toggleFavorite(Module m) {
        ClickGuiModule gui = ClickGuiModule.getInstance();
        if (gui == null) return;
        Set<String> set = favorites();
        if (!set.remove(m.getName())) set.add(m.getName());
        gui.favorites.setValue(String.join(",", set));
    }

    private String settingLabel(Module owner, Setting<?> s) {
        String label = "CoupledCruise".equals(s.getName()) ? "GZBoost" : s.getDisplayName();
        if (owner instanceof HudClientModule && label.startsWith(page + " ")) label = label.substring(page.length() + 1);
        return label;
    }

    private static String pageName(String page) {
        // Page names are English identifiers; the catalog carries their Chinese labels
        // (key: Chinese label, value: English page), so both languages resolve through text().
        return dev.guizhou.util.lang.ClientLanguage.text(page);
    }

    private static String bindLabel(Bind bind) {
        if (bind == null || bind.isEmpty()) return null;
        String s = bind.toString();
        return s == null || s.isBlank() ? null : s.toUpperCase(Locale.ROOT);
    }

    private static List<String> wrap(String text, float max, float size, int lines) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        StringBuilder line = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            if (line.length() > 0 && width(line + ch, size) > max) {
                if (out.size() == lines - 1) {
                    out.add(fit(line + text.substring(i), max, size));
                    return out;
                }
                out.add(line.toString());
                line.setLength(0);
                if (cp == ' ') { i += Character.charCount(cp); continue; }
            }
            line.append(ch);
            i += Character.charCount(cp);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    private static String hex(Color c) {
        return String.format("#%02X%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha());
    }

    private static Color parseHex(String text) {
        text = text.replaceFirst("^#", "");
        if (!text.matches("[a-fA-F0-9]{6}([a-fA-F0-9]{2})?")) throw new IllegalArgumentException();
        long rgba = Long.parseLong(text, 16);
        if (text.length() == 6) rgba = (rgba << 8) | 255;
        return new Color((int) (rgba >> 24) & 255, (int) (rgba >> 16) & 255, (int) (rgba >> 8) & 255, (int) rgba & 255);
    }

    private static String number(Number n) {
        if (n instanceof Integer || n instanceof Long) return n.toString();
        return String.format(Locale.ROOT, "%.3f", n.doubleValue()).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String number(float f) { return number((Number) f); }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void assign(Setting s, Object v) { s.setValue(v); }

    private void setSlider(double mx) {
        if (dragging == null) return;
        double min = ((Number) dragging.getMin()).doubleValue(), max = ((Number) dragging.getMax()).doubleValue();
        double value = min + (max - min) * Math.clamp((mx - dragX) / dragW, 0d, 1d);
        Object old = dragging.getValue();
        if (old instanceof Integer) assign(dragging, (int) Math.round(value));
        else if (old instanceof Long) assign(dragging, Math.round(value));
        else if (old instanceof Float) assign(dragging, (float) (Math.round(value * 1000) / 1000d));
        else assign(dragging, Math.round(value * 1000) / 1000d);
    }

    private void scrollbar(GuiGraphics g, float sx, float sy, float height, float scroll, float max) {
        if (max <= 0) return;
        float thumb = Math.max(22, height * height / (height + max));
        round(g, sx, sy + (height - thumb) * scroll / max, 3, thumb, 1.5f, 0x663A4254);
    }

    private static Icon iconFor(Module.Category c) {
        return switch (c) {
            case COMBAT -> Icon.COMBAT;
            case MOVEMENT -> Icon.MOVEMENT;
            case RENDER -> Icon.RENDER;
            case PLAYER -> Icon.PLAYER;
            case WORLD -> Icon.WORLD;
            case CLIENT -> Icon.CLIENT;
            case FUNNY -> Icon.FUN;
            case HUD -> Icon.HUD;
        };
    }

    private static String categoryName(Module.Category c) {
        return switch (c) {
            case COMBAT -> dev.guizhou.util.lang.ClientLanguage.text("战斗");
            case MOVEMENT -> dev.guizhou.util.lang.ClientLanguage.text("移动");
            case RENDER -> dev.guizhou.util.lang.ClientLanguage.text("渲染");
            case PLAYER -> dev.guizhou.util.lang.ClientLanguage.text("玩家");
            case WORLD -> dev.guizhou.util.lang.ClientLanguage.text("世界");
            case CLIENT -> dev.guizhou.util.lang.ClientLanguage.text("客户端");
            case FUNNY -> dev.guizhou.util.lang.ClientLanguage.text("趣味");
            case HUD -> "HUD";
        };
    }

    // ------------------------------------------------------------ input

    @Override
    boolean mouseClicked(MouseButtonEvent e, boolean doubled) {
        double[] m = local(e.x(), e.y());
        double mx = m[0], my = m[1];
        int b = e.button();
        if (binding != null) {
            assign(binding, Bind.fromMouseButton(b));
            binding = null;
            return true;
        }
        if (modalFrom >= 0) {
            if (picker.active() && b == 0 && picker.press(mx, my)) return true;
            if (editor != null && b == 0 && editor.click(mx, my, false)) return true;
            for (int i = hits.size() - 1; i >= modalFrom; i--) {
                if (hits.get(i).at(mx, my)) { hits.get(i).action.accept(b); return true; }
            }
            closePopovers();
            return true;
        }
        search.click(mx, my, false);
        if (view == View.CONFIGS) configName.click(mx, my, false);
        for (int i = hits.size() - 1; i >= 0; i--) {
            if (hits.get(i).at(mx, my)) { hits.get(i).action.accept(b); return true; }
        }
        return true;
    }

    @Override
    boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        double[] m = local(e.x(), e.y());
        if (picker.dragging() && e.button() == 0) { picker.drag(m[0], m[1]); return true; }
        if (dragging != null && e.button() == 0) { setSlider(m[0]); return true; }
        return false;
    }

    @Override
    boolean mouseReleased(MouseButtonEvent e) {
        dragging = null;
        picker.release();
        return true;
    }

    @Override
    boolean mouseScrolled(double sx, double sy, double horizontal, double vertical) {
        double[] m = local(sx, sy);
        double mx = m[0], my = m[1];
        if (editor != null || binding != null || picker.active()) return true;
        if (dropdown != null) { dropScroll -= (int) Math.signum(vertical); return true; }
        float step = (float) (vertical * 42);
        if (view == View.CONFIGS || view == View.BINDS) { listTarget -= step; return true; }
        float p = drawerProgress();
        float drawerX = x + w - 20 - DRAWER;
        if (selected != null && p > .5f && mx >= drawerX) {
            if (my < y + TOPBAR + 150 && tabMax > 0) tabTarget -= (float) ((vertical + horizontal) * 36);
            else drawerTarget -= step;
        } else gridTarget -= step;
        return true;
    }

    @Override
    boolean keyPressed(KeyEvent e) {
        int k = e.input();
        if (binding != null) {
            if (k == GLFW_KEY_UNKNOWN) return true;
            if (k != GLFW_KEY_ESCAPE) assign(binding, k == GLFW_KEY_DELETE || k == GLFW_KEY_BACKSPACE ? Bind.none() : new Bind(k));
            binding = null;
            return true;
        }
        if (editor != null) {
            if (k == GLFW_KEY_ESCAPE) { editor = null; editing = null; }
            else if (k == GLFW_KEY_ENTER || k == GLFW_KEY_KP_ENTER) commitEdit();
            else editor.key(e);
            return true;
        }
        if (dropdown != null) {
            Object[] values = dropdown.getValue().getClass().getEnumConstants();
            if (k == GLFW_KEY_ESCAPE) dropdown = null;
            else if (k == GLFW_KEY_ENTER || k == GLFW_KEY_KP_ENTER) { assign(dropdown, values[dropIndex]); dropdown = null; }
            else if (k == GLFW_KEY_DOWN || k == GLFW_KEY_UP) {
                dropIndex = Math.clamp(dropIndex + (k == GLFW_KEY_DOWN ? 1 : -1), 0, values.length - 1);
                dropScroll = Math.max(dropIndex - 7, Math.min(dropScroll, dropIndex));
            }
            return true;
        }
        if (picker.active()) {
            if (k == GLFW_KEY_ESCAPE || k == GLFW_KEY_ENTER || k == GLFW_KEY_KP_ENTER) picker.close();
            return true;
        }
        if ((e.modifiers() & GLFW_MOD_CONTROL) != 0 && k == GLFW_KEY_F && view != View.CONFIGS) {
            search.focused = true;
            search.selectAll();
            return true;
        }
        if (configName.focused) {
            if (k == GLFW_KEY_ESCAPE) configName.focused = false;
            else if (k == GLFW_KEY_ENTER || k == GLFW_KEY_KP_ENTER) saveNewConfig();
            else configName.key(e);
            return true;
        }
        if (search.focused) {
            if (k == GLFW_KEY_ESCAPE || k == GLFW_KEY_ENTER || k == GLFW_KEY_KP_ENTER) search.focused = false;
            else search.key(e);
            return true;
        }
        if (k == GLFW_KEY_ESCAPE) {
            if (back != null) goBack();
            else if (selected != null) select(null);
            else screen.onClose();
            return true;
        }
        ClickGuiModule gui = ClickGuiModule.getInstance();
        if (gui != null && k == gui.bind.getValue().getKey() && System.currentTimeMillis() - opened > 100) {
            screen.onClose();
            return true;
        }
        return true;
    }

    @Override
    boolean charTyped(CharacterEvent e) {
        if (editor != null) return editor.character(e);
        if (configName.focused) return configName.character(e);
        return search.character(e);
    }

    private void save() {
        if (GuizhouWare.configManager != null) GuizhouWare.configManager.save();
    }

    private void goBack() {
        Runnable target = back;
        back = null;
        closePopovers();
        if (target != null) target.run();
    }

    @Override
    void removed() {
        dragging = binding = dropdown = null;
        editor = null;
        picker.close();
    }
}
