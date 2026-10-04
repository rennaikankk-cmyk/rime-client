package dev.guizhou;

import dev.guizhou.features.GuiMove;
import dev.guizhou.manager.*;
import dev.guizhou.util.BuildConfig;
import dev.guizhou.util.TextUtil;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class GuizhouWare implements ModInitializer, ClientModInitializer {

    // Extra damage keys can arrive in registries from newer servers through a protocol bridge.
    // Native 1.21.1 melee (including mace hits) still uses PLAYER_ATTACK;
    // spear is a 1.21.11 server capability, not a native 1.21.1 item.
    public static final net.minecraft.resources.ResourceKey<net.minecraft.world.damagesource.DamageType> MACE_SMASH_KEY =
            net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DAMAGE_TYPE,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", "mace_smash"));
    public static final net.minecraft.resources.ResourceKey<net.minecraft.world.damagesource.DamageType> SPEAR_KEY =
            net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DAMAGE_TYPE,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", "spear"));

    public static float TIMER = 1f;
    public static final ResourceLocation AUTOHORSE_SOUND_ID = ResourceLocation.fromNamespaceAndPath("guizhouware", "autohorse");
    public static final SoundEvent AUTOHORSE_SOUND = SoundEvent.createVariableRangeEvent(AUTOHORSE_SOUND_ID);

    public static final Logger LOGGER = LogManager.getLogger("GuizhouWare");
    public static ColorManager colorManager;
    public static PositionManager positionManager;
    public static EventManager eventManager;
    public static CommandManager commandManager;
    public static FriendManager friendManager;
    public static EnemyManager enemyManager;
    public static PlayerInfoManager playerInfoManager;
    public static ModuleManager moduleManager;
    public static ConfigManager configManager;
    public static PlacementManager placementManager;
    public static RotationManager rotationManager;
    public static SwapManager swapManager;
    public static TPSCounterService tpsCounterService;
    public static NetworkSanitizer networkSanitizer;
    public static TelemetryManager telemetryManager;
    public static HeartbeatManager heartbeatManager;
    public static GuiMove guiMove;

    @Override
    public void onInitialize() {
        dev.guizhou.util.license.GuardStatus.requireAuthorized();
        LOGGER.info("Pre-initializing {} v{}",
                BuildConfig.NAME, BuildConfig.VERSION);
        Registry.register(BuiltInRegistries.SOUND_EVENT, AUTOHORSE_SOUND_ID, AUTOHORSE_SOUND);
        dev.guizhou.util.render.UiSounds.register();
        configManager = new ConfigManager();
        eventManager = new EventManager();
        positionManager = new PositionManager();
        friendManager = new FriendManager();
        enemyManager = new EnemyManager();
        playerInfoManager = new PlayerInfoManager();
        colorManager = new ColorManager();
        commandManager = new CommandManager();
        moduleManager = new ModuleManager();
        placementManager = new PlacementManager();
        rotationManager = new RotationManager();
        swapManager = new SwapManager();
        tpsCounterService = new TPSCounterService();
        networkSanitizer = new NetworkSanitizer();
        telemetryManager = new TelemetryManager();
        heartbeatManager = new HeartbeatManager();
        guiMove = new GuiMove();

        TextUtil.init();
    }

    @Override
    public void onInitializeClient() {
        dev.guizhou.util.license.GuardStatus.requireAuthorized();
        LOGGER.info("Initializing {}", BuildConfig.NAME);
        dev.guizhou.util.render.PortRenderResources.init();

        long startTime = System.nanoTime();

        eventManager.init();
        rotationManager.init();
        swapManager.init();
        commandManager.init();
        moduleManager.init();
        dev.guizhou.util.inventory.ItemUseTracker.init();
        friendManager.init();
        enemyManager.init();
        playerInfoManager.init();
        tpsCounterService.init();
        networkSanitizer.init();
        heartbeatManager.init();
        guiMove.init();

        configManager.load();
        if (!commandManager.isFunnyVisible()) {
            moduleManager.stream()
                    .filter(m -> m.getCategory() == dev.guizhou.features.modules.Module.Category.FUNNY)
                    .filter(m -> m.isEnabled())
                    .forEach(dev.guizhou.features.modules.Module::disable);
        }
        moduleManager.onLoad();
        new ConfigAutosave().init();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> configManager.save()));

        long endTime = System.nanoTime();

        LOGGER.info("Initialized {} in {}ms",
                BuildConfig.NAME, (endTime - startTime) / 1000000.0);
    }
}
