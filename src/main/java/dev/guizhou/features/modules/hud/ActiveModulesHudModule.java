package dev.guizhou.features.modules.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.client.HudClientModule;
import dev.guizhou.features.modules.client.HudModule;
import dev.guizhou.features.modules.client.HudPosition;
import dev.guizhou.features.settings.Bind;
import dev.guizhou.util.render.Anim;
import dev.guizhou.util.render.HudDraw;
import dev.guizhou.util.render.ShadcnDraw;
import dev.guizhou.util.render.font.HudFonts;
import dev.guizhou.util.traits.Jsonable;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Live enabled-module list, with an optional saved watch list. A thin edge mark
 * shows the on state. Meta and bind render as muted text and a framed hint.
 * Row order: as added, by name, or longest first.
 */
public class ActiveModulesHudModule extends HudModule implements Jsonable {

    public enum Sort { ADDED, NAME, WIDTH }
    public enum Source { ENABLED, WATCHLIST }

    private static final float NAME = 8.5f, META = 7f, ROW = 13f, PAD = 6f, EDGE = 2f;

    private static ActiveModulesHudModule INSTANCE;

    private final LinkedHashSet<String> entries = new LinkedHashSet<>();
    private final Anim anim = new Anim();
    private final List<Line> lines = new ArrayList<>();

    private record Line(String id, String display, String meta, String bind, boolean enabled, float width) {}

    public ActiveModulesHudModule() {
        super("ActiveModules");
        INSTANCE = this;
        GuizhouWare.configManager.addConfig(this);
    }

    public static ActiveModulesHudModule getInstance() {
        return INSTANCE;
    }

    public boolean add(String name) {
        Module module = GuizhouWare.moduleManager.getModuleByName(name);
        if (module == null) return false;
        return entries.add(module.getName());
    }

    public boolean remove(String name) {
        Module module = GuizhouWare.moduleManager.getModuleByName(name);
        String key = module != null ? module.getName() : name;
        return entries.removeIf(e -> e.equalsIgnoreCase(key));
    }

    public void clear() {
        entries.clear();
    }

    public List<String> getEntries() {
        return new ArrayList<>(entries);
    }

    /** The normal module list follows live toggles; the explicit watch list remains available. */
    public List<Module> displayedModules() {
        HudClientModule hud = GuizhouWare.moduleManager.getModuleByClass(HudClientModule.class);
        if (hud != null && hud.activeModulesSource.getValue() == Source.WATCHLIST) {
            return entries.stream().map(GuizhouWare.moduleManager::getModuleByName)
                    .filter(java.util.Objects::nonNull).toList();
        }
        return GuizhouWare.moduleManager.getModules().stream()
                .filter(m -> m.isEnabled() && !m.hidden).toList();
    }

    public int renderedLineCount() { return displayedModules().size(); }

