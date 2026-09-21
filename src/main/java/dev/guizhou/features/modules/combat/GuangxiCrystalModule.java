package dev.guizhou.features.modules.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.impl.render.Render3DEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.client.TargetsModule;
import dev.guizhou.features.modules.world.GuangxiMineModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.PlacementManager;
import dev.guizhou.manager.RotationRequest;
import dev.guizhou.util.DamageSyncTracker;
import dev.guizhou.util.combat.CrystalAttackWindow;
import dev.guizhou.util.combat.CrystalSelectionPolicy;
import dev.guizhou.util.combat.CrystalRetryTracker;
import dev.guizhou.util.combat.ExplosionDamageModel;
import dev.guizhou.util.combat.CombatSnapshotService;
import dev.guizhou.util.diagnostics.ClientDiagnostics;
import dev.guizhou.util.MathUtil;
import dev.guizhou.util.PlaceUtil;
import dev.guizhou.util.PredictUtil;
import dev.guizhou.util.render.RenderUtil;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.inventory.Result;
import dev.guizhou.util.inventory.ResultType;
import dev.guizhou.util.inventory.SwapMode;
import dev.guizhou.util.models.Timer;
import dev.guizhou.util.player.ChatUtil;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.network.chat.Component;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.InteractionHand;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import dev.guizhou.util.EnchantmentUtil;
import dev.guizhou.util.player.EatUtil;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.awt.Color;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

public class GuangxiCrystalModule extends Module {

    private final Setting<Boolean> place         = bool("Place", true).setPage("General");
    private final Setting<Integer> placeDelay    = num("PlaceDelay", 0, 0, 2000).setPage("General");
    private final Setting<Boolean> doBreak       = bool("Break", true).setPage("General");
    private final Setting<Integer> breakDelay    = num("BreakDelay", 0, 0, 2000).setPage("General");
    private final Setting<Double>  minDamage     = num("MinDamage", 4.0, 0.0, 36.0).setPage("General");
    private final Setting<Double>  maxSelfDamage = num("MaxSelfDamage", 4.0, 0.0, 36.0).setPage("General");
    private final Setting<Double>  reserveHp     = num("Reserve", 1.5, 0.0, 10.0).setPage("General");
    private final Setting<Boolean> balance       = bool("Balance", true).setPage("General");
    private final Setting<Double>  balanceOffset = num("BalanceOffset", 0.0, -20.0, 20.0).setPage("General");
    private final Setting<Boolean> antiSurround  = bool("AntiSurround", true).setPage("General");
    private final Setting<Integer> antiSurroundCompletion = num("AntiSurroundCompletion", 70, 0, 100).setPage("General");

    private final Setting<Boolean> damageSync    = bool("DamageSync", false).setPage("General");

    /** FacePlace (Mio): when the target is low HP or nearly stripped of armor, cells the target
     *  itself occupies become placeable and bypass MinDamage — the classic finisher. */
    private final Setting<Boolean> facePlace        = bool("FacePlace", true).setPage("General");
    /** Only for servers that explicitly permit a crystal overlapping a living target. */
    private final Setting<Boolean> entityOverlap = bool("EntityOverlap", false).setPage("General");
    private final Setting<Double>  facePlaceHealth  = num("FacePlaceHealth", 8.0, 0.0, 36.0).setPage("General");
    private final Setting<Integer> facePlaceArmor   = num("FacePlaceArmor", 2, 0, 4).setPage("General");

    /** Wraps the placement UseItemOn in a sneak-state packet sandwich. */
    private final Setting<Boolean> sneakPlace = bool("SneakPlace", false).setPage("General");

    /** WallPlace (Mio): attempt placement on bases without line of sight within a reduced range —
     *  servers that don't validate placement LOS (vanilla, most legacy) accept it. */
    private final Setting<Boolean> wallPlace  = bool("WallPlace", true).setPage("General");
    private final Setting<Double>  wallRange  = num("WallRange", 4.5, 0.5, 6.0).setPage("General");

    private enum AutoSwapMode { NONE, SILENT, INVENTORY }
    private final Setting<AutoSwapMode> autoSwap = mode("AutoSwap", AutoSwapMode.SILENT).setPage("General");
    private final Setting<Boolean> rotate = bool("Rotate", true).setPage("General");

    private final Setting<Boolean> predict          = bool("Predict", true).setPage("Sync");
    private final Setting<Integer> predictTicks     = num("PredictTicks", 4, 0, 20).setPage("Sync");
    private final Setting<Double>  maxMotionY       = num("MaxMotionY", 0.34, 0.0, 2.0).setPage("Sync");
    private final Setting<Integer> selfPredictTicks = num("SelfPredict", 0, 0, 20).setPage("Sync");
    private final Setting<Boolean> hurtSync         = bool("HurtSync", true).setPage("Sync");
    private final Setting<Integer> waitHurt         = num("WaitHurt", 10, 0, 10).setPage("Sync");
    private final Setting<Integer> syncTimeout      = num("SyncTimeout", 500, 0, 2000).setPage("Sync");
    private final Setting<Integer> switchPause      = num("SwitchPause", 100, 0, 1000).setPage("Sync");
    private final Setting<Integer> minAge           = num("MinAge", 0, 0, 20).setPage("Sync");

    /** TpsSync (Mio): stretch tick-based delays by 20/measuredTPS so cadence survives server lag,
     *  and pause placements briefly right after a server rubberband. */
    private final Setting<Boolean> tpsSync = bool("TpsSync", true).setPage("Sync");

    /** GhostHit (Mio HitFactor): after our own place/break, also attack the next entity ids —
     *  the server allocates ids sequentially, so the crystal we just placed is often id+1. */
    private final Setting<Boolean>  ghostHit    = bool("GhostHit", false).setPage("Sync");
    private final Setting<Integer>  ghostFactor = num("GhostFactor", 2, 1, 4).setPage("Sync");

    private final Setting<Boolean> legacy112        = bool("1.12", false).setPage("General");

    private final Setting<Boolean> antiChinese   = bool("AntiChinese", false).setPage("AntiChinese");

    private final Setting<Double>  antiChineseRange = num("Range", 5.0, 1.0, 5.14).setPage("AntiChinese");

    private final Setting<Double>  antiChineseEnemyRange = num("EnemyRange", 8.0, 1.0, 30.0).setPage("AntiChinese");
    private final Setting<Boolean> basePlace          = bool("BasePlace", true).setPage("BasePlace");
    private final Setting<Double>  basePlaceTargetRange = num("BasePlaceEnemyRange", 7.0, 0.1, 15.0).setPage("BasePlace");
    private final Setting<Double>  basePlaceMinDamage = num("BasePlaceMinDamage", 5.0, 0.0, 36.0).setPage("BasePlace");
    private final Setting<Double>  baseOverrideMax    = num("BaseOverrideMax", 8.0, 0.0, 36.0).setPage("BasePlace");
    private final Setting<Double>  baseMaxSelf        = num("BaseMaxSelfDamage", 12.0, 0.0, 36.0).setPage("BasePlace");
    private final Setting<Boolean> baseBalance        = bool("BaseBalance", true).setPage("BasePlace");
    private final Setting<Boolean> detectMining       = bool("DetectMining", true).setPage("BasePlace");
    private final Setting<Boolean> debug         = bool("Debug", false).setPage("Extra");
    private final Setting<Boolean> packetLog     = bool("PacketLog", false).setPage("Extra");

    private final Setting<RenderMode> renderMode = mode("Render", RenderMode.GRADIENT).setPage("Render");
    private final Setting<Color>   sideColor     = color("SideColor", 130, 80, 255, 45).setPage("Render");
    private final Setting<Color>   lineColor     = color("LineColor", 130, 80, 255, 255).setPage("Render");
    private final Setting<Float>   lineWidth     = num("LineWidth", 1.5f, 0.5f, 5.0f).setPage("Render");
    private final Setting<Double>  gradientHeight = num("GradientHeight", 0.7, 0.0, 1.0).setPage("Render");
    private final Setting<Integer> renderTime    = num("RenderTime", 10, 1, 40).setPage("Render");
    private final Setting<Integer> smoothness    = num("Smoothness", 10, 1, 50).setPage("Render");
    private final Setting<Color>   pulseColor    = color("PulseColor", 130, 80, 255, 150).setPage("Render");
    private final Setting<Double>  pulseSpeed    = num("PulseSpeed", 2.0, 0.2, 6.0).setPage("Render");
    private final Setting<Double>  pulseExpand   = num("PulseExpand", 0.35, 0.0, 1.0).setPage("Render");

    private BlockPos renderPos = null;
    private long     renderStartMs = 0L;
    private AABB     smoothBox = null;

    private enum RenderMode { NONE, GRADIENT, GRADIENT_SMOOTH, BOX_SMOOTH, PULSE }

    private enum RangePreset { B2B2T, VANILLA, CUSTOM }

    private final Setting<RangePreset> rangePreset = mode("RangePreset", RangePreset.B2B2T).setPage("General");
    private final Setting<Double> placeRange = num("PlaceRange", 6.0, 0.0, 6.0).setPage("General");
    private final Setting<Double> breakRange = num("BreakRange", 3.0, 0.0, 6.0).setPage("General");
    private static final double  ARMOR_BALANCE  = 0.20;
    private static final boolean NO_SELF_POP    = true; // retained for config compatibility; Reserve now drives the margin

    private final Setting<Boolean> blockDestruction = bool("BlockDestruction", true).setPage("General");
    private final Setting<Boolean> inhibit = bool("Inhibit", true).setPage("General");
    private final Setting<Integer> attackLimit = num("AttackLimit", 3, 1, 10).setPage("General");
    private final Setting<Integer> breakTimeout = num("BreakTimeout", 100, 0, 1000).setPage("General");
    private final Setting<Integer> retryReset = num("RetryReset", 500, 100, 5000).setPage("General");
    private final Setting<Integer> placeTimeout = num("PlaceTimeout", 100, 0, 1000).setPage("General");
    private final Setting<Integer> reactiveBudget = num("ReactiveScans", 2, 1, 8).setPage("General");

    /** Same-spot rhythm lock yields once another candidate beats the locked spot by this much. */
    private static final float LOCK_RELEASE_MARGIN = 2.0f;

    private static final long CRYSTAL_TRACK_MS = 1750;

    private final Timer placeTimer  = new Timer();
    private final Timer breakTimer  = new Timer();
    private final Timer switchTimer = new Timer();
    private final CrystalAttackWindow attackWindow = new CrystalAttackWindow();
    private final CrystalRetryTracker attackRetries = new CrystalRetryTracker();
    private int reactiveTick = Integer.MIN_VALUE;
    private int reactiveScans;
    private boolean reactiveScheduled;
    private BlockPos reactivePosition;
    private int reactiveScheduledTick;
    private List<TargetCache> decisionTargets = List.of();

    private int lastCarriedSlot = -1;
    private long lastBreakMs = 0L;
    private float sentYaw;
    private float sentPitch;
    private boolean sentRotationKnown;
    private PacketEvent.Send pendingRotation;
    private PacketEvent.Send pendingAttack;
    private boolean sendingCrystalAttack;
    private final java.util.concurrent.atomic.AtomicInteger ownSwapPackets =
            new java.util.concurrent.atomic.AtomicInteger(0);

    private volatile long lifecycleGeneration;
    private ClientLevel sessionLevel;
    private LocalPlayer sessionPlayer;

    private record SelfCache(Vec3 pos, AABB box, Vec3 currentPos, AABB currentBox,
                             float health, float armor, float toughness,
                             int epf, float resistanceMultiplier, Difficulty difficulty) {}

    private SelfCache selfCache = null;
    // tick-scoped entity snapshot: candidate-cell occupancy filters in memory instead of
    // one world query per candidate (O(candidates) queries -> O(1) per decision cycle)
    private volatile List<Entity> entitySnapshot = List.of();
    private volatile int entitySnapshotTick = -1;
    private record SelfDamageKey(Vec3 center, BlockPos phantomBase) {}
    private final Map<SelfDamageKey, Float> selfDamageCache = new HashMap<>();
    private record TargetDamageKey(TargetCache target, Vec3 source, BlockPos phantom) {}
    private final Map<TargetDamageKey, Float> targetDamageCache = new HashMap<>();
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private double placeRangeValue() {
        return switch (rangePreset.getValue()) {
            case B2B2T -> 6.0;
            case VANILLA -> 4.5;
            case CUSTOM -> placeRange.getValue();
        };
    }

    private double breakRangeValue() {
        return switch (rangePreset.getValue()) {
            case B2B2T, VANILLA -> 3.0;
            case CUSTOM -> breakRange.getValue();
        };
    }

    /** GuangxiProbe applies measured server boundaries here (preset -> CUSTOM). */
    public void applyCalibratedRanges(double place, double breakR) {
        rangePreset.setValue(RangePreset.CUSTOM);
        placeRange.setValue(Math.max(0.5, Math.min(6.0, place)));
        breakRange.setValue(Math.max(0.5, Math.min(6.0, breakR)));
    }

