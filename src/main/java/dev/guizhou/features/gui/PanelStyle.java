package dev.guizhou.features.gui;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.client.ClickGuiModule;
import dev.guizhou.features.modules.client.HudClientModule;
import dev.guizhou.features.settings.Bind;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.render.Anim;
import dev.guizhou.util.render.RenderUtil;
import dev.guizhou.util.render.UiSounds;
import dev.guizhou.util.render.font.HudFonts;
import net.minecraft.client.gui.GuiGraphics;
import dev.guizhou.util.compat.input.CharacterEvent;
import dev.guizhou.util.compat.input.KeyEvent;
import dev.guizhou.util.compat.input.MouseButtonEvent;
import org.joml.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

import static dev.guizhou.util.render.ShadcnDraw.*;
import static org.lwjgl.glfw.GLFW.*;

/**
 * Classic multi-column ClickGUI: one draggable panel per category, modules as
 * compact rows, settings expanded inline under a module. Two presets share the
 * implementation: a purple filled look and a dense blue framed look.
 */
final class PanelStyle extends ShellStyle {
    enum Preset { HACHIMI }

    /** Visual parameters of one preset; accent may be overridden by the ClickGui theme. */
    private record Look(float width, float header, float row, float text, int accent, int body, int body2, int rowOff,
                        int rowHover, boolean fillEnabled, boolean barEnabled, int onText, int offText, boolean border,
                        boolean descriptionPanel, boolean icons, boolean glassHeader, float radius, int dim) {}

    /** Hachimi-style glass: translucent panels, tinted header strip, category icons, enabled left bar. */
    private static final Look HACHIMI_LOOK = new Look(110, 18, 16, 9.5f, 0xFFE08A6C, 0xC01F1E1C, 0xA030302E, 0x00000000,
            0x14FFFFFF, false, true, 0xFFFAF9F5, 0xFFC2C0B6, true, true, true, true, 5f, 0x66000000);

    private static final String DESCRIPTION = "DESCRIPTION";

    private final class Panel {
        final Module.Category category;
        final String key;
        float x, y, scroll, scrollTarget, bodyHeight;
        boolean collapsed;

        Panel(Module.Category category, String key) {
            this.category = category;
            this.key = key;
        }
    }

    private record Hit(float x, float y, float w, float h, IntConsumer action) {
        boolean at(double mx, double my) { return contains(mx, my, x, y, w, h); }
    }

    private final Preset preset;
    private final Look look;
    private final Anim anim = new Anim();
    private final List<Panel> panels = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private final Set<Module> expanded = new HashSet<>();
    private final ColorPicker picker = new ColorPicker(this::editPickedHex);
    private int modalFrom = -1;

    private Panel grabbed;
    private float grabDX, grabDY, pressX, pressY;
    private boolean moved;
    private Setting<?> binding, sliding, editing;
    private float slideX, slideW;
    private ShadcnTextField editor, search;
    private float editX, editY, editW, editH;
    private String query = "", hint = "";
    private Module hovered, tooltipModule;
    private Object hoverTarget;
    private long hoverSince;
    private float scale = 1, vw, vh;
    private long opened;
    private int clickModifiers;

    PanelStyle(GuizhouWareGui screen, Preset preset) {
        super(screen);
        this.preset = preset;
        this.look = HACHIMI_LOOK;
    }

    // ------------------------------------------------------------ lifecycle & layout

    @Override
    void init() {
        opened = System.currentTimeMillis();
        anim.clear();
        closePopovers();
        search = new ShadcnTextField(query, s -> query = s);
        search.padLeft = 12;
        rebuildPanels();
    }

    @Override
    void reload() {
        expanded.clear();
        rebuildPanels();
    }

    @Override
    float scale() { return scale; }

    @Override
    boolean capturesMovementKeys() {
        return editor != null || binding != null || picker.active() || search != null && search.focused;
    }

    private Module.Category forcedCategory;

    /** Reveals a normally hidden category (HUD) and pre-expands one module, e.g. HUD appearance. */
    void focusModule(Module module) {
        forcedCategory = module.getCategory();
        query = "";
        search = new ShadcnTextField("", s2 -> query = s2);
        expanded.clear();
        expanded.add(module);
        closePopovers();
        rebuildPanels();
    }

    private List<Module.Category> categories() {
        List<Module.Category> out = new ArrayList<>(GuizhouWare.moduleManager.getCategories().stream()
                .filter(c -> c != Module.Category.HUD)
                .filter(c -> c != Module.Category.FUNNY || GuizhouWare.commandManager.isFunnyVisible()).toList());
        if (forcedCategory != null && !out.contains(forcedCategory)) out.add(forcedCategory);
        return out;
    }

