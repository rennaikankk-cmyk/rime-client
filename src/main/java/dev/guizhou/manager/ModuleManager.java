package dev.guizhou.manager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.event.impl.render.Render3DEvent;
import dev.guizhou.features.commands.ModuleCommand;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Bind;
import dev.guizhou.features.settings.SettingText;
import dev.guizhou.features.modules.client.ClickGuiModule;
import dev.guizhou.features.modules.client.HudClientModule;
import dev.guizhou.features.modules.client.NotificationsModule;
import dev.guizhou.features.modules.client.TargetsModule;
import dev.guizhou.util.traits.Jsonable;
import dev.guizhou.util.traits.Util;
import dev.guizhou.util.diagnostics.ClientDiagnostics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * xiaoan-notac: shell + Targets + qinghua ports only.
 * Original Rime combat/render/movement modules are not registered.
 */
public class ModuleManager implements Jsonable, Util {
    private static final Logger LOGGER = LoggerFactory.getLogger("ModuleManager");

    private final Map<Class<? extends Module>, Module> fastRegistry = new HashMap<>();
    private final List<Module> modules = new ArrayList<>();
    private final Map<Module, TimingKeys> timingKeys = new IdentityHashMap<>();
    private final Set<String> reportedCallbackFailures = ConcurrentHashMap.newKeySet();
    private record TimingKeys(String tick, String render2d, String render3d) {}

    public void init() {
        // Client shell
        register(new HudClientModule());
        register(new ClickGuiModule());
        register(new NotificationsModule());
        register(new TargetsModule());

        // qinghua ports
        register(new dev.guizhou.features.modules.qinghua.OrbitModule());
        register(new dev.guizhou.features.modules.qinghua.VclipModule());
        register(new dev.guizhou.features.modules.qinghua.ChatSuffixModule());
        register(new dev.guizhou.features.modules.qinghua.AutoEZModule());
        register(new dev.guizhou.features.modules.qinghua.AutoLoginModule());
        register(new dev.guizhou.features.modules.qinghua.AutoRunnerModule());
        register(new dev.guizhou.features.modules.qinghua.AntiLagModule());
        register(new dev.guizhou.features.modules.qinghua.AntiMissModule());
        register(new dev.guizhou.features.modules.qinghua.AntiRegearModule());
        register(new dev.guizhou.features.modules.qinghua.TotemDummyModule());
        register(new dev.guizhou.features.modules.qinghua.ETPBotModule());
        register(new dev.guizhou.features.modules.qinghua.TpBotPlusModule());
        register(new dev.guizhou.features.modules.qinghua.AnchorTrapModule());
        register(new dev.guizhou.features.modules.qinghua.SpearKillModule());
        register(new dev.guizhou.features.modules.qinghua.MaceAuraBetaModule());
        register(new dev.guizhou.features.modules.qinghua.LacrymiraAuraModule());

        LOGGER.info("Registered {} modules (xiaoan-notac)", modules.size());

        for (Module module : modules) {
            GuizhouWare.commandManager.register(new ModuleCommand(module));
        }

        GuizhouWare.configManager.addConfig(this);
    }

    public void register(Module module) {
        SettingText.fill(module);
        getModules().add(module);
        fastRegistry.put(module.getClass(), module);
        String name = module.getClass().getSimpleName();
        if (name.endsWith("Module")) name = name.substring(0, name.length() - "Module".length());
        timingKeys.put(module, new TimingKeys(
                "module." + name + ".tick",
                "module." + name + ".render2d",
                "module." + name + ".render3d"));
        EVENT_BUS.register(module, name);
    }

    public void onLoad() {
        for (Module module : getModules()) {
            try {
                module.onLoad();
            } catch (RuntimeException t) {
                reportCallbackFailure(module, "onLoad", t);
            }
        }
        getModules().stream().filter(Module::isEnabled).forEach(Module::enable);
    }

    public void onUpdate() {
        for (Module module : getModules()) {
            if (!module.isEnabled()) continue;
            try {
                module.onUpdate();
            } catch (RuntimeException t) {
                reportCallbackFailure(module, "onUpdate", t);
            }
        }
    }

    public void onTick() {
        for (Module module : getModules()) {
            if (!module.isEnabled()) continue;
            TimingKeys keys = timingKeys.get(module);
            long start = ClientDiagnostics.startTimer();
            try {
                module.onTick();
            } catch (RuntimeException t) {
                reportCallbackFailure(module, "onTick", t);
            } finally {
                if (keys != null) ClientDiagnostics.endTimer(keys.tick(), start);
            }
        }
    }

