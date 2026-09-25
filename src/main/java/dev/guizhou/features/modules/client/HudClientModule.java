package dev.guizhou.features.modules.client;

import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.hud.ActiveModulesHudModule;
import dev.guizhou.features.modules.hud.ArmorHudModule;
import dev.guizhou.features.modules.hud.CoordinatesHudModule;
import dev.guizhou.features.modules.hud.CountsHudModule;
import dev.guizhou.features.modules.hud.NotifierHudModule;
import dev.guizhou.features.modules.hud.PingHudModule;
import dev.guizhou.features.modules.hud.RadarHudModule;
import dev.guizhou.features.modules.hud.PlayersHudModule;
import dev.guizhou.features.modules.hud.SpeedHudModule;
import dev.guizhou.features.modules.hud.TargetHudModule;
import dev.guizhou.features.modules.hud.TotemsHudModule;
import dev.guizhou.features.settings.Setting;
import net.minecraft.client.gui.screens.Screen;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HudClientModule extends Module {

    private static final List<Class<? extends HudModule>> BOTTOM_STACK = List.of(
            CoordinatesHudModule.class,
            PingHudModule.class,
            RadarHudModule.class,
            ActiveModulesHudModule.class);

    private final Map<HudModule, Setting<Boolean>> elements = new LinkedHashMap<>();
    // Per-element custom layout, saved through the normal module config.
    private final Map<HudModule, Setting<Boolean>> customPos = new LinkedHashMap<>();
    private final Map<HudModule, Setting<Double>> posX = new LinkedHashMap<>();
    private final Map<HudModule, Setting<Double>> posY = new LinkedHashMap<>();
    private final Map<HudModule, Setting<Double>> sizes = new LinkedHashMap<>();
    private final Map<HudModule, Setting<Double>> opacity = new LinkedHashMap<>();
    private final Map<HudModule, Setting<Color>> backgrounds = new LinkedHashMap<>();
    private final Map<HudModule, Setting<Color>> foregrounds = new LinkedHashMap<>();
    private final Map<HudModule, Setting<Color>> accents = new LinkedHashMap<>();

    // Live editor drag override (screen px); only present while the editor drags.
    private HudModule dragTarget;
    private int dragX, dragY;
    private boolean editorOpen;

    public final Setting<Color> radarEnemyColor  = color("Radar Enemy Color",  255, 255, 255, 255).setPage("Colors");
    public final Setting<Color> radarFriendColor = color("Radar Friend Color",   0, 255, 100, 255).setPage("Colors");
    public final Setting<Color> radarSelfColor   = color("Radar Self Color",   255, 255, 255, 255).setPage("Colors");
    public final Setting<Color> activeModuleColor = color("Active Module Color", 175,   0,   0, 255).setPage("Colors");

    public Setting<HudPosition> coordinatesPosition;
    public Setting<HudPosition> activeModulesPosition;

    public HudClientModule() {
        super("Hud", "Static-position HUD elements", Category.CLIENT);
        register(new TotemsHudModule(), true);
        register(new ArmorHudModule(), true);
        register(new CountsHudModule(), true);
        register(new CoordinatesHudModule(), true);
        coordinatesPosition = mode("Coordinates Position", HudPosition.BOTTOM_RIGHT).setPage("Elements");
        register(new PingHudModule(), true);
        register(new RadarHudModule(), true);
        register(new PlayersHudModule(), true);
        register(new ActiveModulesHudModule(), true);
        activeModulesPosition = mode("ActiveModules Position", HudPosition.CENTER_RIGHT).setPage("Elements");
        register(new NotifierHudModule(), true);
        register(new SpeedHudModule(), false);
        register(new TargetHudModule(), true);
        register(new dev.guizhou.features.modules.hud.DiagnosticsHudModule(), true);
    }

    private void register(HudModule element, boolean defaultOn) {
        elements.put(element, bool(element.getName(), defaultOn).setPage("Elements"));
        String id = element.getName();
        customPos.put(element, bool(id + " CustomPos", false).setPage("Layout"));
        posX.put(element, num(id + " X", 0.5, 0.0, 1.0).setPage("Layout"));
        posY.put(element, num(id + " Y", 0.5, 0.0, 1.0).setPage("Layout"));
        sizes.put(element, num(id + " 大小 (%)", 100.0, 50.0, 200.0).setPage(id));
        opacity.put(element, num(id + " 背景不透明度 (%)", 100.0, 0.0, 100.0).setPage(id));
        boolean target = element instanceof TargetHudModule;
        Color bg = new Color(target ? 0x171717 : 0x182338);
        Color fg = new Color(target ? 0xFAFAFA : 0xE0E6F2);
        Color acc = new Color(target ? 0xE5E5E5 : 0x72A8D8);
        backgrounds.put(element, register(new Setting<>(id + " 背景颜色", bg)).setPage(id));
        foregrounds.put(element, register(new Setting<>(id + " 文字颜色", fg)).setPage(id));
        accents.put(element, register(new Setting<>(id + " 强调色", acc)).setPage(id));
    }

    public float scaleOf(HudModule element) { return sizes.get(element).getValue().floatValue()/100f; }
    public void resizeElement(HudModule element, double percent) { sizes.get(element).setValue(percent); }
    public dev.guizhou.util.render.HudAppearance appearanceOf(HudModule element) {
        return new dev.guizhou.util.render.HudAppearance(backgrounds.get(element).getValue().getRGB(),
                foregrounds.get(element).getValue().getRGB(), accents.get(element).getValue().getRGB(),
                opacity.get(element).getValue().floatValue()/100f);
    }

    public HudPosition positionOf(HudModule element) {
        if (element instanceof CoordinatesHudModule) return coordinatesPosition.getValue();
        if (element instanceof ActiveModulesHudModule) return activeModulesPosition.getValue();
        return HudPosition.BOTTOM_RIGHT;
    }

    private int lineCount(HudModule element) {
        if (element instanceof RadarHudModule radar) return radar.renderedLineCount();
        if (element instanceof ActiveModulesHudModule active) return active.getEntries().size();
        return 1;
    }

    /**
     * Estimated pixel height of the bottom-stack elements drawn below this one,
     * so card-shaped restyled elements keep default anchors without overlapping.
     */
    public int stackBelowHeight(HudModule element) {
        HudPosition anchor = positionOf(element);
        if (!anchor.isBottom()) return 0;
        int height = 0;
        for (Class<? extends HudModule> type : BOTTOM_STACK) {
            if (type.isInstance(element)) break;
            HudModule other = getElement(type);
            if (other == null || !isElementEnabled(type)) continue;
            if (positionOf(other) != anchor) continue;
            if (other instanceof RadarHudModule radar) height += radar.renderedLineCount() * 15 + 12;
            else if (other instanceof ActiveModulesHudModule active) height += active.getEntries().size() * 15 + 12;
            else height += 28;
        }
        return height;
    }

    public int linesBelow(HudModule element) {
        HudPosition anchor = positionOf(element);
        if (!anchor.isBottom()) return 0;

        int lines = 0;
        for (Class<? extends HudModule> type : BOTTOM_STACK) {
            if (type.isInstance(element)) break;
            HudModule other = getElement(type);
            if (other == null || !isElementEnabled(type)) continue;
            if (positionOf(other) != anchor) continue;
            lines += lineCount(other);
        }
        return lines;
    }

    @SuppressWarnings("unchecked")
    public <T extends HudModule> T getElement(Class<T> type) {
        for (HudModule element : elements.keySet()) {
            if (type.isInstance(element)) return (T) element;
        }
        return null;
    }

    public boolean isElementEnabled(Class<? extends HudModule> type) {
        for (Map.Entry<HudModule, Setting<Boolean>> entry : elements.entrySet()) {
            if (type.isInstance(entry.getKey())) return entry.getValue().getValue();
        }
        return false;
    }

    // ------------------------------------------------------------ editor & layout

    /** GPT wiring point: open the layout editor from ClickGUI / a keybind. */
    public void openEditor(Screen parent) {
        if (mc.player == null && mc.level == null) return;
        editorOpen = true;
        mc.setScreen(new dev.guizhou.features.gui.HudEditorScreen(parent));
    }

    public void markEditorClosed() {
        editorOpen = false;
        endDrag();
    }

    public boolean isEditorOpen() {
        return editorOpen;
    }

    public List<HudModule> elementList() {
        return List.copyOf(elements.keySet());
    }

    public Setting<Boolean> elementToggle(HudModule element) {
        return elements.get(element);
    }

    public boolean isCustomPosition(HudModule element) {
        Setting<Boolean> c = customPos.get(element);
        return c != null && c.getValue();
    }

    public float normalizedX(HudModule element) {
        Setting<Double> s = posX.get(element);
        return s == null ? 0.5f : s.getValue().floatValue();
    }

    public float normalizedY(HudModule element) {
        Setting<Double> s = posY.get(element);
        return s == null ? 0.5f : s.getValue().floatValue();
    }

    /** Persist a dropped position as normalized settings; clamped to keep the element onscreen. */
    public void savePosition(HudModule element, int x, int y, int w, int h) {
        int sw = Math.max(1, mc.getWindow().getGuiScaledWidth());
        int sh = Math.max(1, mc.getWindow().getGuiScaledHeight());
        if (w > 0) x = Math.max(0, Math.min(x, sw - w));
        if (h > 0) y = Math.max(0, Math.min(y, sh - h));
        Setting<Double> sx = posX.get(element);
        Setting<Double> sy = posY.get(element);
        Setting<Boolean> use = customPos.get(element);
        if (sx == null || sy == null || use == null) return;
        sx.setValue(x / (double) sw);
        sy.setValue(y / (double) sh);
        use.setValue(true);
    }

    /** Return one element to its default anchor (editor R key / per-element reset). */
    public void clearPosition(HudModule element) {
        Setting<Boolean> use = customPos.get(element);
        if (use != null) use.setValue(false);
        if (dragTarget == element) endDrag();
    }

    public void resetLayout() {
        for (Map.Entry<HudModule, Setting<Boolean>> e : customPos.entrySet()) {
            e.getValue().setValue(false);
            Setting<Double> sx = posX.get(e.getKey());
            Setting<Double> sy = posY.get(e.getKey());
            if (sx != null) sx.setValue(0.5);
            if (sy != null) sy.setValue(0.5);
        }
        endDrag();
    }

    /** Live pixel override while the editor drags this element (null when not dragging). */
    public int[] livePosition(HudModule element) {
        if (dragTarget != element || !editorOpen) return null;
        return new int[]{dragX, dragY};
    }

    public void updateDrag(HudModule element, int x, int y) {
        dragTarget = element;
        dragX = x;
        dragY = y;
    }

    public void endDrag() {
        dragTarget = null;
    }

    public HudLayout.Rect defaultPlacement(HudModule element, int x, int y, int w, int h) {
        int sw=mc.getWindow().getGuiScaledWidth(),sh=mc.getWindow().getGuiScaledHeight();
        var occupied=new java.util.ArrayList<HudLayout.Rect>();
        occupied.add(new HudLayout.Rect(sw/2-94,sh-42,188,42));
        for(HudModule other:elements.keySet()) {
            if(other==element || !elements.get(other).getValue() || other.boundW()<=0 || other.boundH()<=0)continue;
            if(other.wasRendered())occupied.add(new HudLayout.Rect(other.boundX(),other.boundY(),other.boundW(),other.boundH()));
            else if(isCustomPosition(other))occupied.add(new HudLayout.Rect(Math.round(normalizedX(other)*sw),Math.round(normalizedY(other)*sh),other.boundW(),other.boundH()));
        }
        return HudLayout.place(new HudLayout.Rect(x,y,w,h),sw,sh,occupied);
    }

    @Override public void onDisable() {
        for(HudModule element:elements.keySet())element.beginFrame();
    }

    @Override
    public void onRender2D(Render2DEvent event) {
        if (nullCheck() || mc.options.hideGui) {
            // Frame flags must stay truthful when nothing renders, so editor
            // previews never mistake stale state for live elements.
            for (HudModule element : elements.keySet()) element.beginFrame();
            return;
        }
        dev.guizhou.util.render.font.Fonts.beginHudPass();
        try {
            for(HudModule element:elements.keySet())element.beginFrame();
            // Reserve the measured TargetHUD canvas before placing smaller default cards.
            HudModule target=getElement(TargetHudModule.class);
            if(target!=null && elements.get(target).getValue())target.renderStyled(event, this);
            for (Map.Entry<HudModule, Setting<Boolean>> entry : elements.entrySet()) {
                HudModule element = entry.getKey();
                if(element==target)continue;
                if (!entry.getValue().getValue()) continue;
                element.renderStyled(event, this);
            }
        } finally {
            dev.guizhou.util.render.font.Fonts.endHudPass();
        }
    }
}