    private void rebuildPanels() {
        moduleCache.clear();
        moduleQuery = "\0";
        panels.clear();
        for (Module.Category c : categories()) panels.add(new Panel(c, c.name()));
        if (look.descriptionPanel) panels.add(new Panel(null, DESCRIPTION));
        computeScale();
        float x = 8;
        for (Panel p : panels) {
            p.x = x;
            p.y = 26;
            x += look.width + 4;
        }
        loadLayout();
    }

    private void computeScale() {
        ClickGuiModule gui = ClickGuiModule.getInstance();
        double requested = gui == null ? 1 : gui.menuScale.getValue() / 100;
        requested = Double.isFinite(requested) ? Math.clamp(requested, .6, 1.5) : 1;
        float needed = 16 + panels.size() * (look.width + 4);
        float fit = Math.min(1, width / Math.max(1f, needed));
        scale = (float) Math.max(.35, requested * fit);
        vw = width / scale;
        vh = height / scale;
    }

    private void loadLayout() {
        ClickGuiModule gui = ClickGuiModule.getInstance();
        if (gui == null) return;
        Map<String, String> map = layoutMap(gui.panelLayout.getValue());
        for (Panel p : panels) {
            String v = map.get(preset.name() + "." + p.key);
            if (v == null) continue;
            String[] parts = v.split(",");
            try {
                p.x = Float.parseFloat(parts[0]);
                p.y = Float.parseFloat(parts[1]);
                p.collapsed = parts.length > 2 && parts[2].equals("1");
            } catch (RuntimeException ignored) {
                // Malformed entries fall back to the default column.
            }
        }
    }

    private void saveLayout() {
        ClickGuiModule gui = ClickGuiModule.getInstance();
        if (gui == null) return;
        Map<String, String> map = layoutMap(gui.panelLayout.getValue());
        for (Panel p : panels)
            map.put(preset.name() + "." + p.key, String.format(Locale.ROOT, "%.1f,%.1f,%d", p.x, p.y, p.collapsed ? 1 : 0));
        StringBuilder out = new StringBuilder();
        for (var e : map.entrySet()) {
            if (out.length() > 0) out.append(';');
            out.append(e.getKey()).append('=').append(e.getValue());
        }
        gui.panelLayout.setValue(out.toString());
    }

