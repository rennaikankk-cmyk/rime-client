package probe;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.client.HudClientModule;
import dev.guizhou.features.modules.client.HudModule;
import dev.guizhou.features.settings.Setting;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import java.awt.Color;
import java.nio.file.Files;
import java.util.*;

/** Runs actual module/config/event paths in an isolated native development client. */
public final class HudStateProbe implements ClientModInitializer {
    int ticks, checks;
    boolean done;
    volatile int sink;
    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (done || ++ticks < 100 || mc.getOverlay() != null) return;
            done = true;
            try { run(mc); }
            catch (Throwable ex) {
                ex.printStackTrace();
                try { Files.writeString(mc.gameDirectory.toPath().resolve("failure.txt"), ex.toString()); } catch (Exception ignored) {}
            } finally { mc.stop(); }
        });
    }
    @SuppressWarnings("unchecked")
    void run(Minecraft mc) throws Exception {
        var hud = GuizhouWare.moduleManager.getModuleByClass(HudClientModule.class);
        var saved = hud.toJson();
        Map<String, Object> results = new LinkedHashMap<>();
        try {
            if (!hud.isEnabled()) hud.enable();
            hud.migrateLegacyPalette(); hud.migrateLegacyPalette();
            var elements = hud.elementList();
            for (var element : elements) {
                check(hud.getElement(element.getClass()) == element, "Exact type lookup");
                var toggle = hud.elementToggle(element); boolean value = toggle.getValue();
                toggle.setValue(!value); check(hud.isElementEnabled(element.getClass()) == !value, "Live toggle");
                toggle.setValue(value);
            }
            check(hud.getElement(HudModule.class) == elements.getFirst(), "Base type preserves first matching element");
            var target = elements.getFirst(); String name = target.getName();
            Setting<Color> background = (Setting<Color>) hud.getSettingByName(name + " 背景颜色");
            Setting<Double> opacity = (Setting<Double>) hud.getSettingByName(name + " 背景不透明度 (%)");
            var first = hud.appearanceOf(target);
            results.put("reusesUnchangedAppearance", first == hud.appearanceOf(target));
            background.setValue(new Color(0x80123456, true));
            check(hud.appearanceOf(target).background() == 0x80123456, "Color/alpha updates before next tick");
            hud.migrateLegacyPalette();
            check(hud.appearanceOf(target).background() == 0x80123456, "Migration preserves custom alpha/color");
            opacity.setValue(37d);
            check(hud.appearanceOf(target).backgroundOpacity() == .37f, "Opacity updates immediately");
            background.setValue(new Color(0xFF171717, true)); hud.migrateLegacyPalette();
            check(background.getValue().equals(background.getDefaultValue()), "Color event rechecks legacy palette");
            JsonObject imported = new JsonObject(); imported.addProperty(name + " 背景颜色", "21,24,32,255");
            hud.fromJson(imported); hud.migrateLegacyPalette();
            check(background.getValue().equals(background.getDefaultValue()), "Imported legacy palette migrates");
            imported.addProperty(name + " 背景颜色", "12,34,56,78");
            hud.fromJson(imported); hud.migrateLegacyPalette();
            check(hud.appearanceOf(target).background() == new Color(12,34,56,78).getRGB(), "Imported custom color is visible");
            hud.disable(); background.setValueNoEvent(new Color(0xFF182338,true)); hud.enable(); hud.migrateLegacyPalette();
            check(background.getValue().equals(background.getDefaultValue()), "Enable after silent disabled edits invalidates migration");
            // Stable-state workload: no time-based cache; all accessors must stay current.
            hud.migrateLegacyPalette();
            List<Double> batches = new ArrayList<>();
            for (int round = 0; round < 6; round++) {
                long start = System.nanoTime(); int sum = 0;
                for (int i = 0; i < 50000; i++) {
                    hud.migrateLegacyPalette();
                    for (var e : elements) {
                        sum += hud.appearanceOf(e).background();
                        if (hud.isElementEnabled(e.getClass())) sum++;
                        sum += hud.getElement(e.getClass()).getName().length();
                    }
                    sum += hud.elementList().size();
                }
                if (round > 0) batches.add((System.nanoTime() - start) / 1e6);
                sink = sum;
            }
            results.put("elementCount", elements.size()); results.put("checks", checks);
            results.put("fiftyThousandStablePassesMs", batches);
            Files.writeString(mc.gameDirectory.toPath().resolve("results.json"), new GsonBuilder().setPrettyPrinting().create().toJson(results));
        } finally { hud.fromJson(saved); }
    }
    void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
}