    private double placeRangeSq() {
        double r = placeRangeValue();
        return r * r;
    }

    private double breakRangeSq() {
        double r = breakRangeValue();
        return r * r;
    }

    // ------------------------------------------------------------ Mio-derived timing helpers

    /** Scales a tick-based delay window by measured server TPS (1.0 at 20 TPS). */
    private long tpsMs(long baseMs) {
        if (!tpsSync.getValue()) return baseMs;
        return (long) (baseMs * GuizhouWare.tpsCounterService.delayScale());
    }

    private long pendingWindowMs() {
        return tpsMs(placeTimeout.getValue());
    }

    /** True right after a server rubberband — placing into an unconfirmed world wastes blocks. */
    private boolean rubberbandQuiet() {
        return !tpsSync.getValue() || !GuizhouWare.tpsCounterService.isRubberbandRecent();
    }

    // ------------------------------------------------------------ GhostHit (Mio HitFactor)

    private int lastMaxEntityId = -1;
    private int diagGhost = 0;

    /** Raw-buffer attack by entity id — the STREAM_CODEC path lets us hit ids the server has
     *  not sent us yet, because normal attack() needs a live client-side Entity. */
    private void sendGhostAttack(int entityId) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
        if (mc.getConnection() == null || weaponControlBusy()) return;
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            buf.writeVarInt(entityId);
            buf.writeVarInt(1); // ActionType.ATTACK
            buf.writeBoolean(mc.player.isShiftKeyDown());
            mc.getConnection().send(ServerboundInteractPacket.STREAM_CODEC.decode(buf));
            diagGhost++;
        } finally {
            buf.release();
        }
    }

    /** Attacks the {@code factor-1} entity ids after {@code anchorId}: the server hands out ids
     *  sequentially, so the crystal our own placement is about to spawn is usually anchorId+1. */
    private void sendGhostAttacksAfter(int anchorId) {
        if (!ghostHit.getValue() || ghostFactor.getValue() <= 1 || anchorId <= 0) return;
        int n = Math.min(ghostFactor.getValue(), 4);
        for (int i = 1; i < n; i++) {
            sendGhostAttack(anchorId + i);
        }
    }

    private final Long2LongOpenHashMap crystalPlaces = new Long2LongOpenHashMap();

    private float lastBestDamage    = 0;

    private final java.util.HashMap<String, int[]> diagSendCounts = new java.util.HashMap<>();
    private long diagWindowStart  = 0L;
    private int  diagWindowSends  = 0;
    private int  diagPeakWindow   = 0;
    private long diagLifetimeSends = 0L;
    private long diagLastTickSends = 0L;
    private int  diagPeakTickSends = 0;
    private int  diagPlaceAttempt, diagPlaceSent, diagBreakSent, diagInstaBreak;

    private double lastCalcMs = 0;

    private int lastReactorPlaceTick = -1;
    private BlockPos lastPlaceAir = null;
    private int placeDiagCounter = 0;

    private final ExposureContext exposureCtx = new ExposureContext();

    private final ArrayList<PlaceCandidate> candidates = new ArrayList<>();

    private final Set<Integer> deadIds = ConcurrentHashMap.newKeySet();

    private final Set<BlockPos> seeThrough = new HashSet<>();

    private int cachedChunkX = Integer.MIN_VALUE;
    private int cachedChunkZ = Integer.MIN_VALUE;
    private ChunkAccess cachedChunk;

    private final PlacementManager.PlacementListener placementListener = this::onBlockUpdate;

    public GuangxiCrystalModule() {
        super("GuangxiCrystal", "Automatically places and breaks end crystals.", Category.COMBAT);
        placeDelay.setVisibility(v -> place.getValue());
        breakDelay.setVisibility(v -> doBreak.getValue());
        basePlaceTargetRange.setVisibility(v -> basePlace.getValue());
        basePlaceMinDamage.setVisibility(v -> basePlace.getValue());
        antiChineseRange.setVisibility(v -> antiChinese.getValue());
        antiChineseEnemyRange.setVisibility(v -> antiChinese.getValue());
        placeRange.setVisibility(v -> rangePreset.getValue() == RangePreset.CUSTOM);
        breakRange.setVisibility(v -> rangePreset.getValue() == RangePreset.CUSTOM);
        predictTicks.setVisibility(v -> predict.getValue());
        maxMotionY.setVisibility(v -> predict.getValue());
        selfPredictTicks.setVisibility(v -> predict.getValue());
        waitHurt.setVisibility(v -> hurtSync.getValue());
        syncTimeout.setVisibility(v -> hurtSync.getValue());
        balanceOffset.setVisibility(v -> balance.getValue());
        facePlaceHealth.setVisibility(v -> facePlace.getValue());
        facePlaceArmor.setVisibility(v -> facePlace.getValue());
        wallRange.setVisibility(v -> wallPlace.getValue());
        ghostFactor.setVisibility(v -> ghostHit.getValue());
    }

    @Override
    public void onEnable() {
        lifecycleGeneration++;
        sessionLevel = mc.level;
        sessionPlayer = mc.player;
        resetSessionState();
        GuizhouWare.placementManager.addListener(placementListener);
    }

    private void resetSessionState() {
        crystalPlaces.clear();
        deadIds.clear();
        attackWindow.clear();
        attackRetries.clear();
        reactiveTick = Integer.MIN_VALUE;
        reactiveScans = 0;
        reactiveScheduled = false;
        reactivePosition = null;
        decisionTargets = List.of();
        placeTimer.reset();
        breakTimer.reset();
        switchTimer.reset();
        lastBreakMs = 0L;
        sentRotationKnown = false;
        pendingRotation = null;
        pendingAttack = null;
        sendingCrystalAttack = false;
        lastBestDamage = 0;
        lastCalcMs = 0;
        lastReactorPlaceTick = -1;
        lastPlaceAir = null;
        lastMaxEntityId = -1;
        lastCarriedSlot = -1;
        ownSwapPackets.set(0);
        selfCache = null;
        entitySnapshot = List.of();
        entitySnapshotTick = -1;
        selfDamageCache.clear();
        targetDamageCache.clear();
        cachedChunk = null;
        cachedChunkX = cachedChunkZ = Integer.MIN_VALUE;
        seeThrough.clear();
        miningCheck.clear();
        candidates.clear();
        explodeTimes.clear();
        renderPos = null;
        smoothBox = null;
        resetDiag();
    }

    private void ensureSession() {
        if (sessionLevel == mc.level && sessionPlayer == mc.player) return;
        sessionLevel = mc.level;
        sessionPlayer = mc.player;
        resetSessionState();
    }

    @Override
    public void onDisable() {
        lifecycleGeneration++;
        GuizhouWare.placementManager.removeListener(placementListener);
        resetSessionState();
        sessionLevel = null;
        sessionPlayer = null;
    }

    private void markRender(BlockPos crystalPos) {
        if (renderMode.getValue() == RenderMode.NONE) return;
        renderPos = crystalPos;
        renderStartMs = System.currentTimeMillis();
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (nullCheck() || renderMode.getValue() == RenderMode.NONE || renderPos == null) return;

        long elapsed = System.currentTimeMillis() - renderStartMs;
        long life = renderTime.getValue() * 50L;
        if (elapsed > life) {
            renderPos = null;
            smoothBox = null;
            return;
        }

        switch (renderMode.getValue()) {
            case GRADIENT        -> drawGradient(event, false);
            case GRADIENT_SMOOTH -> drawGradient(event, true);
            case BOX_SMOOTH      -> drawBoxSmooth(event);
            case PULSE           -> drawPulse(event, elapsed, life);
            default -> { }
        }
    }

    private void updateSmoothBox(AABB target) {
        if (smoothBox == null) {
            smoothBox = target;
        } else {
            double f = 1.0 / smoothness.getValue();
            smoothBox = new AABB(
                    lerp(smoothBox.minX, target.minX, f), lerp(smoothBox.minY, target.minY, f), lerp(smoothBox.minZ, target.minZ, f),
                    lerp(smoothBox.maxX, target.maxX, f), lerp(smoothBox.maxY, target.maxY, f), lerp(smoothBox.maxZ, target.maxZ, f));
        }
    }

    private void drawBoxSmooth(Render3DEvent event) {
        updateSmoothBox(new AABB(renderPos.below()));
        RenderUtil.drawBoxFilled(event.getMatrix(), smoothBox, sideColor.getValue());
        RenderUtil.drawBox(event.getMatrix(), smoothBox, lineColor.getValue(), lineWidth.getValue());
    }

    private void drawGradient(Render3DEvent event, boolean smooth) {
        Color side = sideColor.getValue();
        Color line = lineColor.getValue();
        Color clear = new Color(side.getRed(), side.getGreen(), side.getBlue(), 0);
        double height = gradientHeight.getValue();
        float lw = lineWidth.getValue();

        BlockPos basePos = renderPos.below();

        double x1, z1, x2, z2, yTop;
        if (smooth) {
            updateSmoothBox(new AABB(basePos));
            x1 = smoothBox.minX; z1 = smoothBox.minZ;
            x2 = smoothBox.maxX; z2 = smoothBox.maxZ;
            yTop = smoothBox.maxY;
        } else {
            x1 = basePos.getX(); z1 = basePos.getZ();
            x2 = x1 + 1;         z2 = z1 + 1;
            yTop = basePos.getY() + 1;
        }
        double yBottom = yTop - height;
        var m = event.getMatrix();

        RenderUtil.quadHorizontal(m, x1, z1, x2, z2, yTop, side);
        RenderUtil.gradientQuadVertical(m, x1, z1, x2, z1, yTop, yBottom, side, clear);
        RenderUtil.gradientQuadVertical(m, x2, z1, x2, z2, yTop, yBottom, side, clear);
        RenderUtil.gradientQuadVertical(m, x2, z2, x1, z2, yTop, yBottom, side, clear);
        RenderUtil.gradientQuadVertical(m, x1, z2, x1, z1, yTop, yBottom, side, clear);

        RenderUtil.drawLine(new Vec3(x1, yTop, z1), new Vec3(x2, yTop, z1), line, lw);
        RenderUtil.drawLine(new Vec3(x2, yTop, z1), new Vec3(x2, yTop, z2), line, lw);
        RenderUtil.drawLine(new Vec3(x2, yTop, z2), new Vec3(x1, yTop, z2), line, lw);
        RenderUtil.drawLine(new Vec3(x1, yTop, z2), new Vec3(x1, yTop, z1), line, lw);

        RenderUtil.drawLine(new Vec3(x1, yTop, z1), new Vec3(x1, yBottom, z1), line, lw);
        RenderUtil.drawLine(new Vec3(x2, yTop, z1), new Vec3(x2, yBottom, z1), line, lw);
        RenderUtil.drawLine(new Vec3(x2, yTop, z2), new Vec3(x2, yBottom, z2), line, lw);
        RenderUtil.drawLine(new Vec3(x1, yTop, z2), new Vec3(x1, yBottom, z2), line, lw);
    }

    private void drawPulse(Render3DEvent event, long elapsed, long life) {
        double fade = 1.0 - (double) elapsed / life;
        double phase = elapsed / 1000.0 * pulseSpeed.getValue();
        double pulse = (Math.sin(phase * Math.PI * 2.0) + 1.0) * 0.5;
        double grow = pulse * pulseExpand.getValue();

        Color base = pulseColor.getValue();
        Color fill = new Color(base.getRed(), base.getGreen(), base.getBlue(),
                Math.clamp((int) (base.getAlpha() * fade), 0, 255));
        Color line = new Color(base.getRed(), base.getGreen(), base.getBlue(),
                Math.clamp((int) (255 * fade), 0, 255));

        AABB box = new AABB(renderPos).inflate(grow);
        RenderUtil.drawBoxFilled(event.getMatrix(), box, fill);
        RenderUtil.drawBox(event.getMatrix(), box, line, lineWidth.getValue());
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    @Subscribe
    private void onPacketReceive(PacketEvent.Receive event) {
        var packet = event.getPacket();
        if (!(packet instanceof ClientboundEntityEventPacket)
                && !(packet instanceof ClientboundAddEntityPacket)
                && !(packet instanceof ClientboundExplodePacket)
                && !(packet instanceof ClientboundRemoveEntitiesPacket)
                && !(packet instanceof ClientboundPlayerPositionPacket)) return;
        executeForSession(() -> handleIncomingPacket(packet));
    }

    private void executeForSession(Runnable action) {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        long generation = lifecycleGeneration;
        if (level == null || player == null) return;
        mc.execute(() -> {
            if (!isEnabled() || lifecycleGeneration != generation
                    || mc.level != level || mc.player != player) return;
            ensureSession();
            action.run();
        });
    }

    private void handleIncomingPacket(net.minecraft.network.protocol.Packet<?> packet) {
        if (packet instanceof ClientboundRemoveEntitiesPacket removed) {
            entitySnapshotTick = -1;
            entitySnapshot = List.of();
            for (int id : removed.getEntityIds()) attackRetries.removed(id);
            return;
        }
        if (packet instanceof ClientboundPlayerPositionPacket) {
            sentRotationKnown = false;
            pendingRotation = null;
            return;
        }
        if (packet instanceof ClientboundExplodePacket) {
            long now = System.currentTimeMillis();
            explodeTimes.addLast(now);
            while (!explodeTimes.isEmpty() && now - explodeTimes.peekFirst() > 1000) explodeTimes.removeFirst();
            return;
        }
        if (packet instanceof ClientboundEntityEventPacket pkt) {
            if (pkt.getEventId() != 3) return;

            var e = pkt.getEntity(mc.level);
            if (e != null) deadIds.add(e.getId());
            return;
        }

        if (packet instanceof ClientboundAddEntityPacket pkt) {
            entitySnapshotTick = -1;
            entitySnapshot = List.of();
            attackRetries.removed(pkt.getId());
            if (pkt.getId() > lastMaxEntityId) lastMaxEntityId = pkt.getId();
            instaGates[9]++; // any spawn packet reached this listener
            if (packetLog.getValue() && pkt.getType() == EntityType.END_CRYSTAL) {
                GuizhouWare.LOGGER.info("[Sniff] spawn id={} pos=({},{},{})",
                        pkt.getId(),
                        String.format("%.1f", pkt.getX()), String.format("%.1f", pkt.getY()),
                        String.format("%.1f", pkt.getZ()));
            }
            onCrystalSpawn(pkt);
        }
    }

    @Subscribe(priority = Integer.MIN_VALUE)
    private void onPacketSendDiag(PacketEvent.Send event) {
        var packet = event.getPacket();
        if (sendingCrystalAttack && mc.isSameThread() && packet instanceof ServerboundInteractPacket) {
            pendingAttack = event;
        }
        if (packet instanceof ServerboundMovePlayerPacket move && move.hasRotation()) {
            executeForSession(() -> {
                acceptPendingRotation();
                pendingRotation = event;
            });
        }
        boolean ownSwap = packet instanceof ServerboundSetCarriedItemPacket
                && ownSwapPackets.getAndUpdate(v -> v > 0 ? v - 1 : 0) > 0;
        if (!(packet instanceof ServerboundSetCarriedItemPacket) && !packetLog.getValue()) return;
        executeForSession(() -> {
            if (!event.isCancelled()) recordOutgoingPacket(packet, ownSwap);
        });
    }

    private void acceptPendingRotation() {
        PacketEvent.Send event = pendingRotation;
        pendingRotation = null;
        if (event != null && !event.isCancelled() && event.getPacket() instanceof ServerboundMovePlayerPacket move) {
            sentYaw = move.getYRot(0.0F);
            sentPitch = move.getXRot(0.0F);
            sentRotationKnown = Float.isFinite(sentYaw) && Float.isFinite(sentPitch);
        }
    }

    private void recordOutgoingPacket(net.minecraft.network.protocol.Packet<?> packet, boolean ownSwap) {
        if (packet instanceof ServerboundSetCarriedItemPacket pkt) {
            if (ownSwap) {
                // Our own placement swap — must not lock out breaking (placeDelay=0 spam
                // would otherwise keep the switch-pause permanently engaged).
                lastCarriedSlot = pkt.getSlot();
            } else if (pkt.getSlot() != lastCarriedSlot) {
                lastCarriedSlot = pkt.getSlot();
                switchTimer.reset();
            }
        }
        // Packet sniffer: logs every placement exactly as it leaves the client, so a
        // working placement (RusherHack / manual) can be diffed against ours field by field.
        if (packetLog.getValue() && packet instanceof ServerboundUseItemOnPacket use) {
            var hit = use.getHitResult();
            Vec3 loc = hit.getLocation();
            GuizhouWare.LOGGER.info("[Sniff] use pos={} face={} cur=({},{},{}) hand={} seq={}",
                    hit.getBlockPos().toShortString(), hit.getDirection(),
                    String.format("%.3f", loc.x - hit.getBlockPos().getX()),
                    String.format("%.3f", loc.y - hit.getBlockPos().getY()),
                    String.format("%.3f", loc.z - hit.getBlockPos().getZ()),
                    use.getHand(), use.getSequence());
        }
        if (!packetLog.getValue()) return;
        diagSendCounts.computeIfAbsent(packet.getClass().getSimpleName(), k -> new int[1])[0]++;
        diagWindowSends++;
        diagLifetimeSends++;
        long now = System.currentTimeMillis();
        if (diagWindowStart == 0L) diagWindowStart = now;
        if (now - diagWindowStart >= 1000L) flushDiag(now);
    }

    @Subscribe(priority = 100)
    private void onDiagTickBoundary(PreTickEvent event) {
        if (!packetLog.getValue()) return;
        int tickDelta = (int) (diagLifetimeSends - diagLastTickSends);
        diagLastTickSends = diagLifetimeSends;
        if (tickDelta > diagPeakTickSends) diagPeakTickSends = tickDelta;
    }

    private void flushDiag(long now) {
        if (diagWindowSends > diagPeakWindow) diagPeakWindow = diagWindowSends;
        StringBuilder breakdown = new StringBuilder();
        diagSendCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .forEach(e -> breakdown.append(e.getKey()).append('=').append(e.getValue()[0]).append(' '));
        GuizhouWare.LOGGER.info(
                "[ACDiag] {}ms sends={} peakTick={} peakSec={} | place att/sent={}/{} break={} insta={} ghost={} tps={} gates[delay={} age={} switch={} eat={} mace={} range={} self={} rot={} sync={} any={} crys={}] | {}",
                now - diagWindowStart, diagWindowSends, diagPeakTickSends, diagPeakWindow,
                diagPlaceAttempt, diagPlaceSent, diagBreakSent, diagInstaBreak, diagGhost,
                String.format("%.1f", GuizhouWare.tpsCounterService.getAverageTPS()),
                instaGates[0], instaGates[1], instaGates[2], instaGates[3], instaGates[4],
                instaGates[5], instaGates[6], instaGates[7], instaGates[8],
                instaGates[9], instaGates[10],
                breakdown.toString().trim());
        diagSendCounts.clear();
        diagWindowSends = 0;
        diagPeakTickSends = 0;
        diagPlaceAttempt = diagPlaceSent = diagBreakSent = diagInstaBreak = 0;
        diagGhost = 0;
        java.util.Arrays.fill(instaGates, 0);
        diagWindowStart = now;
    }

    private void resetDiag() {
        diagSendCounts.clear();
        diagWindowStart = 0L;
        diagWindowSends = 0;
        diagPeakWindow = 0;
        diagLifetimeSends = 0L;
        diagLastTickSends = 0L;
        diagPeakTickSends = 0;
        diagPlaceAttempt = diagPlaceSent = diagBreakSent = diagInstaBreak = 0;
        diagGhost = 0;
        java.util.Arrays.fill(instaGates, 0);
    }

    private void onCrystalSpawn(ClientboundAddEntityPacket pkt) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
        instaGates[10] += pkt.getType() == EntityType.END_CRYSTAL ? 1 : 0; // crystal-typed spawns seen
        if (!doBreak.getValue() || breakDelay.getValue() != 0) { if (pkt.getType() == EntityType.END_CRYSTAL) instaGates[0]++; return; }
        if (pkt.getType() != EntityType.END_CRYSTAL) return;
        if (nullCheck() || mc.player.isDeadOrDying()) return;
        if (!rubberbandQuiet()) return;
        if (minAge.getValue() > 0) { instaGates[1]++; return; } // fresh spawn is tick 0; tick-loop break respects minAge
        if (!switchTimer.passedMs(tpsMs(switchPause.getValue()))) { instaGates[2]++; return; }
        if (!attackWindow.canAttack(pkt.getId(), mc.player.tickCount)) return;

        if (weaponControlBusy()) { instaGates[3]++; return; }
        AutoSwordModule sword = GuizhouWare.moduleManager.getModuleByClass(AutoSwordModule.class);
        if (sword != null && sword.isEnabled() && sword.isMaceAttackReady()) { instaGates[4]++; return; }

        EndCrystal crystal = new EndCrystal(EntityType.END_CRYSTAL, mc.level);
        crystal.setPos(pkt.getX(), pkt.getY(), pkt.getZ());
        crystal.setId(pkt.getId());

        AABB bb = crystal.getBoundingBox();
        Vec3 reachEye = bestReachEye(bb);
        if (sqDistToBox(reachEye, bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ)
                > breakRangeSq()) { instaGates[5]++; return; }

        acceptPendingRotation();
        if (!sentRotationKnown || !canBreakCrystal(crystal, sentYaw, sentPitch)) { instaGates[7]++; return; }
        ensureSelfCache();
        updateSeeThrough();
        float playerHealth = selfNow().health();
        if (!selfDamageOk(crystal.position(), null, maxSelfDamage.getValue(), playerHealth)) { instaGates[6]++; return; }

        TargetsModule targets = GuizhouWare.moduleManager.getModuleByClass(TargetsModule.class);
        List<TargetCache> potentialTargets = collectTargets(targets, breakRangeValue() + 14.0);
        float damage = breakDamage(potentialTargets, crystal.position(), false);
        if (damage <= 0.0F || !balanceOk(damage, crystal.position(), null)) { instaGates[8]++; return; }
        if (!sendCrystalAttack(crystal, true)) return;
        lastBestDamage = damage;
        mc.player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        // Alternation means our next placement is imminent: pre-attack the ids it will spawn at.
        sendGhostAttacksAfter(pkt.getId());
    }

    /** Per-second insta-break gate rejections: [delay,minAge,switch,eat,mace,range,self,rot,sync, anySpawn, crystalSpawn]. */
    private final int[] instaGates = new int[11];

    private void onBlockUpdate(BlockPos pos, boolean nowAir) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
        if (!nowAir || nullCheck()) return;
        double range = placeRangeValue() + 2.0;
        if (mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > range * range) return;
        reactivePosition = pos.immutable();
        if (reactiveScheduled) { ClientDiagnostics.count("combat.GuangxiCrystal.reactive.coalesced", 1); return; }
        reactiveScheduled = true;
        reactiveScheduledTick = mc.player.tickCount;
        long generation = lifecycleGeneration;
        var level = mc.level;
        var player = mc.player;
        // schedule always queues, unlike execute on the client thread. Already queued block updates
        // therefore merge into one scan; crystal-spawn instant attacks keep their separate direct path.
        mc.schedule(() -> {
            if (!isEnabled() || generation != lifecycleGeneration || mc.level != level || mc.player != player) return;
            BlockPos changed = reactivePosition;
            reactiveScheduled = false;
            reactivePosition = null;
            if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
            if (changed == null || mc.player.tickCount - reactiveScheduledTick > 1) return;
            long timer = ClientDiagnostics.startTimer();
            try { scanBlockUpdate(changed, true); }
            finally { ClientDiagnostics.endTimer("combat.GuangxiCrystal.reactive.scan", timer); }
        });
    }

    private void scanBlockUpdate(BlockPos pos, boolean nowAir) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
        if (!nowAir) return;
        if (nullCheck() || mc.player.isDeadOrDying()) return;
        ensureSession();
        if (!rubberbandQuiet()) return;
        if (weaponControlBusy()) return;

        AutoSwordModule sword = GuizhouWare.moduleManager.getModuleByClass(AutoSwordModule.class);
        if (sword != null && sword.isEnabled() && sword.isMaceAttackReady()) return;

        double cutoff = (placeRangeValue() + 2.0) * (placeRangeValue() + 2.0);
        if (mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > cutoff) return;

        if (reactiveTick != mc.player.tickCount) {
            reactiveTick = mc.player.tickCount;
            reactiveScans = 0;
        }
        if (reactiveScans >= reactiveBudget.getValue()) return;

        updateSeeThrough();

        boolean canBreak = doBreak.getValue() && breakTimer.passedMs(tpsMs(breakDelay.getValue()))
                && switchTimer.passedMs(tpsMs(switchPause.getValue()));
        boolean canPlace = place.getValue() && placeTimer.passedMs(tpsMs(placeDelay.getValue()))
                && canSupplyCrystal() && mc.player.tickCount != lastReactorPlaceTick;
        if (!canBreak && !canPlace) return;
        reactiveScans++;
        ensureSelfCache();
        refreshEntitySnapshot();

        TargetsModule targets = GuizhouWare.moduleManager.getModuleByClass(TargetsModule.class);
        double scanRadius = placeRangeValue() + 12.0;
        List<TargetCache> potentialTargets = collectTargets(targets, scanRadius);

        if (canBreak) {
            EndCrystal target = findBestCrystal(potentialTargets);
            if (target != null && breakCrystal(target)) {
                return;
            }
        }

        if (!canPlace) return;
        PlaceTarget placeTarget = findBestPlace(potentialTargets);
        if (placeTarget != null && doPlace(placeTarget)) {
            lastReactorPlaceTick = mc.player.tickCount;
            placeTimer.reset();
        }
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) { dev.guizhou.manager.HeartbeatManager.notifyDeniedOnce(); return; }
if (nullCheck() || mc.player.isDeadOrDying()) return;
        ensureSession();
        if (!rubberbandQuiet()) {
            lastBestDamage = 0;
            return;
        }
        ensureSelfCache();
        refreshEntitySnapshot();
        ownSwapPackets.set(0);

        if (weaponControlBusy()) {
            lastBestDamage = 0;
            return;
        }

        AutoSwordModule sword = GuizhouWare.moduleManager.getModuleByClass(AutoSwordModule.class);
        if (sword != null && sword.isEnabled() && sword.isMaceAttackReady()) {
            lastBestDamage = 0;
            return;
        }

        ageCrystalPlaces();
        updateSeeThrough();

        boolean antiChineseReady = antiChinese.getValue()
                && enemyNearby(antiChineseEnemyRange.getValue());

        boolean breakReady = breakTimer.passedMs(tpsMs(breakDelay.getValue()))
                && switchTimer.passedMs(tpsMs(switchPause.getValue()));
        boolean canBreak = doBreak.getValue() && breakReady;
        boolean placeReady = placeTimer.passedMs(tpsMs(placeDelay.getValue()));
        boolean canPlace = place.getValue() && placeReady && canSupplyCrystal();
        boolean canBase = basePlace.getValue() && placeReady && canSupplyObsidian();
        if (!canBreak && !canPlace && !canBase && !antiChineseReady) return;

        long calculationTimer = ClientDiagnostics.startTimer();
        long calcStart = System.nanoTime();
        TargetsModule targets = GuizhouWare.moduleManager.getModuleByClass(TargetsModule.class);
        double scanRadius = Math.max(breakRangeValue(), placeRangeValue()) + 12.0
                + (canBase ? placeRangeValue() : 0.0);
        if (canBase) scanRadius = Math.max(scanRadius, basePlaceTargetRange.getValue());
        List<TargetCache> potentialTargets = canBreak || canPlace || canBase
                ? collectTargets(targets, scanRadius) : List.of();

        boolean broke = false;
        if (canBreak) {
            EndCrystal target = findBestCrystal(potentialTargets);
            if (target != null && breakCrystal(target)) {
                broke = true;
            }
        }

        if (!broke && antiChineseReady && breakReady) {
            EndCrystal acTarget = findAntiChineseCrystal();
            if (acTarget != null) breakCrystal(acTarget);
        }

        // World collision, entity lists, and mining state are owned by the client thread.
        PlaceTarget crystalTarget = canPlace ? findBestPlace(potentialTargets) : null;
        BasePlaceTarget baseTarget = canBase
                ? findBestBasePlace(findClosestBaseTarget(potentialTargets), potentialTargets) : null;

        lastCalcMs = (System.nanoTime() - calcStart) / 1_000_000.0;
        ClientDiagnostics.endTimer("combat.GuangxiCrystal.scan", calculationTimer);
        if (debug.getValue()) {
            ChatUtil.sendPersistent("autocrystal:calc",
                    Component.literal(String.format("AutoCrystal calc: %.3f ms", lastCalcMs)));
        }

        boolean baseWins = baseTarget != null
                && (crystalTarget == null
                    || (crystalTarget.damage <= baseOverrideMax.getValue()
                        && baseTarget.damage > crystalTarget.damage));

        boolean placed = false;
        if (baseWins) {

            boolean basePlaced = doBasePlace(baseTarget);
            if (basePlaced) {
                placeTimer.reset();
                placed = true;
                if (canPlace) {
                    doPlace(new PlaceTarget(baseTarget.base, baseTarget.damage), true);
                }
            }
        }
        if (!placed && crystalTarget != null) {
            placed = doPlace(crystalTarget);
            if (placed) placeTimer.reset();
        }

        if (!placed && antiChineseReady && canPlace) {
            PlaceTarget acPlace = findAntiChinesePlace();
            if (acPlace != null && doPlace(acPlace)) {
                placeTimer.reset();
            }
        }
    }

    private boolean canSupplyCrystal() {
        if (mc.player.getMainHandItem().is(Items.END_CRYSTAL)
                || mc.player.getOffhandItem().is(Items.END_CRYSTAL)) return true;
        if (autoSwap.getValue() == AutoSwapMode.NONE) return false;
        return InventoryUtil.find(Items.END_CRYSTAL, autoSwap.getValue() == AutoSwapMode.INVENTORY
                ? InventoryUtil.FULL_SCOPE : InventoryUtil.HOTBAR_SCOPE).found();
    }

    private boolean canSupplyObsidian() {
        Result obs = InventoryUtil.find(Items.OBSIDIAN, autoSwap.getValue() == AutoSwapMode.INVENTORY
                ? InventoryUtil.FULL_SCOPE : InventoryUtil.HOTBAR_SCOPE);
        return obs.found() && obs.type() != ResultType.OFFHAND;
    }

    private BasePlaceTarget findBestBasePlace(LivingEntity primary, List<TargetCache> potentialTargets) {
        if (primary == null || potentialTargets.isEmpty()) return null;

        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 playerCenter = mc.player.position();
        double maxSelf = baseMaxSelf.getValue();
        float playerHealth = selfNow().health();
        float primaryHp = primary.getHealth() + primary.getAbsorptionAmount();
        double baseMin = basePlaceMinDamage.getValue();
        int targetFeetY = primary.blockPosition().getY();
        double range = placeRangeValue();
        double rangeSq = range * range;
        int r = (int) Math.ceil(range);

        BlockPos playerPos = mc.player.blockPosition();

        int topLayerY    = targetFeetY - 1;
        int bottomLayerY = playerPos.getY() - r;

        int rr = r * r;

        for (int by = topLayerY; by >= bottomLayerY; by--) {
            BlockPos layerBest = null;
            float layerDmg = 0;
            int dy = by - playerPos.getY();
            int dyy = dy * dy;
            if (dyy > rr) continue;

            for (int dx = -r; dx <= r; dx++) {
                int dxxdyy = dx * dx + dyy;
                for (int dz = -r; dz <= r; dz++) {
                    if (dxxdyy + dz * dz > rr) continue;
                    BlockPos basePos    = new BlockPos(playerPos.getX() + dx, by, playerPos.getZ() + dz);
                    BlockPos crystalPos = basePos.above();

                    var crystalState = mc.level.getBlockState(crystalPos);
                    if (!crystalState.isAir() && !crystalState.canBeReplaced()) continue;
                    if (legacy112.getValue() && !mc.level.getBlockState(crystalPos.above()).isAir()) continue;
                    if (isBlocked(crystalPos)) continue;

                    if (playerCenter.distanceToSqr(basePos.getX() + 0.5, basePos.getY() + 0.5, basePos.getZ() + 0.5) > rangeSq) continue;
                    if (!PlaceUtil.canPlace(basePos)) continue;
                    if (detectMining.getValue() && isBeingMinedNow(basePos)) continue;

                    if (sqDistToBox(eye, crystalPos.getX(), crystalPos.getY(), crystalPos.getZ(),
                            crystalPos.getX() + 1, crystalPos.getY() + 1, crystalPos.getZ() + 1) > placeRangeSq()) continue;

                    Vec3 crystalCenter = new Vec3(crystalPos.getX() + 0.5, crystalPos.getY(), crystalPos.getZ() + 0.5);
                    double cMinX = crystalCenter.x - 1, cMinZ = crystalCenter.z - 1;
                    double cMaxX = crystalCenter.x + 1, cMaxY = crystalCenter.y + 2, cMaxZ = crystalCenter.z + 1;
                    Vec3 breakEye = bestReachEye(new AABB(cMinX, crystalCenter.y, cMinZ, cMaxX, cMaxY, cMaxZ));
                    if (sqDistToBox(breakEye, cMinX, crystalCenter.y, cMinZ, cMaxX, cMaxY, cMaxZ) > breakRangeSq()) continue;

                    if (!anyTargetWithin(potentialTargets, crystalCenter, 144.0)) continue;

                    float bound = 0;
                    boolean anyBound = false;
                    for (int i = 0, n = potentialTargets.size(); i < n; i++) {
                        TargetCache tc = potentialTargets.get(i);
                        if (tc.pos.distanceToSqr(crystalCenter) > 144.0) continue;
                        float dmg = maxDamage(tc, crystalCenter);
                        if (dmg < getDynamicMin(tc.hp, tc.abs, tc.armorBroken)) continue;
                        bound += dmg;
                        anyBound = true;
                    }
                    if (!anyBound || bound < baseMin || bound <= layerDmg) continue;

                    if (!selfDamageOk(crystalCenter, basePos, maxSelf, playerHealth)) continue;

                    float totalDmg = 0;
                    boolean anyTarget = false;
                    for (int i = 0, n = potentialTargets.size(); i < n; i++) {
                        TargetCache tc = potentialTargets.get(i);
                        if (tc.pos.distanceToSqr(crystalCenter) > 144.0) continue;
                        float dmg = calcDamage(tc, crystalCenter, basePos);
                        if (dmg < getDynamicMin(tc.hp, tc.abs, tc.armorBroken)) continue;
                        totalDmg += dmg;
                        anyTarget = true;
                    }
                    if (!anyTarget) continue;
                    if (totalDmg < baseMin) continue;
                    if (baseBalance.getValue() && totalDmg < primaryHp
                            && totalDmg < calcDamageSelf(crystalCenter, basePos)) continue;

                    if (totalDmg > layerDmg) {
                        layerDmg  = totalDmg;
                        layerBest = basePos;
                    }
                }
            }

            if (layerBest != null) {
                return new BasePlaceTarget(layerBest, layerDmg);
            }
        }
        return null;
    }

    /** Obsidian base placement follows the crystal AutoSwap mode; NONE falls back to SILENT. */
    private boolean doBasePlace(BasePlaceTarget target) {
        EnumSet<ResultType> scope = autoSwap.getValue() == AutoSwapMode.INVENTORY
                ? InventoryUtil.FULL_SCOPE : InventoryUtil.HOTBAR_SCOPE;
        Result obs = InventoryUtil.find(Items.OBSIDIAN, scope);
        if (!obs.found() || obs.type() == ResultType.OFFHAND) return false;

        ownSwapPackets.set(2); // our own swap must not trigger the break switch-pause
        SwapMode mode = autoSwap.getValue() == AutoSwapMode.INVENTORY
                ? SwapMode.ALTSILENT : SwapMode.SILENT;
        try {
            return GuizhouWare.placementManager.placeDirect(target.base, null, Items.OBSIDIAN, mode, scope);
        } finally {
            ownSwapPackets.set(0);
        }
    }

    private LivingEntity findClosestBaseTarget(List<TargetCache> targets) {
        double range = basePlaceTargetRange.getValue();
        double rangeSq = range * range;
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (TargetCache target : targets) {
            if (!(target.entity instanceof Player p)) continue;
            double d = mc.player.distanceToSqr(p);
            if (d > rangeSq) continue;
            if (d < bestDist) { bestDist = d; best = p; }
        }
        return best;
    }

    private EndCrystal findBestCrystal(List<TargetCache> potentialTargets) {
        if (potentialTargets.isEmpty()) return null;
        EndCrystal best = null;
        float bestDmg = 0;
        float playerHealth = selfNow().health();
        double maxSelf = maxSelfDamage.getValue();
        double breakRangeSq = breakRangeSq();

        AABB searchArea = mc.player.getBoundingBox().inflate(breakRangeValue() + 2);
        for (Entity e : mc.level.getEntities(null, searchArea)) {
            if (!(e instanceof EndCrystal crystal)) continue;
            if (!crystalAttackEligible(crystal)) continue;

            AABB bb = crystal.getBoundingBox();
            Vec3 reachEye = bestReachEye(bb);
            if (sqDistToBox(reachEye, bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ) > breakRangeSq) continue;
            if (!canPlanCrystalAttack(crystal)) continue;

            Vec3 cp = crystal.position();
            if (breakDamage(potentialTargets, cp, true) <= bestDmg) continue;

            if (!selfDamageOk(cp, null, maxSelf, playerHealth)) continue;

            float totalDmg = breakDamage(potentialTargets, cp, false);
            if (totalDmg <= bestDmg) continue;
            if (!balanceOk(totalDmg, cp, null)) continue;
            if (totalDmg > bestDmg) { bestDmg = totalDmg; best = crystal; }
        }
        lastBestDamage = bestDmg;
        return best;
    }

    private boolean breakCrystal(EndCrystal crystal) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return false;
        if (mc.gameMode == null || mc.getConnection() == null || weaponControlBusy()
                || !crystalAttackEligible(crystal)
                || !switchTimer.passedMs(tpsMs(switchPause.getValue()))) return false;
        if (rotate.getValue()) {
            Vec3 eyePos = mc.player.getEyePosition(1.0f);
            Vec3 hit = getClosestPointToEye(eyePos, crystal.getBoundingBox());
            float[] angles = MathUtil.calcAngle(eyePos, hit);
            if (!canBreakCrystal(crystal, angles[0], angles[1])) return false;
            GuizhouWare.rotationManager.submit(new RotationRequest(
                "AutoCrystal_break", 60, angles[0], angles[1], RotationRequest.Mode.SILENT
            ));
        }
        acceptPendingRotation();
        if (!sentRotationKnown || !canBreakCrystal(crystal, sentYaw, sentPitch)) return false;
        return sendCrystalAttack(crystal, false);
    }

    private boolean crystalAttackEligible(EndCrystal crystal) {
        return crystal.isAlive() && !crystal.isRemoved() && !deadIds.contains(crystal.getId())
                && crystal.tickCount >= minAge.getValue()
                && (!inhibit.getValue() || attackRetries.canAttempt(crystal.getId(), System.currentTimeMillis(),
                    tpsMs(breakTimeout.getValue()), attackLimit.getValue(), retryResetMs()))
                && attackWindow.canAttack(crystal.getId(), mc.player.tickCount);
    }

    private long retryResetMs() {
        return Math.max(tpsMs(breakTimeout.getValue()), tpsMs(retryReset.getValue()));
    }

    private boolean canPlanCrystalAttack(EndCrystal crystal) {
        if (rotate.getValue()) {
            Vec3 eye = mc.player.getEyePosition(1.0F);
            float[] angles = MathUtil.calcAngle(eye, getClosestPointToEye(eye, crystal.getBoundingBox()));
            return canBreakCrystal(crystal, angles[0], angles[1]);
        }
        acceptPendingRotation();
        return sentRotationKnown && canBreakCrystal(crystal, sentYaw, sentPitch);
    }

    private float breakDamage(List<TargetCache> potentialTargets, Vec3 center, boolean optimistic) {
        float total = 0.0F;
        for (TargetCache tc : potentialTargets) {
            if (tc.pos.distanceToSqr(center) > 144.0 || isSyncHeld(tc.entity)) continue;
            if (damageSync.getValue() && DamageSyncTracker.shouldHold(tc.entity)) continue;
            float damage = optimistic ? maxDamage(tc, center) : calcDamage(tc, center);
            if (damage > 0.0F && damage >= getPlaceMin(tc)) total += damage;
        }
        return total;
    }

    private boolean sendCrystalAttack(EndCrystal crystal, boolean instant) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return false;
        if (mc.gameMode == null || mc.getConnection() == null || weaponControlBusy()
                || !crystalAttackEligible(crystal)
                || !breakTimer.passedMs(tpsMs(breakDelay.getValue()))
                || !switchTimer.passedMs(tpsMs(switchPause.getValue()))) return false;
        if (!selfDamageOk(crystal.position(), null, maxSelfDamage.getValue(), selfNow().health())) return false;

        // Send is cancellable at Connection HEAD; inspect the complete dispatch before committing cadence.
        pendingAttack = null;
        sendingCrystalAttack = true;
        PacketEvent.Send attempt;
        try {
            if (instant) {
                mc.player.connection.send(ServerboundInteractPacket.createAttackPacket(crystal, mc.player.isShiftKeyDown()));
            } else {
                mc.gameMode.attack(mc.player, crystal);
            }
            attempt = pendingAttack;
        } finally {
            sendingCrystalAttack = false;
            pendingAttack = null;
        }
        if (attempt == null || attempt.isCancelled()) return false;
        attackWindow.markSent(crystal.getId(), mc.player.tickCount);
        attackRetries.markSent(crystal.getId(), System.currentTimeMillis(), retryResetMs());
        breakTimer.reset();
        lastBreakMs = System.currentTimeMillis();
        if (instant) diagInstaBreak++;
        else diagBreakSent++;
        return true;
    }

    private boolean canBreakCrystal(EndCrystal crystal, float yaw, float pitch) {
        AABB bb = crystal.getBoundingBox();
        Vec3 eyePos = bestReachEye(bb);
        if (bb.contains(eyePos)) return true;
        Vec3 look = getLookVector(yaw, pitch);
        Vec3 reachEnd = eyePos.add(look.scale(breakRangeValue()));
        var hit = bb.clip(eyePos, reachEnd);
        if (hit.isEmpty()) return false;
        BlockHitResult obstruction = mc.level.clip(new ClipContext(eyePos, hit.get(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        if (obstruction.getType() == HitResult.Type.MISS
                || obstruction.getLocation().distanceToSqr(eyePos) + 1.0E-6 >= hit.get().distanceToSqr(eyePos)) return true;
        return wallPlace.getValue() && hit.get().distanceToSqr(eyePos) <= wallRange.getValue() * wallRange.getValue();
    }

    private PlaceTarget findBestPlace(List<TargetCache> potentialTargets) {
        if (potentialTargets.isEmpty()) return null;
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        BlockPos playerPos = mc.player.blockPosition();
        int r = (int) Math.ceil(placeRangeValue());
        long now = System.currentTimeMillis();
        double placeRangeSq = placeRangeSq();
        double breakRangeSq = breakRangeSq();
        double maxSelf = maxSelfDamage.getValue();
        float playerHealth = selfNow().health();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int rr = r * r;

        candidates.clear();
        int scanAir = 0, scanPlaceR = 0, scanBreakR = 0, scanLowBound = 0;
        for (int x = -r; x <= r; x++) {
            int xx = x * x;
            for (int y = -r; y <= r; y++) {
                int xxyy = xx + y * y;
                for (int z = -r; z <= r; z++) {

                    if (xxyy + z * z > rr) continue;
                    cursor.set(playerPos.getX() + x, playerPos.getY() + y, playerPos.getZ() + z);
                    var baseState = mc.level.getBlockState(cursor);
                    if (!baseState.is(Blocks.OBSIDIAN) && !baseState.is(Blocks.BEDROCK)) continue;

                    int bx = cursor.getX(), by = cursor.getY(), bz = cursor.getZ();
                    int ay = by + 1;
                    cursor.set(bx, ay, bz);
                    var airState = mc.level.getBlockState(cursor);
                    if (!airState.isAir() && !airState.is(Blocks.FIRE)) { scanAir++; continue; } // FIRE is clearable now
                    if (legacy112.getValue() && !mc.level.getBlockState(cursor.set(bx, ay + 1, bz)).isAir()) { scanAir++; continue; }

                    if (sqDistToBox(eyePos, bx, ay, bz, bx + 1, ay + 1, bz + 1) > placeRangeSq) { scanPlaceR++; continue; }

                    double cx = bx + 0.5, cy = by + 1.0, cz = bz + 0.5;
                    Vec3 breakEye = bestReachEye(new AABB(cx - 1, cy, cz - 1, cx + 1, cy + 2, cz + 1));
                    if (sqDistToBox(breakEye, cx - 1, cy, cz - 1, cx + 1, cy + 2, cz + 1) > breakRangeSq) { scanBreakR++; continue; }

                    Vec3 crystalCenter = new Vec3(cx, cy, cz);

                    float boundTotal = 0;
                    boolean anyTarget = false;
                    for (int i = 0, n = potentialTargets.size(); i < n; i++) {
                        TargetCache tc = potentialTargets.get(i);
                        if (tc.pos.distanceToSqr(crystalCenter) > 144.0) continue;
                        float dmg = maxDamage(tc, crystalCenter);
                        if (dmg < getPlaceMin(tc)) continue;
                        boundTotal += dmg;
                        anyTarget = true;
                    }
                    if (!anyTarget) { scanLowBound++; continue; }

                    candidates.add(new PlaceCandidate(
                            new BlockPos(bx, by, bz), new BlockPos(bx, ay, bz), crystalCenter, boundTotal));
                }
            }
        }
        if (candidates.isEmpty()) {
            logPlacePick(null, "-", 0, scanAir, scanPlaceR, scanBreakR, scanLowBound, 0, 0, 0, 0, 0, 0);
            return null;
        }

        candidates.sort((a, b) -> Float.compare(b.bound, a.bound));

        BlockPos locked = null;
        if (lastPlaceAir != null) {
            long lockTs = crystalPlaces.get(lastPlaceAir.asLong());
            boolean pending = lockTs != 0L && now - lockTs < pendingWindowMs();
            if (pending || hasLiveCrystalAt(lastPlaceAir)) {
                double cx = lastPlaceAir.getX() + 0.5, cy = lastPlaceAir.getY(), cz = lastPlaceAir.getZ() + 0.5;
                Vec3 lockEye = bestReachEye(new AABB(cx - 1, cy, cz - 1, cx + 1, cy + 2, cz + 1));
                if (sqDistToBox(lockEye, cx - 1, cy, cz - 1, cx + 1, cy + 2, cz + 1) <= breakRangeSq) {
                    locked = lastPlaceAir;
                } else {
                    lastPlaceAir = null;
                }
            } else {
                lastPlaceAir = null;
            }
        }

        // Damage-aware lock release: the same-spot rhythm lock must not blind us to a clearly
        // better position (upstream behaviour locked onto the old spot even at 6.9 vs 12.8 dmg).
        if (locked != null && !candidates.isEmpty()) {
            float lockedBound = -1f;
            for (int i = 0, n = candidates.size(); i < n; i++) {
                if (candidates.get(i).airPos.equals(locked)) {
                    lockedBound = candidates.get(i).bound;
                    break;
                }
            }
            if (lockedBound < 0f || candidates.get(0).bound > lockedBound + LOCK_RELEASE_MARGIN) {
                locked = null;
            }
        }

        PlaceTarget best = null;
        int rejLocked = 0, rejBlocked = 0, rejPending = 0, rejSelf = 0, rejMin = 0, rejBal = 0;
        String topReject = "-";
        float topRejectBound = -1f;
        for (int i = 0, n = candidates.size(); i < n; i++) {
            PlaceCandidate c = candidates.get(i);
            if (best != null && c.bound <= best.damage) break;

            if (locked != null && !c.airPos.equals(locked)) {
                if (c.bound > topRejectBound) { topRejectBound = c.bound; topReject = c.airPos + " locked"; }
                rejLocked++;
                continue;
            }
            LivingEntity faceEntity = entityOverlap.getValue() ? faceOverlapAllowed(c.airPos, potentialTargets) : null;
            if (faceEntity == null && isBlocked(c.airPos)) {
                if (c.bound > topRejectBound) { topRejectBound = c.bound; topReject = c.airPos + " blocked"; }
                rejBlocked++;
                continue;
            }
            long pendingTs = crystalPlaces.get(c.airPos.asLong());
            if (pendingTs != 0L && now - pendingTs < pendingWindowMs()) {
                if (c.bound > topRejectBound) { topRejectBound = c.bound; topReject = c.airPos + " pending"; }
                rejPending++;
                continue;
            }

            if (!selfDamageOk(c.crystalCenter, null, maxSelf, playerHealth)) {
                if (c.bound > topRejectBound) { topRejectBound = c.bound; topReject = c.airPos + " selfDmg"; }
                rejSelf++;
                continue;
            }

            float totalDmg = 0;
            boolean anyTarget = false;
            for (int j = 0, m = potentialTargets.size(); j < m; j++) {
                TargetCache tc = potentialTargets.get(j);
                if (tc.pos.distanceToSqr(c.crystalCenter) > 144.0) continue;
                float dmg = calcDamage(tc, c.crystalCenter);
                // FacePlace-eligible targets bypass MinDamage entirely (finisher damage).
                double minGate = getPlaceMin(tc);
                if (dmg < minGate) continue;
                totalDmg += dmg;
                anyTarget = true;
            }
            if (!anyTarget) {
                if (c.bound > topRejectBound) { topRejectBound = c.bound; topReject = c.airPos + " belowMinDmg"; }
                rejMin++;
                continue;
            }
            if (!balanceOk(totalDmg, c.crystalCenter, null)) {
                if (c.bound > topRejectBound) { topRejectBound = c.bound; topReject = c.airPos + " balance"; }
                rejBal++;
                continue;
            }
            if (best == null || totalDmg > best.damage) {
                // Do not let an unreachable high-damage candidate starve a valid runner-up.
                if (GuizhouWare.placementManager.prepareInteractionHit(c.base, null, mc.player.getEyePosition(),
                        placeRangeValue(), wallPlace.getValue() ? wallRange.getValue() : 0.0, false) == null) continue;
                best = new PlaceTarget(c.base, totalDmg, faceEntity);
            }
        }
        logPlacePick(best, topReject, topRejectBound,
                scanAir, scanPlaceR, scanBreakR, scanLowBound,
                rejLocked, rejBlocked, rejPending, rejSelf, rejMin, rejBal);
        return best;
    }

    /** Position-level place-decision diagnostics, printed when PacketLog is on (1 in 10 decisions). */
    private void logPlacePick(PlaceTarget best, String topReject, float topRejectBound,
                              int scanAir, int scanPlaceR, int scanBreakR, int scanLowBound,
                              int rejLocked, int rejBlocked, int rejPending, int rejSelf,
                              int rejMin, int rejBal) {
        if (!packetLog.getValue() || ++placeDiagCounter % 10 != 0) return;
        GuizhouWare.LOGGER.info(
                "[ACDiag] place-pick {} dmg={} | topReject[{} bound={}] | scan[air={} placeR={} breakR={} lowBound={}] final[locked={} blocked={} pending={} self={} min={} bal={}]",
                best == null ? "none" : best.base.above().toShortString(),
                best == null ? "0.0" : String.format("%.1f", best.damage),
                topReject, String.format("%.1f", topRejectBound),
                scanAir, scanPlaceR, scanBreakR, scanLowBound,
                rejLocked, rejBlocked, rejPending, rejSelf, rejMin, rejBal);
    }

    private boolean isBurrowBlock(BlockState state) {
        return state.is(Blocks.REDSTONE_BLOCK)
                || state.is(Blocks.PISTON)
                || state.is(Blocks.STICKY_PISTON)
                || state.is(Blocks.PISTON_HEAD)
                || state.is(Blocks.MOVING_PISTON);
    }

    private boolean nearBurrowBlock(Vec3 center) {
        int r = (int) Math.ceil(ANTI_CHINESE_RADIUS);
        double radSq = ANTI_CHINESE_RADIUS * ANTI_CHINESE_RADIUS;
        double playerRangeSq = antiChineseRange.getValue() * antiChineseRange.getValue();
        Vec3 playerPos = mc.player.position();
        int cx = Mth.floor(center.x), cy = Mth.floor(center.y), cz = Mth.floor(center.z);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    int bx = cx + dx, by = cy + dy, bz = cz + dz;
                    double ox = (bx + 0.5) - center.x;
                    double oy = (by + 0.5) - center.y;
                    double oz = (bz + 0.5) - center.z;
                    if (ox * ox + oy * oy + oz * oz > radSq) continue;

                    if (playerPos.distanceToSqr(bx + 0.5, by + 0.5, bz + 0.5) > playerRangeSq) continue;
                    cursor.set(bx, by, bz);
                    if (isBurrowBlock(getStateFast(cursor))) return true;
                }
            }
        }
        return false;
    }

    private static final int[][] FACE_OFFSETS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private boolean adjacentToBurrowBlock(BlockPos airPos) {
        double playerRangeSq = antiChineseRange.getValue() * antiChineseRange.getValue();
        Vec3 playerPos = mc.player.position();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int[] o : FACE_OFFSETS) {
            int bx = airPos.getX() + o[0], by = airPos.getY() + o[1], bz = airPos.getZ() + o[2];
            if (playerPos.distanceToSqr(bx + 0.5, by + 0.5, bz + 0.5) > playerRangeSq) continue;
            cursor.set(bx, by, bz);
            if (isBurrowBlock(getStateFast(cursor))) return true;
        }
        return false;
    }

    private final Set<BlockPos> miningCheck = new HashSet<>();

    private boolean isBeingMinedNow(BlockPos pos) {
        GuangxiMineModule mine = GuizhouWare.moduleManager.getModuleByClass(GuangxiMineModule.class);
        if (mine == null || !mine.isEnabled()) return false;
        miningCheck.clear();
        mine.collectMiningPositions(miningCheck, 0.0);
        return miningCheck.contains(pos);
    }

    private boolean enemyNearby(double range) {        TargetsModule targets = GuizhouWare.moduleManager.getModuleByClass(TargetsModule.class);
        double rangeSq = range * range;
        AABB area = mc.player.getBoundingBox().inflate(range);
        for (Entity e : mc.level.getEntities(mc.player, area)) {
            if (!(e instanceof Player p)) continue;
            if (targets != null && !targets.isValidPlayerTarget(p)) continue;
            if (mc.player.distanceToSqr(p) <= rangeSq) return true;
        }
        return false;
    }

    private PlaceTarget findAntiChinesePlace() {
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        BlockPos playerPos = mc.player.blockPosition();
        int r = (int) Math.ceil(placeRangeValue());
        long now = System.currentTimeMillis();
        double placeRangeSq = placeRangeSq();
        double breakRangeSq = breakRangeSq();
        double maxSelf = maxSelfDamage.getValue();
        float playerHealth = selfNow().health();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int rr = r * r;

        PlaceTarget best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int x = -r; x <= r; x++) {
            int xx = x * x;
            for (int y = -r; y <= r; y++) {
                int xxyy = xx + y * y;
                for (int z = -r; z <= r; z++) {
                    if (xxyy + z * z > rr) continue;
                    cursor.set(playerPos.getX() + x, playerPos.getY() + y, playerPos.getZ() + z);
                    var baseState = mc.level.getBlockState(cursor);
                    if (!baseState.is(Blocks.OBSIDIAN) && !baseState.is(Blocks.BEDROCK)) continue;

                    int bx = cursor.getX(), by = cursor.getY(), bz = cursor.getZ();
                    int ay = by + 1;
                    cursor.set(bx, ay, bz);
                    var airState = mc.level.getBlockState(cursor);
                    if (!airState.isAir() && !airState.is(Blocks.FIRE)) continue;
                    if (legacy112.getValue() && !mc.level.getBlockState(cursor.set(bx, ay + 1, bz)).isAir()) continue;

                    if (sqDistToBox(eyePos, bx, ay, bz, bx + 1, ay + 1, bz + 1) > placeRangeSq) continue;

                    double cx = bx + 0.5, cy = by + 1.0, cz = bz + 0.5;
                    Vec3 breakEye = bestReachEye(new AABB(cx - 1, cy, cz - 1, cx + 1, cy + 2, cz + 1));
                    if (sqDistToBox(breakEye, cx - 1, cy, cz - 1, cx + 1, cy + 2, cz + 1) > breakRangeSq) continue;

                    Vec3 crystalCenter = new Vec3(cx, cy, cz);

                    double dSq = eyePos.distanceToSqr(crystalCenter);
                    if (dSq >= bestDistSq) continue;

                    BlockPos airPos = new BlockPos(bx, ay, bz);
                    if (!adjacentToBurrowBlock(airPos)) continue;

                    if (isBlocked(airPos)) continue;
                    long pendingTs = crystalPlaces.get(airPos.asLong());
                    if (pendingTs != 0L && now - pendingTs < pendingWindowMs()) continue;

                    if (!selfDamageOk(crystalCenter, null, maxSelf, playerHealth)) continue;

                    bestDistSq = dSq;
                    best = new PlaceTarget(new BlockPos(bx, by, bz), 0f);
                }
            }
        }
        return best;
    }

    private EndCrystal findAntiChineseCrystal() {
        float playerHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        double maxSelf = maxSelfDamage.getValue();
        double breakRangeSq = breakRangeSq();

        EndCrystal best = null;
        double bestDist = Double.MAX_VALUE;
        AABB searchArea = mc.player.getBoundingBox().inflate(breakRangeValue() + 2);
        for (Entity e : mc.level.getEntities(null, searchArea)) {
            if (!(e instanceof EndCrystal crystal)) continue;
            if (!crystalAttackEligible(crystal)) continue;

            AABB bb = crystal.getBoundingBox();
            Vec3 reachEye = bestReachEye(bb);
            if (sqDistToBox(reachEye, bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ) > breakRangeSq) continue;
            if (!canPlanCrystalAttack(crystal)) continue;

            Vec3 cp = crystal.position();
            if (!nearBurrowBlock(cp)) continue;
            if (!selfDamageOk(cp, null, maxSelf, playerHealth)) continue;

            double d = mc.player.distanceToSqr(crystal);
            if (d < bestDist) { bestDist = d; best = crystal; }
        }
        return best;
    }

    private List<TargetCache> collectTargets(TargetsModule targets, double scanRadius) {
        List<TargetCache> out = new ArrayList<>();
        AABB area = mc.player.getBoundingBox().inflate(scanRadius);
        for (Entity e : mc.level.getEntities(mc.player, area)) {
            if (!(e instanceof LivingEntity living)) continue;
            if (living.isDeadOrDying()) continue;
            if (deadIds.contains(e.getId())) continue;

            if (targets != null && !targets.isValidPlayerTarget(e)) continue;
            boolean armorBroken = e instanceof Player p && isAnyArmorBroken(p);

            Vec3 tPos = living.position();
            AABB tBox = living.getBoundingBox();
            if (predict.getValue() && predictTicks.getValue() > 0) {
                Vec3 p = PredictUtil.predict(living, predictTicks.getValue(), maxMotionY.getValue());
                tPos = p;
                tBox = tBox.move(p.x - living.getX(), p.y - living.getY(), p.z - living.getZ());
            }

            var baseSnapshot = CombatSnapshotService.get(living);
            float armor = baseSnapshot.armor();
            float toughness = baseSnapshot.toughness();
            int protPoints = baseSnapshot.protection();

            boolean faceEligible = facePlace.getValue() && living instanceof Player p
                    && (living.getHealth() + living.getAbsorptionAmount() <= facePlaceHealth.getValue()
                        || equippedArmorPieces(p) <= facePlaceArmor.getValue());

            out.add(new TargetCache(living, tPos, tBox,
                    living.getHealth(), living.getAbsorptionAmount(), armorBroken,
                    armor, toughness, protPoints, baseSnapshot.resistanceMultiplier(), faceEligible));
        }
        decisionTargets = out;
        return out;
    }

    private void updateSeeThrough() {
        seeThrough.clear();
        if (!antiSurround.getValue()) return;
        GuangxiMineModule mine = GuizhouWare.moduleManager.getModuleByClass(GuangxiMineModule.class);
        if (mine == null || !mine.isEnabled()) return;
        mine.collectMiningPositions(seeThrough, antiSurroundCompletion.getValue() / 100.0);
    }

    private static boolean anyTargetWithin(List<TargetCache> cache, Vec3 center, double maxDistSq) {
        for (int i = 0, n = cache.size(); i < n; i++) {
            if (cache.get(i).pos.distanceToSqr(center) <= maxDistSq) return true;
        }
        return false;
    }

    private static double sqDistToBox(Vec3 p, double minX, double minY, double minZ,
                                      double maxX, double maxY, double maxZ) {
        double dx = p.x < minX ? minX - p.x : (p.x > maxX ? p.x - maxX : 0);
        double dy = p.y < minY ? minY - p.y : (p.y > maxY ? p.y - maxY : 0);
        double dz = p.z < minZ ? minZ - p.z : (p.z > maxZ ? p.z - maxZ : 0);
        return dx * dx + dy * dy + dz * dz;
    }

    private record TargetCache(LivingEntity entity, Vec3 pos, AABB box, float hp, float abs,
                               boolean armorBroken, float armor, float toughness,
                               int protPoints, float resistanceMultiplier, boolean faceEligible) {}

    /** Main-thread entity references; this shallow list must never be used by a background worker. */
    private void refreshEntitySnapshot() {
        int tick = mc.player == null ? -1 : mc.player.tickCount;
        if (tick == entitySnapshotTick) return;
        double r = Math.max(Math.max(placeRangeValue(), breakRangeValue()) + 4.0, 8.0);
        List<Entity> snap = new ArrayList<>(mc.level.getEntities(null, mc.player.getBoundingBox().inflate(r)));
        entitySnapshot = snap;
        entitySnapshotTick = tick;
    }

    private List<Entity> snapshotEntities() {
        int tick = mc.player == null ? -1 : mc.player.tickCount;
        List<Entity> snap = entitySnapshot;
        if (tick == entitySnapshotTick && !snap.isEmpty()) return snap;
        if (mc.level == null) return List.of();
        // stale or cold (e.g. synchronous mode before the first refresh): rebuild locally
        double r = Math.max(Math.max(placeRangeValue(), breakRangeValue()) + 4.0, 8.0);
        return mc.level.getEntities(null, mc.player.getBoundingBox().inflate(r));
    }

    private boolean isBlocked(BlockPos airPos) {
        return isBlockedExcept(airPos, null);
    }

    /** Explicit server compatibility exception; vanilla rejects an occupied crystal column. */
    private boolean isBlockedExcept(BlockPos airPos, LivingEntity allow) {
        AABB cell = new AABB(airPos).expandTowards(0, 1, 0);
        for (Entity e : snapshotEntities()) {
            if (e.isRemoved() || !e.getBoundingBox().intersects(cell)) continue;
            if (e == allow) continue;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------ FacePlace (Mio)

    private int equippedArmorPieces(Player p) {
        int n = 0;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            if (!p.getItemBySlot(slot).isEmpty()) n++;
        }
        return n;
    }

    /** FacePlace triggers on near-death or nearly-stripped targets — punch through MinDamage. */
    private boolean isFacePlaceEligible(TargetCache tc) {
        return tc.faceEligible;
    }

    /**
     * Returns the face-eligible target allowed to occupy this cell, or null when the cell must
     * stay entity-clean. When the only blocker is a FacePlace-eligible target the cell becomes a
     * server-compatibility candidate. Vanilla rejects placement through standing entities.
     */
    @org.jetbrains.annotations.Nullable
    private LivingEntity faceOverlapAllowed(BlockPos airPos, List<TargetCache> targets) {
        AABB cell = new AABB(airPos).expandTowards(0, 1, 0);
        LivingEntity allowed = null;
        outer:
        for (Entity e : snapshotEntities()) {
            if (e.isRemoved() || !e.getBoundingBox().intersects(cell)) continue;
            if (e instanceof LivingEntity living) {
                for (int i = 0, n = targets.size(); i < n; i++) {
                    if (targets.get(i).entity == living && isFacePlaceEligible(targets.get(i))) {
                        allowed = living;
                        continue outer;
                    }
                }
            }
            return null;
        }
        return allowed;
    }

    private boolean doPlace(PlaceTarget target) {
        return doPlace(target, false);
    }

    private boolean doPlace(PlaceTarget target, boolean trustBase) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return false;
        boolean holdingCrystal = mc.player.getMainHandItem().is(Items.END_CRYSTAL)
                || mc.player.getOffhandItem().is(Items.END_CRYSTAL);
        if (autoSwap.getValue() == AutoSwapMode.NONE) {
            if (!holdingCrystal) {
                lastBestDamage = 0;
                return false;
            }
        } else if (!holdingCrystal
                && !InventoryUtil.find(Items.END_CRYSTAL, InventoryUtil.FULL_SCOPE).found()) {
            lastBestDamage = 0;
            return false;
        }
        lastBestDamage = target.damage;

        // Fire over the base blocks the server-side placement: clear it with an instant
        // 2-packet break and place next tick (Mio trick).
        BlockPos air = target.base.above();
        if (mc.level.getBlockState(air).is(Blocks.FIRE)) {
            mc.getConnection().send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, air, Direction.DOWN));
            mc.getConnection().send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, air, Direction.DOWN));
            diagPlaceAttempt++;
            return false;
        }

        diagPlaceAttempt++;
        var options = new dev.guizhou.manager.PlacementManager.CrystalOptions(
                placeRangeValue(), wallPlace.getValue() ? wallRange.getValue() : 0.0, sneakPlace.getValue());
        var hit = GuizhouWare.placementManager.prepareInteractionHit(target.base, null,
                mc.player.getEyePosition(), options.range(), options.wallRange(), trustBase);
        if (hit == null) return false;
        if (rotate.getValue()) {
            float[] angles = MathUtil.calcAngle(mc.player.getEyePosition(), hit.getLocation());
            if (!GuizhouWare.rotationManager.trySubmit(new RotationRequest(
                    "AutoCrystal_place", 60, angles[0], angles[1], RotationRequest.Mode.SILENT))) return false;
        }
        boolean sent = placeCrystalShared(target.base, trustBase, target.faceEntity(), options);
        if (sent) {
            diagPlaceSent++;
            recordPlace(air);
            // The spawn for this placement takes the next sequential entity id.
            sendGhostAttacksAfter(lastMaxEntityId);
        }
        return sent;
    }

    /**
     * Crystal placement shared with PistonCrystal: a held crystal (either hand) places directly
     * with that hand and zero swap packets; NONE refuses to swap; SILENT hotbar-swaps and reverts;
     * INVENTORY window-swaps from anywhere (main inventory included) and reverts within the tick.
     * {@code faceEntity} is an explicit non-vanilla entity-overlap compatibility exception.
     */
    public boolean placeCrystalShared(BlockPos base, boolean trustBase) {
        return placeCrystalShared(base, trustBase, null);
    }

    public boolean placeCrystalShared(BlockPos base, boolean trustBase, LivingEntity faceEntity) {
        return placeCrystalShared(base, trustBase, faceEntity,
                dev.guizhou.manager.PlacementManager.CrystalOptions.DEFAULT);
    }

    private boolean placeCrystalShared(BlockPos base, boolean trustBase, LivingEntity faceEntity,
                                       dev.guizhou.manager.PlacementManager.CrystalOptions options) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return false;
        if (weaponControlBusy()) return false;
        boolean mainCrystal = mc.player.getMainHandItem().is(Items.END_CRYSTAL);
        boolean offCrystal = mc.player.getOffhandItem().is(Items.END_CRYSTAL);
        if (mainCrystal || offCrystal) {
            return GuizhouWare.placementManager.placeCrystalDirect(base, trustBase,
                    offCrystal && !mainCrystal ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND,
                    faceEntity, options);
        }
        return switch (autoSwap.getValue()) {
            case NONE -> false;
            case SILENT -> {
                ownSwapPackets.set(2); // swap + swap-back must not trigger the break switch-pause
                try {
                    yield GuizhouWare.placementManager.placeCrystal(base, trustBase,
                            SwapMode.SILENT, InventoryUtil.HOTBAR_SCOPE, faceEntity, options);
                } finally {
                    ownSwapPackets.set(0);
                }
            }
            case INVENTORY -> {
                ownSwapPackets.set(2);
                try {
                    yield GuizhouWare.placementManager.placeCrystal(base, trustBase,
                            SwapMode.ALTSILENT, InventoryUtil.FULL_SCOPE, faceEntity, options);
                } finally {
                    ownSwapPackets.set(0);
                }
            }
        };
    }

    private void recordPlace(BlockPos airPos) {
        crystalPlaces.put(airPos.asLong(), System.currentTimeMillis());
        markRender(airPos);
        lastPlaceAir = airPos;
    }

    private boolean hasLiveCrystalAt(BlockPos airPos) {
        for (Entity e : mc.level.getEntities(null, new AABB(airPos))) {
            if (e instanceof EndCrystal && e.blockPosition().equals(airPos)) return true;
        }
        return false;
    }

    public boolean preplaceCrystal(BlockPos airPos, boolean snap) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return false;
        if (nullCheck() || !place.getValue() || weaponControlBusy()) return false;
        ensureSession();
        if (!rubberbandQuiet()) return false;

        BlockPos base = airPos.below();
        var baseState = mc.level.getBlockState(base);
        if (!baseState.is(Blocks.OBSIDIAN) && !baseState.is(Blocks.BEDROCK)) return false;
        if (isBlocked(airPos)) return false;

        long now = System.currentTimeMillis();
        long pendingTs = crystalPlaces.get(airPos.asLong());
        if (pendingTs != 0L && now - pendingTs < pendingWindowMs()) return false;

        ensureSelfCache();
        updateSeeThrough();
        Vec3 center = new Vec3(airPos.getX() + 0.5, airPos.getY(), airPos.getZ() + 0.5);
        if (!selfDamageOk(center, null, maxSelfDamage.getValue(), selfNow().health())) return false;
        return doPlace(new PlaceTarget(base, 0f), false);
    }

    private boolean weaponControlBusy() {
        return EatUtil.shouldDefer() || GuangxiElytraChaseModule.blocksCrystalCombat();
    }

    public boolean isDesirablePlacement(BlockPos airPos) {
        if (nullCheck()) return false;
        ensureSession();
        ensureSelfCache();
        updateSeeThrough();

        BlockPos base = airPos.below();
        BlockState baseState = mc.level.getBlockState(base);
        if (!baseState.is(Blocks.OBSIDIAN) && !baseState.is(Blocks.BEDROCK)) return false;
        if (legacy112.getValue() && !mc.level.getBlockState(airPos.above()).isAir()) return false;

        int bx = airPos.getX(), by = airPos.getY(), bz = airPos.getZ();
        Vec3 eye = mc.player.getEyePosition(1.0f);
        if (sqDistToBox(eye, bx, by, bz, bx + 1, by + 1, bz + 1) > placeRangeSq()) return false;

        double cx = bx + 0.5, cy = by, cz = bz + 0.5;
        if (sqDistToBox(bestReachEye(new AABB(cx - 1, cy, cz - 1, cx + 1, cy + 2, cz + 1)),
                cx - 1, cy, cz - 1, cx + 1, cy + 2, cz + 1) > breakRangeSq()) return false;

        Vec3 crystalCenter = new Vec3(cx, cy, cz);
        float playerHealth = selfNow().health();
        if (!selfDamageOk(crystalCenter, null, maxSelfDamage.getValue(), playerHealth)) return false;

        TargetsModule targets = GuizhouWare.moduleManager.getModuleByClass(TargetsModule.class);
        List<TargetCache> potentialTargets = collectTargets(targets, placeRangeValue() + 12.0);
        for (int i = 0, n = potentialTargets.size(); i < n; i++) {
            TargetCache tc = potentialTargets.get(i);
            if (tc.pos.distanceToSqr(crystalCenter) > 144.0) continue;
            if (calcDamage(tc, crystalCenter) >= getDynamicMin(tc.hp, tc.abs, tc.armorBroken)) return true;
            if (antiSurround.getValue() && isFeetAdjacent(tc.pos, airPos)) return true;
        }
        return false;
    }

    private boolean isFeetAdjacent(Vec3 targetPos, BlockPos airPos) {
        BlockPos feet = BlockPos.containing(targetPos.x, targetPos.y, targetPos.z);
        if (feet.getY() != airPos.getY()) return false;
        int dx = airPos.getX() - feet.getX();
        int dz = airPos.getZ() - feet.getZ();
        return (Math.abs(dx) == 1 && dz == 0) || (Math.abs(dz) == 1 && dx == 0);
    }

    private void ageCrystalPlaces() {
        long now = System.currentTimeMillis();
        attackRetries.expire(now, Math.max(5000L, retryResetMs()));
        LongIterator it = crystalPlaces.keySet().iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            if (now - crystalPlaces.get(key) > CRYSTAL_TRACK_MS) it.remove();
        }

        if (mc.player != null && mc.player.tickCount % 100 == 0) {
            deadIds.clear();
        }
    }

    private float calcDamage(TargetCache tc, Vec3 explosionPos) {
        return calcDamage(tc, explosionPos, null);
    }

    private float calcDamage(TargetCache tc, Vec3 explosionPos, BlockPos phantomObsidianPos) {
        double dist = tc.pos.distanceTo(explosionPos);
        if (dist > 12.0) return 0;
        TargetDamageKey key = new TargetDamageKey(tc, explosionPos,
                phantomObsidianPos == null ? null : phantomObsidianPos.immutable());
        Float cached = targetDamageCache.get(key);
        if (cached != null) { ClientDiagnostics.count("combat.GuangxiCrystal.damageCache.hit", 1); return cached; }
        double exposure = calcExposure(explosionPos, tc.box, phantomObsidianPos);
        float damage = targetDamage(tc, dist, exposure);
        targetDamageCache.put(key, damage);
        ClientDiagnostics.count("combat.GuangxiCrystal.damageCache.miss", 1);
        return damage;
    }

    private float maxDamage(TargetCache tc, Vec3 explosionPos) {
        double dist = tc.pos.distanceTo(explosionPos);
        if (dist > 12.0) return 0;
        return targetDamage(tc, dist, 1.0);
    }

    private float targetDamage(TargetCache tc, double dist, double exposure) {
        if (exposure <= 0) return 0;
        double impact = (1.0 - dist / 12.0) * exposure;
        if (impact <= 0) return 0;

        float dmg = baseExplosionDamage(impact, selfNow().difficulty());
        return ExplosionDamageModel.reduce(dmg, tc.armor, tc.toughness, tc.resistanceMultiplier, tc.protPoints);
    }

    private float maxDamageSelf(Vec3 explosionPos) {
        SelfCache sc = selfNow();
        double dist = Math.min(sc.pos().distanceTo(explosionPos), sc.currentPos().distanceTo(explosionPos));
        if (dist > 12.0) return 0;
        return selfDamage(dist, 1.0, sc);
    }

    private float calcDamageSelf(Vec3 explosionPos, BlockPos phantomObsidianPos) {
        SelfCache sc = selfNow();
        SelfDamageKey key = new SelfDamageKey(explosionPos,
                phantomObsidianPos == null ? null : phantomObsidianPos.immutable());
        Float cached = selfDamageCache.get(key);
        if (cached != null) return cached;
        float damage = selfDamageAt(sc, sc.currentPos(), sc.currentBox(), explosionPos, phantomObsidianPos);
        if (!sc.pos().equals(sc.currentPos())) {
            damage = Math.max(damage, selfDamageAt(sc, sc.pos(), sc.box(), explosionPos, phantomObsidianPos));
        }
        selfDamageCache.put(key, damage);
        return damage;
    }

    private float selfDamageAt(SelfCache sc, Vec3 position, AABB box, Vec3 explosionPos, BlockPos phantomBase) {
        double dist = position.distanceTo(explosionPos);
        if (dist > 12.0) return 0;
        double exposure = calcExposure(explosionPos, box, phantomBase);
        return selfDamage(dist, exposure, sc);
    }

    private boolean selfPredictionActive() {
        return predict.getValue() && selfPredictTicks.getValue() > 0;
    }

    private SelfCache selfNow() {
        SelfCache c = selfCache;
        if (c == null) {
            ensureSelfCache();
            c = selfCache;
        }
        return c;
    }

    /** Main-thread only: refresh the self snapshot before a calculation pass. */
    private void ensureSelfCache() {
        // A chunk can be replaced at the same coordinates; keep this cache within one pass.
        cachedChunk = null;
        cachedChunkX = cachedChunkZ = Integer.MIN_VALUE;
        selfDamageCache.clear();
        targetDamageCache.clear();

        float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        var baseSnapshot = CombatSnapshotService.get(mc.player);
        float armor = baseSnapshot.armor();
        float toughness = baseSnapshot.toughness();

        Vec3 currentPos = mc.player.position();
        AABB currentBox = mc.player.getBoundingBox();
        Vec3 pos = currentPos;
        AABB box = currentBox;
        if (selfPredictionActive()) {
            Vec3 p = PredictUtil.predict(mc.player, selfPredictTicks.getValue(), maxMotionY.getValue());
            pos = p;
            box = box.move(p.x - mc.player.getX(), p.y - mc.player.getY(), p.z - mc.player.getZ());
        }
        selfCache = new SelfCache(pos, box, currentPos, currentBox, health, armor, toughness,
                baseSnapshot.protection(), baseSnapshot.resistanceMultiplier(), mc.level.getDifficulty());
    }

    private static float resistanceMultiplier(LivingEntity entity) {
        var effect = entity.getEffect(net.minecraft.world.effect.MobEffects.RESISTANCE);
        return ExplosionDamageModel.resistanceMultiplier(effect == null ? -1 : effect.getAmplifier());
    }

    private int protectionPoints(LivingEntity entity) {
        int points = 0;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = entity.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            points += EnchantmentUtil.getLevel(Enchantments.PROTECTION, stack);
            points += 2 * EnchantmentUtil.getLevel(Enchantments.BLAST_PROTECTION, stack);
        }
        return Math.min(points, 20);
    }

    private boolean isSyncHeld(LivingEntity target) {
        return hurtSync.getValue()
                && target.hurtTime > waitHurt.getValue()
                && System.currentTimeMillis() - lastBreakMs < tpsMs(syncTimeout.getValue());
    }

    private float selfDamage(double dist, double exposure, SelfCache sc) {
        if (exposure <= 0) return 0;
        double impact = (1.0 - dist / 12.0) * exposure;
        if (impact <= 0) return 0;

        float dmg = baseExplosionDamage(impact, sc.difficulty());
        return ExplosionDamageModel.reduce(dmg, sc.armor(), sc.toughness(), sc.resistanceMultiplier(), sc.epf());
    }

    private float baseExplosionDamage(double impact, Difficulty difficulty) {
        float dmg = (float) ((impact * impact + impact) / 2.0 * 7.0 * 12.0 + 1.0);
        switch (difficulty) {
            case PEACEFUL -> dmg = 0f;
            case EASY -> dmg = Math.min(dmg / 2f + 1f, dmg);
            case HARD -> dmg *= 1.5f;
        }
        return dmg;
    }

    /**
     * AlienV4 Balance gate: a hit that cannot one-shot (below max player HP) must deal at least
     * {@code BalanceOffset} more damage to the target than to ourselves; lethal hits always pass.
     */
    private boolean balanceOk(float totalDamage, Vec3 crystalCenter, BlockPos phantomObsidianPos) {
        if (!balance.getValue()) return true;
        // A group sum of 20 is not proof that any one target dies, especially with absorption.
        for (TargetCache target : decisionTargets) {
            float health = target.hp + target.abs;
            if (health <= 0 || totalDamage < health || isSyncHeld(target.entity)) continue;
            if (damageSync.getValue() && DamageSyncTracker.shouldHold(target.entity)) continue;
            if (calcDamage(target, crystalCenter, phantomObsidianPos) >= health) return true;
        }
        float selfDamage = calcDamageSelf(crystalCenter, phantomObsidianPos);
        return totalDamage >= selfDamage + balanceOffset.getValue();
    }

    private boolean selfDamageOk(Vec3 crystalCenter, BlockPos phantomObsidianPos,
                                 double maxSelf, float playerHealth) {
        double reserve = reserveHp.getValue();
        float optimistic = maxDamageSelf(crystalCenter);
        if (CrystalSelectionPolicy.isSelfDamageSafe(optimistic, maxSelf, playerHealth, reserve)) return true;

        float real = calcDamageSelf(crystalCenter, phantomObsidianPos);
        return CrystalSelectionPolicy.isSelfDamageSafe(real, maxSelf, playerHealth, reserve);
    }

    private float armorAbsorb(float dmg, float armor, float toughness) {
        float i = 2.0f + toughness / 4.0f;
        float j = Mth.clamp(armor - dmg / i, armor * 0.2f, 20.0f);
        return dmg * (1.0f - j / 25.0f);
    }

    private double calcExposure(Vec3 source, AABB box, BlockPos phantomObsidianPos) {
        double dx = box.getXsize();
        double dy = box.getYsize();
        double dz = box.getZsize();
        double stepX = 1.0 / (dx * 2.0 + 1.0);
        double stepY = 1.0 / (dy * 2.0 + 1.0);
        double stepZ = 1.0 / (dz * 2.0 + 1.0);
        if (stepX <= 0 || stepY <= 0 || stepZ <= 0) return 0;
        double offsetX = (1.0 - Math.floor(1.0 / stepX) * stepX) / 2.0;
        double offsetZ = (1.0 - Math.floor(1.0 / stepZ) * stepZ) / 2.0;
        int total = 0;
        int unblocked = 0;

        ExposureContext ctx = exposureCtx;
        BiFunction<ExposureContext, BlockPos, BlockHitResult> tester = (c, pos) -> {
            Vec3 from = c.start, to = c.end;
            if (phantomObsidianPos != null && pos.equals(phantomObsidianPos)) {
                return Shapes.block().clip(from, to, pos);
            }

            if (!seeThrough.isEmpty() && seeThrough.contains(pos)) return null;
            BlockState state = getStateFast(pos);
            if (state.isAir()) return null;

            if (blockDestruction.getValue() && state.getBlock().getExplosionResistance() < EXPLODABLE_RESISTANCE) return null;

            VoxelShape shape = state.getCollisionShape(mc.level, pos);
            if (shape.isEmpty()) return null;
            return shape.clip(from, to, pos);
        };

        for (double xi = 0; xi <= 1.0; xi += stepX) {
            for (double yi = 0; yi <= 1.0; yi += stepY) {
                for (double zi = 0; zi <= 1.0; zi += stepZ) {
                    Vec3 point = new Vec3(
                        box.minX + dx * xi + offsetX,
                        box.minY + dy * yi,
                        box.minZ + dz * zi + offsetZ
                    );
                    ctx.set(point, source);
                    if (BlockGetter.traverseBlocks(point, source, ctx, tester, c -> null) == null) unblocked++;
                    total++;
                }
            }
        }
        ClientDiagnostics.count("combat.GuangxiCrystal.rays", total);
        return total == 0 ? 0 : (double) unblocked / total;
    }

    private static final class ExposureContext {
        private Vec3 start = Vec3.ZERO;
        private Vec3 end = Vec3.ZERO;

        void set(Vec3 start, Vec3 end) {
            this.start = start;
            this.end = end;
        }
    }

    private double getDynamicMin(float health, float absorption, boolean armorBroken) {
        return CrystalSelectionPolicy.minimumDamage(minDamage.getValue(), health + absorption,
                armorBroken, false);
    }

    private double getPlaceMin(TargetCache target) {
        return CrystalSelectionPolicy.minimumDamage(minDamage.getValue(), target.hp + target.abs,
                target.armorBroken, isFacePlaceEligible(target));
    }

    private boolean isAnyArmorBroken(Player player) {
        int threshold = (int) (ARMOR_BALANCE * 100);
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            var stack = player.getItemBySlot(slot);
            if (stack.isEmpty() || stack.getMaxDamage() <= 0) continue;
            int durabilityPct = 100 - (stack.getDamageValue() * 100 / stack.getMaxDamage());
            if (durabilityPct < threshold) return true;
        }
        return false;
    }

    private Vec3 getClosestPointToEye(Vec3 eye, AABB box) {
        double x = eye.x, y = eye.y, z = eye.z;
        final double VEC = 1.0 / 16.0;
        final double EPS = 1e-9;

        if (eye.x < box.minX) x = box.minX;
        else if (eye.x > box.maxX) x = box.maxX;
        if (eye.y < box.minY) y = box.minY;
        else if (eye.y > box.maxY) y = box.maxY;
        if (eye.z < box.minZ) z = box.minZ;
        else if (eye.z > box.maxZ) z = box.maxZ;

        if (Math.abs(x - box.minX) < EPS) x = Math.min(box.minX + VEC, box.maxX - EPS);
        else if (Math.abs(x - box.maxX) < EPS) x = Math.max(box.maxX - VEC, box.minX + EPS);
        if (Math.abs(z - box.minZ) < EPS) z = Math.min(box.minZ + VEC, box.maxZ - EPS);
        else if (Math.abs(z - box.maxZ) < EPS) z = Math.max(box.maxZ - VEC, box.minZ + EPS);

        return new Vec3(x, y, z);
    }

    private Vec3 getLookVector(float yaw, float pitch) {
        float f = (float) Math.cos(-yaw * 0.017453292F - Math.PI);
        float g = (float) Math.sin(-yaw * 0.017453292F - Math.PI);
        float h = -(float) Math.cos(-pitch * 0.017453292F);
        float i = (float) Math.sin(-pitch * 0.017453292F);
        return new Vec3(g * h, i, f * h);
    }

    public float getLastBestDamage() {
        return lastBestDamage;
    }

    public double getMinDamage() {
        return minDamage.getValue();
    }

    @Override
    public String getDisplayInfo() {
        return lastBestDamage > 0 ? String.format("%.1f", lastBestDamage) : null;
    }

    private final java.util.ArrayDeque<Long> explodeTimes = new java.util.ArrayDeque<>();

    @Override
    public String getMeta() {
        long now = System.currentTimeMillis();
        while (!explodeTimes.isEmpty() && now - explodeTimes.peekFirst() > 1000) explodeTimes.removeFirst();
        return String.valueOf(explodeTimes.size());
    }

    private record PlaceTarget(BlockPos base, float damage, LivingEntity faceEntity) {
        PlaceTarget(BlockPos base, float damage) { this(base, damage, null); }
    }
    private record BasePlaceTarget(BlockPos base, float damage) {}
    private record PlaceCandidate(BlockPos base, BlockPos airPos, Vec3 crystalCenter, float bound) {}

    private static final float EXPLODABLE_RESISTANCE = 600.0f;

    private static final double ANTI_CHINESE_RADIUS = 3.0;

    private BlockState getStateFast(BlockPos pos) {
        if (mc.level.isOutsideBuildHeight(pos.getY())) return Blocks.VOID_AIR.defaultBlockState();
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        if (cachedChunkX != cx || cachedChunkZ != cz || cachedChunk == null) {
            cachedChunk = mc.level.getChunk(cx, cz);
            cachedChunkX = cx;
            cachedChunkZ = cz;
        }
        ChunkAccess chunk = cachedChunk;
        if (chunk == null) return Blocks.AIR.defaultBlockState();
        int sectionIdx = mc.level.getSectionIndex(pos.getY());
        LevelChunkSection[] sections = chunk.getSections();
        if (sectionIdx < 0 || sectionIdx >= sections.length) return Blocks.AIR.defaultBlockState();
        LevelChunkSection section = sections[sectionIdx];
        if (section == null || section.hasOnlyAir()) return Blocks.AIR.defaultBlockState();
        return section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    private Vec3 bestReachEye(AABB box) {
        Vec3 base = mc.player.getEyePosition(1.0f);
        float scale = (float) mc.player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.SCALE);
        double standing = 1.62 * scale;
        double sneaking = 1.27 * scale;
        double swimming = 0.4  * scale;

        Vec3 pos = mc.player.position();
        double[] heights;
        Pose pose = mc.player.getPose();
        switch (pose) {
            case FALL_FLYING:
            case SPIN_ATTACK:
            case SWIMMING:    heights = new double[]{swimming, standing, sneaking}; break;
            case CROUCHING:   heights = new double[]{sneaking, standing, swimming}; break;
            default:          heights = new double[]{standing, sneaking, swimming};
        }

        Vec3 bestEye = base;
        double bestSq = sqDistToBox(base, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        for (double h : heights) {
            Vec3 e = new Vec3(pos.x, pos.y + h, pos.z);
            double sq = sqDistToBox(e, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
            if (sq < bestSq) { bestSq = sq; bestEye = e; }
        }
        return bestEye;
    }
}