    private static Map<String, String> layoutMap(String value) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String entry : value.split(";")) {
            int eq = entry.indexOf('=');
            if (eq > 0) map.put(entry.substring(0, eq), entry.substring(eq + 1));
        }
        return map;
    }

    private void closePopovers() {
        binding = sliding = editing = null;
        editor = null;
        picker.close();
    }

    // ------------------------------------------------------------ colours

    private int accent(Module.Category c) {
        ClickGuiModule gui = ClickGuiModule.getInstance();
        if (gui == null) return look.accent;
        return switch (gui.theme.getValue()) {
            case CUSTOM -> gui.customColor.getValue().getRGB() | 0xFF000000;
            case RAINBOW -> gui.rainbowAt(0).getRGB() | 0xFF000000;
            case COLORS -> c == null ? look.accent : gui.categoryAccent(c).getRGB() | 0xFF000000;
            default -> look.accent;
        };
    }

    private int onText(Module.Category c) {
        if (look.fillEnabled) return look.onText;
        ClickGuiModule gui = ClickGuiModule.getInstance();
        boolean themed = gui != null && gui.theme.getValue() != ClickGuiModule.Theme.GUIZHOUWARE;
        return themed ? accent(c) : look.onText;
    }

    // ------------------------------------------------------------ frame

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        computeScale();
        settingsCache.clear();
        hits.clear();
        modalFrom = -1;
        hovered = null;
        hoverTarget = null;
        hint = "";
        boolean world = mc.level != null;
        // Dim the world in place. Vanilla renderBlurredBackground copies the
        // whole framebuffer and blurs it every frame; that is the open-GUI stall.
        if (world) g.fill(0, 0, width, height, look.dim);
        else RenderUtil.gradient(g, 0, 0, width, height, 0xFF0B0B10, 0xFF07070A, 0xFF07070A, 0xFF0B0B10);

        double mx = mouseX / scale, my = mouseY / scale;
        var pose = g.pose();
        pose.pushPose();
        pose.scale(scale, scale, 1);
        float open = anim.to("open", 1, 12);
        pose.translate(0, (1 - open) * -6, 0);
        my += (1 - open) * 6;

        drawToolbar(g, mx, my);
        for (Panel p : panels) drawPanel(g, p, mx, my);
        if (!look.descriptionPanel) drawTooltip(g, mx, my);
        if (!hint.isEmpty()) text(g, fit(hint, vw - 20, 7.5f), 8, vh - 12, 7.5f, SUBTLE);
        else text(g, "左键开关 · 右键展开设置 · 拖动标题移动 · 单击标题折叠 · 直接输入即可搜索", 8, vh - 12, 7.5f, SUBTLE);

        modalFrom = hits.size();
        if (picker.active()) {
            picker.draw(g, 4, 4, vw - 4, vh - 4);
            hit(picker.x(), picker.y(), ColorPicker.W, ColorPicker.H, b -> { });
        } else modalFrom = -1;
        pose.popPose();
        UiSounds.hover(hoverTarget);
    }

    private void drawToolbar(GuiGraphics g, double mx, double my) {
        float sw = 160, sh = 15, sx = (vw - sw) / 2, sy = 5;
        search.bounds(sx, sy, sw, sh);
        drawSearchField(g, sx, sy, sw, sh);
        hit(sx, sy, sw, sh, b -> { if (b == 0) search.focused = true; });

        float bx = vw - 8 - 16;
        toolButton(g, Icon.CLOSE, bx, sy, "关闭", mx, my, screen::onClose);
        bx -= 19;
        boolean inWorld = mc.level != null;
        toolButton(g, Icon.HUD, bx, sy, inWorld ? "编辑 HUD" : "进入世界后可编辑 HUD", mx, my, () -> {
            if (!inWorld) return;
            HudClientModule hud = GuizhouWare.moduleManager.getModuleByClass(HudClientModule.class);
            if (hud != null) { save(); hud.openEditor(screen); }
        });
        bx -= 19;
        toolButton(g, Icon.EXTERNAL, bx, sy, "云参数", mx, my, screen::openCloudConfigs);
    }

    private void drawSearchField(GuiGraphics g, float sx, float sy, float sw, float sh) {
        boolean focused = search.focused;
        round(g, sx, sy, sw, sh, look.radius + 1, focused ? 0xE0121118 : 0x900C0C12);
        outline(g, sx, sy, sw, sh, look.radius + 1, 1, focused ? accent(null) : 0x50FFFFFF);
        icon(g, Icon.SEARCH, sx + 4, sy + 3.5f, 8, focused ? accent(null) : 0x90FFFFFF);
        String value = search.value();
        if (value.isEmpty() && !focused) {
            text(g, "搜索模块… (Ctrl+F)", sx + 15, sy + 3.5f, 8, 0x80FFFFFF);
            return;
        }
        text(g, fit(value, sw - 22, 8), sx + 15, sy + 3.5f, 8, 0xFFFFFFFF);
        if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
            float cx = sx + 15 + Math.min(width(value, 8), sw - 22);
            g.fill((int) cx, (int) sy + 3, (int) cx + 1, (int) (sy + sh - 3), accent(null));
        }
    }

    private void toolButton(GuiGraphics g, Icon icon, float bx, float by, String tip, double mx, double my, Runnable action) {
        boolean hover = contains(mx, my, bx, by, 15, 15);
        if (hover) { hint = tip; hoverTarget = icon; }
        round(g, bx, by, 15, 15, look.radius + 1, hover ? 0xE0222030 : 0x900C0C12);
        outline(g, bx, by, 15, 15, look.radius + 1, 1, hover ? accent(null) : 0x50FFFFFF);
        icon(g, icon, bx + 3.5f, by + 3.5f, 8, hover ? 0xFFFFFFFF : 0xB0FFFFFF);
        hit(bx, by, 15, 15, b -> { if (b == 0) { UiSounds.click(); action.run(); } });
    }

    // ------------------------------------------------------------ panels

    private String moduleQuery = "\0";
    private final Map<Module.Category, List<Module>> moduleCache = new EnumMap<>(Module.Category.class);
    private final Map<Module, List<Setting<?>>> settingsCache = new IdentityHashMap<>();

    private List<Module> modules(Module.Category c) {
        String q = query.toLowerCase(Locale.ROOT).trim();
        if (!q.equals(moduleQuery)) {
            moduleQuery = q;
            moduleCache.clear();
        }
        List<Module> cached = moduleCache.get(c);
        if (cached != null) return cached;
        List<Module> built = GuizhouWare.moduleManager.stream().filter(m -> !m.hidden && m.getCategory() == c)
                .filter(m -> q.isEmpty() || (m.getName() + " " + m.getDisplayName()).toLowerCase(Locale.ROOT).contains(q))
                .sorted(Comparator.comparing(Module::getDisplayName, String.CASE_INSENSITIVE_ORDER)).toList();
        moduleCache.put(c, built);
        return built;
    }

    private List<Setting<?>> settingsOf(Module m) {
        List<Setting<?>> cached = settingsCache.get(m);
        if (cached != null) return cached;
        List<Setting<?>> built = m.getSettings().stream().filter(s -> s != m.enabled && s.isVisible()).toList();
        settingsCache.put(m, built);
        return built;
    }

    private float expandedHeight(Module m) {
        return settingsOf(m).size() * look.row + 2;
    }

    private void drawPanel(GuiGraphics g, Panel p, double mx, double my) {
        boolean description = p.category == null;
        List<Module> mods = description ? List.of() : modules(p.category);
        if (!description && !query.isBlank() && mods.isEmpty()) return;
        float x = p.x, y = p.y, W = look.width, H = look.header, r = look.radius;
        int accent = accent(p.category);

        float content;
        if (description) content = descriptionHeight();
        else {
            content = 0;
            for (Module m : mods) content += look.row + anim.to("exp:" + m.getName(), expanded.contains(m) ? expandedHeight(m) : 0, 16);
        }
        float open = anim.to("panel:" + p.key, p.collapsed ? 0 : 1, 14);
        float maxBody = Math.max(look.row * 3, vh - (y + H) - 16);
        float body = Math.min(content, maxBody) * open;
        p.bodyHeight = body;
        float maxScroll = Math.max(0, content - maxBody);
        p.scrollTarget = Math.clamp(p.scrollTarget, 0, maxScroll);
        p.scroll = anim.to("scroll:" + p.key, p.scrollTarget, 18);

        int headerColor = look.glassHeader ? mix(accent, look.body, .70f) : accent;
        if (body > .5f) {
            round(g, x, y, W, H + body + (look.border ? 0 : 1), r, look.body);
            if (look.body2 != 0)
                g.fill((int) (x + 1), (int) (y + H + body * .45f), (int) (x + W - 1), (int) (y + H + body), look.body2);
        }
        round(g, x, y, W, H, r, headerColor);
        if (body > .5f && r > 0) g.fill((int) x, (int) (y + H - r), (int) (x + W), (int) (y + H), headerColor);
        if (body > .5f && look.glassHeader) g.fill((int) x, (int) (y + H - 1), (int) (x + W), (int) (y + H), alpha(accent, .55f));
        if (look.border) outline(g, x, y, W, H + body, r, 1, accent);

        String title = description ? "描述" : categoryName(p.category);
        float titleX = x + 5;
        if (look.icons && !description) {
            Icon ci = p.category == Module.Category.FUNNY ? Icon.FUN : Icon.valueOf(p.category.name());
            icon(g, ci, x + 4, y + (H - 8) / 2, 8, 0xFFE8ECFF);
            titleX = x + 14;
        }
        text(g, title, titleX, y + (H - look.text) / 2 + .5f, look.text + .5f, HudFonts.SEMI, 0xFFFFFFFF);
        if (!description) {
            String count = mods.stream().filter(Module::isEnabled).count() + "/" + mods.size();
            textRight(g, count, x + W - 5, y + (H - 7) / 2 + .5f, 7, HudFonts.REGULAR, 0xC0FFFFFF);
        }
        hit(x, y, W, H, b -> {
            if (b == 0) {
                grabbed = p;
                grabDX = (float) (mx - p.x);
                grabDY = (float) (my - p.y);
                pressX = (float) mx;
                pressY = (float) my;
                moved = false;
                panels.remove(p);
                panels.add(p);
            } else if (b == 1) {
                UiSounds.click();
                p.collapsed = !p.collapsed;
                saveLayout();
            }
        });
        if (body <= .5f) return;

        float top = y + H, bottom = y + H + body;
        g.enableScissor((int) x, (int) top, (int) (x + W), (int) Math.ceil(bottom));
        if (description) drawDescription(g, x, top, W);
        else {
            float ry = top - p.scroll;
            for (Module m : mods) {
                float exp = anim.to("exp:" + m.getName(), expanded.contains(m) ? expandedHeight(m) : 0, 16);
                if (ry + look.row + exp > top && ry < bottom) {
                    drawModule(g, m, x, ry, W, mx, my, top, bottom, accent);
                    if (exp > .5f) {
                        float sTop = Math.max(top, ry + look.row), sBottom = Math.min(bottom, ry + look.row + exp);
                        if (sBottom > sTop) {
                            g.enableScissor((int) x, (int) sTop, (int) (x + W), (int) Math.ceil(sBottom));
                            drawSettings(g, m, x, ry + look.row, W, mx, my, sTop, sBottom, accent);
                            g.disableScissor();
                        }
                    }
                }
                ry += look.row + exp;
            }
        }
        g.disableScissor();
        if (maxScroll > 0 && !p.collapsed) {
            float track = body - 4, thumb = Math.max(10, track * track / (track + maxScroll));
            round(g, x + W - 2.5f, top + 2 + (track - thumb) * p.scroll / maxScroll, 1.5f, thumb, .75f, 0x90FFFFFF);
        }
    }

    private void drawModule(GuiGraphics g, Module m, float x, float ry, float W, double mx, double my,
                            float top, float bottom, int accent) {
        float rh = look.row;
        boolean inside = my >= top && my < bottom;
        boolean hover = inside && contains(mx, my, x, ry, W, rh);
        if (hover) {
            hovered = m;
            hoverTarget = m;
            hint = m.getDisplayName() + (m.getDescription().isBlank() ? "" : " · " + m.getDescription());
        }
        boolean on = m.isEnabled();
        float onP = anim.to(m.enabled, on ? 1 : 0, 14);
        if (look.fillEnabled) {
            g.fill((int) x + 1, (int) ry, (int) (x + W - 1), (int) (ry + rh), hover ? look.rowHover : look.rowOff);
            if (onP > .01f) g.fill((int) x + 1, (int) ry, (int) (x + 1 + (W - 2) * onP), (int) (ry + rh),
                    alpha(mix(accent, 0xFFFFFFFF, hover ? .08f : 0), .88f));
        } else if (look.barEnabled) {
            if (hover) g.fill((int) x + 1, (int) ry, (int) (x + W - 1), (int) (ry + rh), look.rowHover);
            if (onP > .01f) {
                float inset = rh * (1 - Math.min(1, onP)) / 2;
                g.fill((int) x + 1, (int) (ry + inset), (int) x + 3, (int) (ry + rh - inset), accent);
            }
        } else if (hover) {
            g.fill((int) x + 1, (int) ry, (int) (x + W - 1), (int) (ry + rh), look.rowHover);
        }
        int color = mix(look.offText, onText(m.getCategory()), onP);
        float ty = ry + (rh - look.text) / 2 + .5f;
        float right = x + W - 4;
        boolean hasSettings = !settingsOf(m).isEmpty();
        if (hasSettings) {
            String sign = expanded.contains(m) ? "-" : "+";
            textRight(g, sign, right, ty, look.text, HudFonts.SEMI, alpha(0xFFFFFFFF, .75f));
            right -= width(sign, look.text, HudFonts.SEMI) + 4;
        }
        String bind = bindLabel(m.bind.getValue());
        if (bind != null) {
            textRight(g, bind, right, ty + 1, look.text - 1.5f, HudFonts.REGULAR, alpha(0xFFFFFFFF, .5f));
            right -= width(bind, look.text - 1.5f) + 4;
        }
        text(g, fit(m.getDisplayName(), right - x - 6, look.text), x + 5, ty, look.text, on ? HudFonts.SEMI : HudFonts.REGULAR, color);
        if (inside) hit(x, Math.max(ry, top), W, Math.min(ry + rh, bottom) - Math.max(ry, top), b -> {
            if (b == 0) { UiSounds.moduleToggle(!m.isEnabled()); m.toggle(); }
            else if (b == 1 && hasSettings) {
                UiSounds.click();
                if (!expanded.remove(m)) expanded.add(m);
                closePopovers();
            }
        });
    }

    private void drawSettings(GuiGraphics g, Module m, float x, float sy, float W, double mx, double my,
                              float top, float bottom, int accent) {
        float rh = look.row;
        int bg = look.fillEnabled ? 0xC00B0A10 : 0x60000000;
        g.fill((int) x + 1, (int) sy, (int) (x + W - 1), (int) (sy + expandedHeight(m)), bg);
        g.fill((int) x + 1, (int) sy, (int) x + 2, (int) (sy + expandedHeight(m) - 2), alpha(accent, .9f));
        float ry = sy + 1;
        for (Setting<?> s : settingsOf(m)) {
            if (ry + rh > top && ry < bottom) drawSetting(g, m, s, x + 3, ry, W - 5, rh, mx, my, top, bottom, accent);
            ry += rh;
        }
    }

    private void drawSetting(GuiGraphics g, Module owner, Setting<?> s, float sx, float sy, float sw, float sh,
                             double mx, double my, float top, float bottom, int accent) {
        Object v = s.getValue();
        boolean inside = my >= top && my < bottom;
        boolean hover = inside && contains(mx, my, sx, sy, sw, sh);
        if (hover) hint = s.hoverHint();
        if (hover) { g.fill((int) sx, (int) sy, (int) (sx + sw), (int) (sy + sh), 0x14FFFFFF); hoverTarget = s; }
        float size = look.text - .5f, ty = sy + (sh - size) / 2 + .5f;
        String label = settingLabel(owner, s);
        int labelColor = 0xFFE4E2EC;

        if (v instanceof Number n && s.hasRestriction()) {
            float a = ((Number) s.getMin()).floatValue(), b = ((Number) s.getMax()).floatValue();
            float ratio = b <= a ? 0 : Math.clamp((n.floatValue() - a) / (b - a), 0, 1);
            float shown = anim.to("slide:" + System.identityHashCode(s), ratio, 22);
            g.fill((int) sx, (int) (sy + 1), (int) (sx + sw * shown), (int) (sy + sh - 1), alpha(accent, look.fillEnabled ? .55f : .38f));
            if (!look.fillEnabled) g.fill((int) sx, (int) (sy + sh - 2), (int) (sx + sw * shown), (int) (sy + sh - 1), accent);
        }
        if (editing == s && editor != null) {
            editX = sx; editY = sy; editW = sw; editH = sh;
            drawInlineEditor(g, sx, sy, sw, sh, size, accent);
            return;
        }
        text(g, fit(label, sw * .6f, size), sx + 2, ty, size, labelColor);
        float right = sx + sw - 2;
        if (v instanceof Boolean on) {
            float bs = Math.min(sh - 5, 7), by = sy + (sh - bs) / 2;
            if (on) round(g, right - bs, by, bs, bs, 1.5f, accent);
            else outline(g, right - bs, by, bs, bs, 1.5f, 1, 0x90FFFFFF);
            if (on) icon(g, Icon.CHECK, right - bs + .5f, by + .5f, bs - 1, 0xFFFFFFFF);
        } else if (v instanceof Color c) {
            float cw = 16, ch = sh - 5, cy = sy + (sh - ch) / 2;
            ColorPicker.checker(g, right - cw, cy, cw, ch);
            round(g, right - cw, cy, cw, ch, 1.5f, c.getRGB());
            outline(g, right - cw, cy, cw, ch, 1.5f, 1, 0x60FFFFFF);
        } else {
            String value;
            if (v instanceof Bind bind) value = binding == s ? "…" : bindLabel(bind) == null ? "无" : bindLabel(bind);
            else if (v instanceof Enum<?> e) value = labelOf(e);
            else if (v instanceof Vector2f vec) value = number(vec.x) + "," + number(vec.y);
            else if (v instanceof Number n) value = number(n);
            else value = String.valueOf(v);
            int valueColor = binding == s ? accent : 0xFFFFFFFF;
            textRight(g, fit(value, sw * .42f, size), right, ty, size, HudFonts.SEMI, alpha(valueColor, .92f));
        }
        if (!inside || sy < top - 1 || sy + sh > bottom + 1) return;
        final float fx = sx, fw = sw, fy = sy;
        hit(sx, sy, sw, sh, button -> {
            boolean shift = (clickModifiers & GLFW_MOD_SHIFT) != 0;
            if (button == 1 && !(v instanceof Enum<?>)) { UiSounds.click(); s.reset(); return; }
            if (v instanceof Boolean on) { if (button == 0) { UiSounds.toggle(!on); assign(s, !on); } return; }
            UiSounds.click();
            if (v instanceof Number && s.hasRestriction()) {
                if (button != 0) return;
                if (shift) beginEdit(s);
                else { sliding = s; slideX = fx; slideW = fw; setSlider(mx); }
            } else if (v instanceof Enum<?> e) {
                Object[] values = e.getClass().getEnumConstants();
                int dir = button == 1 ? -1 : 1;
                assign(s, values[Math.floorMod(e.ordinal() + dir, values.length)]);
            } else if (v instanceof Color c) {
                if (button == 0) { closePopovers(); picker.open(s, c, fx + fw + 6, fy - 20); }
            } else if (v instanceof Bind) {
                if (button == 0) { closePopovers(); binding = s; search.focused = false; }
            } else if (button == 0) beginEdit(s);
        });
    }

    private void drawInlineEditor(GuiGraphics g, float sx, float sy, float sw, float sh, float size, int accent) {
        g.fill((int) sx, (int) sy, (int) (sx + sw), (int) (sy + sh), 0xF0101018);
        outline(g, sx, sy, sw, sh, 1, 1, accent);
        String value = editor.value();
        text(g, fit(value, sw - 8, size), sx + 3, sy + (sh - size) / 2 + .5f, size, 0xFFFFFFFF);
        if ((System.currentTimeMillis() / 500) % 2 == 0) {
            float cx = sx + 3 + Math.min(width(value, size), sw - 8);
            g.fill((int) cx, (int) sy + 2, (int) cx + 1, (int) (sy + sh - 2), accent);
        }
    }

    private float descriptionHeight() {
        Module m = tooltipModule;
        if (m == null) return look.row * 2;
        return look.row + 4 + wrap(m.getDescription().isBlank() ? "没有描述" : m.getDescription(), look.width - 10, look.text - .5f, 8).size() * (look.text + 3) + 4;
    }

    private void drawDescription(GuiGraphics g, float x, float top, float W) {
        if (hovered != null) tooltipModule = hovered;
        Module m = tooltipModule;
        if (m == null) {
            text(g, "悬停模块查看描述", x + 5, top + 4, look.text - .5f, 0x90FFFFFF);
            return;
        }
        text(g, fit(m.getDisplayName(), W - 10, look.text), x + 5, top + 4, look.text, HudFonts.SEMI, 0xFFFFFFFF);
        float ly = top + look.row + 4;
        for (String line : wrap(m.getDescription().isBlank() ? "没有描述" : m.getDescription(), W - 10, look.text - .5f, 8)) {
            text(g, line, x + 5, ly, look.text - .5f, 0xC8FFFFFF);
            ly += look.text + 3;
        }
    }

    private void drawTooltip(GuiGraphics g, double mx, double my) {
        ClickGuiModule gui = ClickGuiModule.getInstance();
        if (gui != null && !gui.descriptions.getValue()) return;
        if (hovered != tooltipModule) {
            tooltipModule = hovered;
            hoverSince = System.currentTimeMillis();
        }
        Module m = tooltipModule;
        if (m == null || m.getDescription().isBlank() || System.currentTimeMillis() - hoverSince < 250) return;
        List<String> lines = wrap(m.getDescription(), 180, 8, 6);
        float tw = 0;
        for (String l : lines) tw = Math.max(tw, width(l, 8));
        tw += 10;
        float th = lines.size() * 11 + 6;
        float tx = (float) mx + 10, ty = (float) my + 8;
        if (tx + tw > vw - 4) tx = (float) mx - tw - 6;
        if (ty + th > vh - 4) ty = vh - 4 - th;
        round(g, tx, ty, tw, th, 2, 0xF00A0B11);
        outline(g, tx, ty, tw, th, 2, 1, accent(m.getCategory()));
        float ly = ty + 4;
        for (String l : lines) {
            text(g, l, tx + 5, ly, 8, 0xFFEDEDF2);
            ly += 11;
        }
    }

    // ------------------------------------------------------------ editing

    private void beginEdit(Setting<?> s) {
        closePopovers();
        editing = s;
        Object v = s.getValue();
        String value = v instanceof Color c ? ColorPicker.hex(c) : v instanceof Vector2f p ? p.x + "," + p.y : String.valueOf(v);
        editor = new ShadcnTextField(value, t -> { });
        editor.focused = true;
        editor.selectAll();
        search.focused = false;
    }

    private void editPickedHex() {
        Setting<?> s = picker.target();
        picker.close();
        if (s != null) beginEdit(s);
    }

    private void commitEdit() {
        try {
            String text = editor.value().trim();
            Object old = editing.getValue(), next;
            if (old instanceof Integer) next = Integer.valueOf(text);
            else if (old instanceof Long) next = Long.valueOf(text);
            else if (old instanceof Float) { float f = Float.parseFloat(text); if (!Float.isFinite(f)) throw new IllegalArgumentException(); next = f; }
            else if (old instanceof Double) { double d = Double.parseDouble(text); if (!Double.isFinite(d)) throw new IllegalArgumentException(); next = d; }
            else if (old instanceof Color) next = parseHex(text);
            else if (old instanceof Vector2f) {
                String[] parts = text.split(",");
                if (parts.length != 2) throw new IllegalArgumentException();
                next = new Vector2f(Float.parseFloat(parts[0].trim()), Float.parseFloat(parts[1].trim()));
            } else next = editor.value();
            if (next instanceof Number n && editing.hasRestriction()) {
                double min = ((Number) editing.getMin()).doubleValue(), max = ((Number) editing.getMax()).doubleValue();
                if (n.doubleValue() < min || n.doubleValue() > max) throw new IllegalArgumentException();
            }
            assign(editing, next);
            editor = null;
            editing = null;
        } catch (IllegalArgumentException invalid) {
            hint = "格式无效或超出范围";
            UiSounds.error();
            editor.selectAll();
        }
    }

    private void setSlider(double mx) {
        if (sliding == null) return;
        double min = ((Number) sliding.getMin()).doubleValue(), max = ((Number) sliding.getMax()).doubleValue();
        double value = min + (max - min) * Math.clamp((mx - slideX) / slideW, 0d, 1d);
        Object old = sliding.getValue();
        if (old instanceof Integer) assign(sliding, (int) Math.round(value));
        else if (old instanceof Long) assign(sliding, Math.round(value));
        else if (old instanceof Float) assign(sliding, (float) (Math.round(value * 100) / 100d));
        else assign(sliding, Math.round(value * 100) / 100d);
    }

    // ------------------------------------------------------------ input

    @Override
    boolean mouseClicked(MouseButtonEvent e, boolean doubled) {
        double mx = e.x() / scale, my = e.y() / scale;
        int b = e.button();
        clickModifiers = e.modifiers();
        if (binding != null) {
            assign(binding, Bind.fromMouseButton(b));
            binding = null;
            return true;
        }
        if (modalFrom >= 0 && picker.active()) {
            if (picker.press(mx, my)) return true;
            picker.close();
            return true;
        }
        if (editor != null && !contains(mx, my, editX, editY, editW, editH)) commitEdit();
        if (editor != null) return true;
        search.focused = contains(mx, my, search.x, search.y, search.w, search.h) && b == 0;
        for (int i = hits.size() - 1; i >= 0; i--) {
            if (hits.get(i).at(mx, my)) { hits.get(i).action.accept(b); return true; }
        }
        return true;
    }

    @Override
    boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        double mx = e.x() / scale, my = e.y() / scale;
        if (picker.dragging()) { picker.drag(mx, my); return true; }
        if (sliding != null) { setSlider(mx); return true; }
        if (grabbed != null) {
            if (Math.abs(mx - pressX) + Math.abs(my - pressY) > 3) moved = true;
            if (moved) {
                grabbed.x = (float) Math.clamp(mx - grabDX, -look.width + 20, vw - 20);
                grabbed.y = (float) Math.clamp(my - grabDY, 0, vh - look.header);
            }
            return true;
        }
        return false;
    }

    @Override
    boolean mouseReleased(MouseButtonEvent e) {
        picker.release();
        sliding = null;
        if (grabbed != null) {
            if (!moved) { UiSounds.click(); grabbed.collapsed = !grabbed.collapsed; }
            grabbed = null;
            saveLayout();
        }
        return true;
    }

    @Override
    boolean mouseScrolled(double sx, double sy, double horizontal, double vertical) {
        double mx = sx / scale, my = sy / scale;
        if (picker.active() || editor != null) return true;
        for (int i = panels.size() - 1; i >= 0; i--) {
            Panel p = panels.get(i);
            if (contains(mx, my, p.x, p.y, look.width, look.header + p.bodyHeight)) {
                p.scrollTarget -= (float) (vertical * look.row * 2);
                return true;
            }
        }
        for (Panel p : panels) p.y += (float) (vertical * look.row * 2);
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
        if (picker.active()) {
            if (k == GLFW_KEY_ESCAPE || k == GLFW_KEY_ENTER || k == GLFW_KEY_KP_ENTER) picker.close();
            return true;
        }
        if ((e.modifiers() & GLFW_MOD_CONTROL) != 0 && k == GLFW_KEY_F) {
            search.focused = true;
            search.selectAll();
            return true;
        }
        if (search.focused) {
            if (k == GLFW_KEY_ESCAPE) {
                if (search.value().isEmpty()) search.focused = false;
                else { search = new ShadcnTextField("", s -> query = s); search.padLeft = 12; search.focused = true; query = ""; }
            } else if (k == GLFW_KEY_ENTER || k == GLFW_KEY_KP_ENTER) search.focused = false;
            else search.key(e);
            return true;
        }
        if (k == GLFW_KEY_ESCAPE) { screen.onClose(); return true; }
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
        if (binding != null || picker.active()) return true;
        if (!search.focused) {
            String s = e.codepointAsString();
            if (s.isEmpty() || Character.isISOControl(s.codePointAt(0)) || s.equals(" ")) return true;
            search.focused = true;
        }
        return search.character(e);
    }

    @Override
    void removed() {
        closePopovers();
        grabbed = null;
    }

    // ------------------------------------------------------------ helpers

    private void hit(float hx, float hy, float hw, float hh, IntConsumer action) {
        if (hw > 0 && hh > 0) hits.add(new Hit(hx, hy, hw, hh, action));
    }

    private void save() {
        if (GuizhouWare.configManager != null) GuizhouWare.configManager.save();
    }

    private String settingLabel(Module owner, Setting<?> s) {
        String label = "CoupledCruise".equals(s.getName()) ? "GZBoost" : s.getName();
        if (owner instanceof HudClientModule) {
            String page = s.getPage();
            if (label.startsWith(page + " ")) label = label.substring(page.length() + 1);
        }
        return label;
    }

    private static String bindLabel(Bind bind) {
        if (bind == null || bind.isEmpty()) return null;
        String s = bind.toString();
        return s == null || s.isBlank() ? null : s.toUpperCase(Locale.ROOT);
    }

    private static String number(Number n) {
        if (n instanceof Integer || n instanceof Long) return n.toString();
        return String.format(Locale.ROOT, "%.2f", n.doubleValue()).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String number(float f) { return number((Number) f); }

    private static Color parseHex(String text) {
        text = text.replaceFirst("^#", "");
        if (!text.matches("[a-fA-F0-9]{6}([a-fA-F0-9]{2})?")) throw new IllegalArgumentException();
        long rgba = Long.parseLong(text, 16);
        if (text.length() == 6) rgba = (rgba << 8) | 255;
        return new Color((int) (rgba >> 24) & 255, (int) (rgba >> 16) & 255, (int) (rgba >> 8) & 255, (int) rgba & 255);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void assign(Setting s, Object v) { s.setValue(v); }

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

    private static String categoryName(Module.Category c) {
        return switch (c) {
            case COMBAT -> "战斗";
            case MOVEMENT -> "移动";
            case RENDER -> "渲染";
            case PLAYER -> "玩家";
            case WORLD -> "世界";
            case CLIENT -> "客户端";
            case FUNNY -> "趣味";
            case HUD -> "HUD";
        };
    }
}