    public void onRender2D(Render2DEvent event) {
        for (Module module : getModules()) {
            if (!module.isEnabled()) continue;
            TimingKeys keys = timingKeys.get(module);
            long start = ClientDiagnostics.startTimer();
            try {
                module.onRender2D(event);
            } catch (RuntimeException t) {
                reportCallbackFailure(module, "onRender2D", t);
            } finally {
                if (keys != null) ClientDiagnostics.endTimer(keys.render2d(), start);
            }
        }
    }

    public void onRender3D(Render3DEvent event) {
        for (Module module : getModules()) {
            if (!module.isEnabled()) continue;
            TimingKeys keys = timingKeys.get(module);
            long start = ClientDiagnostics.startTimer();
            try {
                module.onRender3D(event);
            } catch (RuntimeException t) {
                reportCallbackFailure(module, "onRender3D", t);
            } finally {
                if (keys != null) ClientDiagnostics.endTimer(keys.render3d(), start);
            }
        }
    }

    public void onUnload() {
        for (Module module : getModules()) {
            try {
                module.onUnload();
            } catch (RuntimeException t) {
                reportCallbackFailure(module, "onUnload", t);
            }
        }
    }

    public void onKeyPressed(int eventKey) {
        if (eventKey <= 0 || mc.screen != null) return;
        for (Module module : getModules()) {
            if (module.getBind().getKey() != eventKey) continue;
            if (module.getBindMode() == Module.BindMode.HOLD) {
                if (!module.isEnabled()) module.enable();
            } else {
                module.toggle();
            }
        }
    }

    public void onKeyReleased(int eventKey) {
        if (eventKey <= 0) return;
        for (Module module : getModules()) {
            if (module.getBind().getKey() != eventKey) continue;
            if (module.getBindMode() == Module.BindMode.HOLD && module.isEnabled()) {
                module.disable();
            }
        }
    }

    public void onMousePressed(int button) {
        if (button < 0 || mc.screen != null) return;
        Bind bind = new Bind(button);
        for (Module module : getModules()) {
            if (!module.getBind().equals(bind)) continue;
            if (module.getBindMode() == Module.BindMode.HOLD) {
                if (!module.isEnabled()) module.enable();
            } else {
                module.toggle();
            }
        }
    }

    public void onMouseReleased(int button) {
        if (button < 0) return;
        Bind bind = new Bind(button);
        for (Module module : getModules()) {
            if (!module.getBind().equals(bind)) continue;
            if (module.getBindMode() == Module.BindMode.HOLD && module.isEnabled()) {
                module.disable();
            }
        }
    }

    public List<Module> getModules() {
        return this.modules;
    }

    public Module getModuleByName(String name) {
        for (Module module : this.modules) {
            if (!module.getName().equalsIgnoreCase(name)) continue;
            return module;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public <T extends Module> T getModuleByClass(Class<T> clazz) {
        Module module = fastRegistry.get(clazz);
        return module == null ? null : (T) module;
    }

    public ArrayList<Module> getEnabledModules() {
        ArrayList<Module> enabledModules = new ArrayList<>();
        for (Module module : getModules()) {
            if (!module.isEnabled()) continue;
            enabledModules.add(module);
        }
        return enabledModules;
    }

    public ArrayList<Module> getModulesByCategory(Module.Category category) {
        ArrayList<Module> modulesCategory = new ArrayList<>();
        getModules().forEach(module -> {
            if (module.getCategory() == category) {
                modulesCategory.add(module);
            }
        });
        return modulesCategory;
    }

    public List<Module.Category> getCategories() {
        return Stream.of(Module.Category.values()).toList();
    }

    private void reportCallbackFailure(Module module, String callback, RuntimeException t) {
        String key = module.getClass().getName() + "#" + callback + "#" + t.getClass().getName();
        if (!reportedCallbackFailures.add(key)) return;
        LOGGER.error("Module {} {} failed", module.getName(), callback, t);
    }

    @Override
    public JsonElement toJson() {
        JsonObject object = new JsonObject();
        for (Module module : getModules()) {
            object.add(module.getName(), module.toJson());
        }
        return object;
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        for (Module module : getModules()) {
            if (!object.has(module.getName())) continue;
            try {
                module.fromJson(object.get(module.getName()));
            } catch (RuntimeException t) {
                LOGGER.error("Failed to load module {}", module.getName(), t);
            }
        }
    }
}