    @Override
    public void render(Render2DEvent event) {
        List<Module> displayed = displayedModules();
        if (displayed.isEmpty()) {
            bounds(0, 0, 0, 0);
            return;
        }

        GuiGraphics ctx = event.getContext();
        HudClientModule hudClient = GuizhouWare.moduleManager.getModuleByClass(HudClientModule.class);
        int activeColor = hudClient != null
                ? 0xFF000000 | (hudClient.activeModuleColor.getValue().getRGB() & 0xFFFFFF)
                : ShadcnDraw.ACCENT;
        HudPosition pos = hudClient != null ? hudClient.positionOf(this) : HudPosition.CENTER_RIGHT;
        Sort sort = hudClient != null ? hudClient.activeModulesSort.getValue() : Sort.ADDED;
        boolean edge = hudClient == null || hudClient.activeModulesEdge.getValue();

        lines.clear();
        float textW = 0f;
        for (Module module : displayed) {
            String display = module.getDisplayName();
            String meta = module.getMeta();
            meta = meta == null || meta.isEmpty() ? "" : meta;
            Bind bind = module.getBind().getKey() > 0 ? module.getBind() : null;
            String bindText = bind != null ? bind.toString() : "";
            float w = ShadcnDraw.width(display, NAME, HudFonts.SEMI)
                    + (meta.isEmpty() ? 0f : 4f + ShadcnDraw.width(meta, META))
                    + (bindText.isEmpty() ? 0f : 5f + HudDraw.keycapWidth(bindText, 6f));
            textW = Math.max(textW, w);
            lines.add(new Line(module.getName(), display, meta, bindText, module.isEnabled(), w));
        }
        if (lines.isEmpty()) {
            bounds(0, 0, 0, 0);
            return;
        }
        if (sort == Sort.NAME) lines.sort(Comparator.comparing(Line::display, String.CASE_INSENSITIVE_ORDER));
        else if (sort == Sort.WIDTH) lines.sort(Comparator.comparingDouble(Line::width).reversed());

        float edgeRoom = edge ? EDGE + 5f : 0f;
        float cardW = PAD * 2 + edgeRoom + textW;
        float cardH = PAD * 2 - 2f + lines.size() * ROW;
        int defX = pos.isLeft() ? 4 : Math.round(screenWidth() - cardW - 4f);
        int defY = pos.isBottom()
                ? Math.round(bottomAnchor() - 4f - (hudClient != null ? hudClient.stackBelowHeight(this) : 0) - cardH)
                : Math.round(screenHeight() / 2f - cardH / 2f);
        int[] o = origin(defX, defY, (int) cardW, (int) cardH);
        int ox = o[0], oy = o[1];

        HudDraw.card(ctx, ox, oy, cardW, cardH, HudDraw.RADIUS);
        boolean left = pos.isLeft();
        int accent = HudDraw.accent();
        int n = lines.size();
        for (int i = 0; i < n; i++) {
            Line line = lines.get(i);
            float on = anim.to(line.id(), line.enabled() ? 1f : 0f, 12f);
            float mid = oy + PAD - 1f + i * ROW + ROW / 2f;
            if (edge) {
                float ex = left ? ox + PAD : ox + cardW - PAD - EDGE;
                ShadcnDraw.round(ctx, ex, mid - ROW / 2f + 1.5f, 1.5f, ROW - 3f, 0,
                        ShadcnDraw.mix(HudDraw.fade(ShadcnDraw.SUBTLE, .45f), accent, on));
            }
            int nameColor = ShadcnDraw.mix(ShadcnDraw.MUTED, activeColor, on);
            float x = left ? ox + PAD + edgeRoom : ox + cardW - PAD - edgeRoom - line.width();
            HudDraw.textMid(ctx, line.display(), x, mid, NAME, HudFonts.SEMI, nameColor);
            x += ShadcnDraw.width(line.display(), NAME, HudFonts.SEMI);
            if (!line.meta().isEmpty()) {
                x += 4f;
                HudDraw.textMid(ctx, line.meta(), x, mid, META, HudFonts.REGULAR, ShadcnDraw.MUTED);
                x += ShadcnDraw.width(line.meta(), META);
            }
            if (!line.bind().isEmpty()) HudDraw.keycap(ctx, line.bind(), x + 5f, mid - 4.5f, 9f, 6f);
        }
        bounds(ox, oy, (int) cardW, (int) cardH);
    }

    @Override
    public JsonElement toJson() {
        JsonObject object = new JsonObject();
        JsonArray array = new JsonArray();
        for (String entry : entries) array.add(entry);
        object.add("entries", array);
        return object;
    }

    @Override
    public void fromJson(JsonElement element) {
        entries.clear();
        JsonElement arr = element.getAsJsonObject().get("entries");
        if (arr == null || !arr.isJsonArray()) return;
        for (JsonElement e : arr.getAsJsonArray()) entries.add(e.getAsString());
    }

    @Override
    public String getFileName() {
        return "active_modules_hud.json";
    }
}
