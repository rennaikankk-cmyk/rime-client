package dev.guizhou.manager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.event.impl.render.Render3DEvent;
import dev.guizhou.features.commands.ModuleCommand;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.combat.*;
import dev.guizhou.features.modules.player.*;
import dev.guizhou.features.settings.Bind;
import dev.guizhou.features.modules.client.ClickGuiModule;
import dev.guizhou.features.modules.client.HudClientModule;
import dev.guizhou.features.modules.client.NotificationsModule;
import dev.guizhou.features.modules.client.TargetsModule;
import dev.guizhou.features.modules.funny.TrickshotModule;
import dev.guizhou.features.modules.funny.MoFuckerModule;
import dev.guizhou.features.modules.movement.SprintModule;
import dev.guizhou.features.modules.movement.VelocityModule;
import dev.guizhou.features.modules.movement.NoSlowModule;
import dev.guizhou.features.modules.movement.NoJumpDelayModule;
import dev.guizhou.features.modules.movement.EntityControlModule;
import dev.guizhou.features.modules.movement.GuangxiElytraModule;
import dev.guizhou.features.modules.world.AutoPortalModule;
import dev.guizhou.features.modules.world.FastPortalModule;
import dev.guizhou.features.modules.world.BomberModule;
import dev.guizhou.features.modules.world.GuangxiMineModule;
import dev.guizhou.features.modules.world.NukerModule;
import dev.guizhou.features.modules.world.ScaffoldModule;
import dev.guizhou.features.modules.render.BlockHighlightModule;
import dev.guizhou.features.modules.render.BreadcrumbsModule;
import dev.guizhou.features.modules.render.BreakIndicatorsModule;
import dev.guizhou.features.modules.funny.AutoHorseModule;
import dev.guizhou.features.modules.funny.DiscordRPCModule;
import dev.guizhou.features.modules.funny.EndermanLookModule;
import dev.guizhou.features.modules.render.CrystalHandModule;
import dev.guizhou.features.modules.render.ShadersModule;
import dev.guizhou.features.modules.render.SkyboxModule;
import dev.guizhou.features.modules.render.SeeThroughModule;
import dev.guizhou.features.modules.render.FullbrightModule;
import dev.guizhou.features.modules.render.LogoutSpotsModule;
import dev.guizhou.features.modules.render.NametagsModule;
import dev.guizhou.features.modules.render.TablistModule;
import dev.guizhou.features.modules.render.NoRenderModule;
import dev.guizhou.features.modules.render.PopEffectsModule;
import dev.guizhou.features.modules.render.ShulkerPreviewModule;
import dev.guizhou.features.modules.render.QuadSupremeModule;
import dev.guizhou.features.modules.render.ViewModelModule;
import dev.guizhou.util.traits.Jsonable;
import dev.guizhou.util.traits.Util;
import dev.guizhou.util.diagnostics.ClientDiagnostics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class ModuleManager implements Jsonable, Util {
    private static final Logger LOGGER = LoggerFactory.getLogger("ModuleManager");

    private final Map<Class<? extends Module>, Module> fastRegistry = new HashMap<>();
    private final List<Module> modules = new ArrayList<>();
    private final Map<Module, TimingKeys> timingKeys = new IdentityHashMap<>();
    private final Set<String> reportedCallbackFailures = ConcurrentHashMap.newKeySet();
    private record TimingKeys(String tick, String render2d, String render3d) {}

    public void init() {
        register(new HudClientModule());
        register(new ClickGuiModule());
        register(new NotificationsModule());
        register(new dev.guizhou.features.modules.client.InterfaceFeedbackModule());
        register(new dev.guizhou.features.modules.client.GuangxiProbeModule());
        register(new TargetsModule());
        register(new AutoTrapModule());
        register(new AutoTotemModule());
        register(new AutoLogModule());
        register(new AutoSwordModule());
        register(new GuangxiCrystalModule());
        register(new AutoAnchorModule());
        register(new AutoMineModule());
        register(new AutoXPModule());
        register(new VelocityModule());
        register(new SprintModule());
        register(new NoSlowModule());
        register(new NoJumpDelayModule());
        register(new EntityControlModule());
        register(new dev.guizhou.features.modules.movement.NoFallModule());
        register(new AntiHungerModule());
        register(new GuangxiElytraModule());
        register(new dev.guizhou.features.modules.movement.ArmorFlyModule());

        register(new AutoPortalModule());
        register(new FastPortalModule());
        register(new FastuseModule());
        register(new AutoUseModule());
        register(new BlockHighlightModule());
        register(new BreadcrumbsModule());
        register(new BreakIndicatorsModule());
        register(new AutoHorseModule());
        register(new DiscordRPCModule());
        register(new EndermanLookModule());
        register(new CrystalHandModule());
        register(new FullbrightModule());
        register(new NoRenderModule());
        register(new ShadersModule());
        register(new SkyboxModule());
        register(new SeeThroughModule());
        register(new NametagsModule());
        register(new TablistModule());
        register(new LogoutSpotsModule());
        register(new PopEffectsModule());
        register(new ShulkerPreviewModule());
        register(new QuadSupremeModule());
        register(new ViewModelModule());
        register(new MiddleClickExtraModule());
        register(new KeyPotionModule());
        register(new SurroundModule());
        register(new GuangxiPistonModule());
        register(new GuangxiTPModule());
        register(new GuangxiDesyncModule());
        register(new GuangxiElytraChaseModule());
        register(new GuangxiSpearTPModule());
        register(new GuangxiBedModule());
        register(new PearlBlockerModule());
        register(new PhaseModule());
        register(new TrickshotModule());
        register(new MoFuckerModule());
        register(new BomberModule());
        register(new GuangxiMineModule());
        register(new NukerModule());
        register(new ScaffoldModule());
        register(new ReplenishModule());
        register(new InstantRekitModule());
        register(new NoRotateModule());
        register(new dev.guizhou.features.modules.client.AutoReconnectModule());
        register(new dev.guizhou.features.modules.client.VisualRangeModule());
        register(new dev.guizhou.features.modules.render.HoleESPModule());
        register(new HoleFillModule());
        register(new dev.guizhou.features.modules.render.TrajectoriesModule());
        register(new dev.guizhou.features.modules.render.FreecamModule());
        register(new dev.guizhou.features.modules.render.WaypointsModule());
        register(new AutoArmorModule());
        register(new AutoEatModule());
        register(new MiddleClickFriendModule());

        LOGGER.info("Registered {} modules", modules.size());

        for (Module module : modules) {
            GuizhouWare.commandManager.register(new ModuleCommand(module));
        }

        GuizhouWare.configManager.addConfig(this);
    }

    public void register(Module module) {
        dev.guizhou.features.settings.SettingText.fill(module);
        getModules().add(module);
        fastRegistry.put(module.getClass(), module);
        String prefix = "module." + module.getName();
        timingKeys.put(module, new TimingKeys(prefix + ".tick", prefix + ".render2d", prefix + ".render3d"));
    }

    public List<Module> getModules() {
        return modules;
    }

    public Stream<Module> stream() {
        return getModules().stream();
    }

    @SuppressWarnings("unchecked")
    public <T extends Module> T getModuleByClass(Class<T> clazz) {
        return (T) fastRegistry.get(clazz);
    }

    public Module getModuleByName(String name) {
        return stream().filter(m -> m.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    public Module getModuleByDisplayName(String display) {
        return stream().filter(m -> m.getDisplayName().equalsIgnoreCase(display)).findFirst().orElse(null);
    }

    public List<Module> getModulesByCategory(Module.Category category) {
        return stream().filter(m -> m.getCategory() == category).toList();
    }

    public List<Module.Category> getCategories() {
        return Arrays.asList(Module.Category.values());
    }

    public void onLoad() {
        getModules().forEach(Module::onLoad);
    }

    public void onTick() {
        for (Module module : modules) {
            if (!module.isEnabled()) continue;
            long started = ClientDiagnostics.startTimer();
            try { module.onTick(); }
            catch (RuntimeException failure) { reportCallbackFailure(module, "tick", failure); }
            finally { if (started != 0) ClientDiagnostics.endTimer(timingKeys.get(module).tick(), started); }
        }
        for (Module module : modules) {
            if (module.isEnabled() && module.getBindMode() == Module.BindMode.HOLD && !module.getBind().isDown()) {
                module.disable();
            }
        }
    }

    public void onRender2D(Render2DEvent event) {
        for (Module module : modules) {
            if (!module.isEnabled()) continue;
            long started = ClientDiagnostics.startTimer();
            try { module.onRender2D(event); }
            catch (RuntimeException failure) { reportCallbackFailure(module, "render2d", failure); }
            finally { if (started != 0) ClientDiagnostics.endTimer(timingKeys.get(module).render2d(), started); }
        }
    }

    public void onRender3D(Render3DEvent event) {
        for (Module module : modules) {
            if (!module.isEnabled()) continue;
            long started = ClientDiagnostics.startTimer();
            try { module.onRender3D(event); }
            catch (RuntimeException failure) { reportCallbackFailure(module, "render3d", failure); }
            finally { if (started != 0) ClientDiagnostics.endTimer(timingKeys.get(module).render3d(), started); }
        }
    }

    private void reportCallbackFailure(Module module, String callback, RuntimeException failure) {
        String scope = "module." + module.getName() + "." + callback;
        ClientDiagnostics.count(scope + ".failures", 1);
        if (reportedCallbackFailures.add(scope + "." + failure.getClass().getName())) {
            LOGGER.error("Module {} {} callback failed; later modules will continue", module.getName(), callback, failure);
        }
    }

    public void onUnload() {
        getModules().forEach(EVENT_BUS::unregister);
        getModules().forEach(Module::onUnload);
    }

    public void onKeyPressed(int key) {
        if (key <= 0 || mc.screen != null) return;
        stream().filter(module -> module.getBind().getKey() == key).forEach(module -> {
            if (module.getBindMode() == Module.BindMode.HOLD) {
                if (!module.isEnabled()) module.enable();
            } else {
                module.toggle();
            }
        });
    }

    public void onKeyReleased(int key) {
        if (key <= 0) return;
        stream().filter(module -> module.getBind().getKey() == key
                && module.getBindMode() == Module.BindMode.HOLD
                && module.isEnabled())
                .forEach(Module::disable);
    }

    public void onMousePressed(int button) {
        if (mc.screen != null) return;
        int key = Bind.MOUSE_BUTTON_OFFSET + button;
        stream().filter(module -> module.getBind().getKey() == key).forEach(module -> {
            if (module.getBindMode() == Module.BindMode.HOLD) {
                if (!module.isEnabled()) module.enable();
            } else {
                module.toggle();
            }
        });
    }

    public void onMouseReleased(int button) {
        int key = Bind.MOUSE_BUTTON_OFFSET + button;
        stream().filter(module -> module.getBind().getKey() == key
                && module.getBindMode() == Module.BindMode.HOLD
                && module.isEnabled())
                .forEach(Module::disable);
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
        JsonObject imported = dev.guizhou.util.ArmorFlyConfigMigration.migrate(element.getAsJsonObject());
        for (Module module : getModules()) {
            try {
                module.fromJson(imported.get(module.getName()));
            } catch (Throwable e) {
                LOGGER.error("Failed to load module {}", module.getName(), e);
            }
        }
    }

    @Override
    public String getFileName() {
        return "modules.json";
    }
}
