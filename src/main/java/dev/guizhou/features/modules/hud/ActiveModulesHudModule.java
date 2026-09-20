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
import dev.guizhou.util.render.ShadcnDraw;
import dev.guizhou.util.render.font.HudFonts;
import dev.guizhou.util.traits.Jsonable;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class ActiveModulesHudModule extends HudModule implements Jsonable {

    private static final float TEXT = 11f;
    private static final float ROW = 15f;
    private static final float PAD = 6f;

    private static ActiveModulesHudModule INSTANCE;

    private final LinkedHashSet<String> entries = new LinkedHashSet<>();

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

    @Override
    public void render(Render2DEvent event) {
        if (entries.isEmpty()) {
            bounds(0, 0, 0, 0);
            return;
        }

        GuiGraphics ctx = event.getContext();
        HudClientModule hudClient = GuizhouWare.moduleManager.getModuleByClass(HudClientModule.class);
        int activeColor = hudClient != null
                ? 0xFF000000 | (hudClient.activeModuleColor.getValue().getRGB() & 0xFFFFFF)
                : ShadcnDraw.ACCENT;
        HudPosition pos = hudClient != null ? hudClient.positionOf(this) : HudPosition.CENTER_RIGHT;

        record Line(String display, String meta, String suffix, boolean enabled) {}
        List<Line> lines = new ArrayList<>();
        float textW = 0f;
        for (String name : entries) {
            Module module = GuizhouWare.moduleManager.getModuleByName(name);
            if (module == null) continue;
            String display = module.getDisplayName();
            String metaRaw = module.getMeta();
            String meta = metaRaw != null ? " (" + metaRaw + ")" : "";
            Bind bind = module.getBind().getKey() > 0 ? module.getBind() : null;
            String suffix = bind != null ? " [" + bind.toString() + "]" : "";
            textW = Math.max(textW, ShadcnDraw.width(display + meta + suffix, TEXT));
            lines.add(new Line(display, meta, suffix, module.isEnabled()));
        }
        if (lines.isEmpty()) {
            bounds(0, 0, 0, 0);
            return;
        }

        float cardW = PAD * 2 + textW;
        float cardH = PAD * 2 + lines.size() * ROW - (ROW - TEXT - 2f);
        int defX = pos.isLeft() ? 4 : Math.round(screenWidth() - cardW - 4f);
        int defY = pos.isBottom()
                ? Math.round(bottomAnchor() - 4f - (hudClient != null ? hudClient.stackBelowHeight(this) : 0) - cardH)
                : Math.round(screenHeight() / 2f - cardH / 2f);
        int[] o = origin(defX, defY, (int) cardW, (int) cardH);
        int ox = o[0], oy = o[1];

        ShadcnDraw.panel(ctx, ox, oy, cardW, cardH, 6f);
        float rx = pos.isLeft() ? ox + PAD : ox + cardW - PAD;
        float firstRow = oy + PAD - 1f;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            float ry = firstRow + i * ROW;
            float x = rx;
            float afterName = x + ShadcnDraw.width(line.display(), TEXT);
            if (pos.isLeft()) {
                ShadcnDraw.text(ctx, line.display(), x, ry, TEXT, line.enabled() ? activeColor : ShadcnDraw.MUTED);
                if (!line.meta().isEmpty()) {
                    ShadcnDraw.text(ctx, line.meta(), afterName, ry, TEXT, ShadcnDraw.MUTED);
                    afterName += ShadcnDraw.width(line.meta(), TEXT);
                }
                if (!line.suffix().isEmpty()) {
                    ShadcnDraw.text(ctx, line.suffix(), afterName, ry, TEXT, activeColor);
                }
            } else {
                float end = x;
                if (!line.suffix().isEmpty()) {
                    float sx = end - ShadcnDraw.width(line.suffix(), TEXT);
                    ShadcnDraw.text(ctx, line.suffix(), sx, ry, TEXT, activeColor);
                    end = sx;
                }
                if (!line.meta().isEmpty()) {
                    float mx = end - ShadcnDraw.width(line.meta(), TEXT);
                    ShadcnDraw.text(ctx, line.meta(), mx, ry, TEXT, ShadcnDraw.MUTED);
                    end = mx;
                }
                ShadcnDraw.text(ctx, line.display(), end - ShadcnDraw.width(line.display(), TEXT), ry, TEXT,
                        line.enabled() ? activeColor : ShadcnDraw.MUTED);
            }
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
