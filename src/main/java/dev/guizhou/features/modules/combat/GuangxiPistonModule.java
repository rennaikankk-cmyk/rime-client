package dev.guizhou.features.modules.combat;

import dev.guizhou.GuizhouWare;
import net.minecraft.world.entity.ai.attributes.Attributes;
import dev.guizhou.event.impl.entity.player.TickEvent;
import dev.guizhou.event.impl.render.Render3DEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.client.TargetsModule;
import dev.guizhou.features.modules.world.GuangxiMineModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.RotationRequest;
import dev.guizhou.util.DamageSyncTracker;
import dev.guizhou.util.EnchantmentUtil;
import dev.guizhou.util.PacketSendUtil;
import dev.guizhou.util.combat.ExplosionDamageModel;
import dev.guizhou.util.combat.CombatSnapshotService;
import dev.guizhou.util.combat.CrystalRetryTracker;
import dev.guizhou.util.combat.PistonPowerSequence;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import dev.guizhou.util.MathUtil;
import dev.guizhou.util.PlaceUtil;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.inventory.ResultType;
import dev.guizhou.util.inventory.SwapMode;
import dev.guizhou.util.inventory.SwapMode;
import dev.guizhou.util.render.RenderUtil;
import dev.guizhou.util.render.placement.*;
import dev.guizhou.util.player.EatUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

public class GuangxiPistonModule extends Module {

    private static final String ROTATION_ID     = "PistonCrystal";
    private static final int    ROTATION_PRIORITY = 70;

    private static final double NUDGE = 1.0 / 16.0;

    private final Setting<Double> placeReach = num("PlaceRange", 4.5, 1.0, 6.0).setPage("General");
    private final Setting<Double> breakReach = num("BreakRange", 3.0, 0.5, 6.0).setPage("General");

    private final Setting<Double>  minDamage    = num("MinDamage",    6.0, 0.0, 36.0).setPage("General");
    private final Setting<Integer> delay        = num("Delay",       10, 0, 20).setPage("General");
    private final Setting<Boolean> autoBase     = bool("AutoBase",   true).setPage("General");
    private final Setting<Boolean> reuseRedstone = bool("ReuseRedstone", true).setPage("General");
    private final Setting<Boolean> inventorySwap = bool("InventorySwap", true).setPage("General");
    private final Setting<Boolean> rotate = bool("Rotate", true).setPage("General");
    private final dev.guizhou.manager.RotationOptions rotationOptions = new dev.guizhou.manager.RotationOptions(this, rotate);

    private final Setting<Double> maxSelfDamage = num("MaxSelfDamage", 8.0, 0.0, 36.0).setPage("General");
    private final Setting<Double> reserve = num("Reserve", 2.0, 0.0, 20.0).setPage("General");
    private final Setting<Double> targetRange = num("TargetRange", 10.0, 1.0, 16.0).setPage("General");
    private final Setting<Integer> actionTimeout = num("ActionTimeout", 60, 10, 200).setPage("General");
    private final Setting<Integer> retryDelay = num("RetryDelay", 4, 1, 20).setPage("General");
    private final Setting<Integer> attackWait = num("AttackWait", 2, 1, 20).setPage("General");
    private final Setting<Boolean> inAirTarget = bool("InAirTarget", true).setPage("General");
    private final Setting<Boolean> blockDestruction = bool("BlockDestruction", true).setPage("General");

    private final Setting<Boolean> damageSync   = bool("DamageSync", false).setPage("General");

    private final Setting<Boolean> render      = bool("Render",      true).setPage("Render");
    private final Setting<Float>   fadeTime    = num("FadeTime",     0.2f, 0.05f, 2.0f).setPage("Render");
    private final Setting<Color>   fillColor   = color("FillColor",  224, 138, 108, 148).setPage("Render");
    private final Setting<Color>   outlineColor = color("OutlineColor", 224, 138, 108, 148).setPage("Render");

    private final PlacementVisualOptions visualOptions = new PlacementVisualOptions(this, false, true, true);
    private final PlacementVisuals visuals = new PlacementVisuals("活塞水晶");
    private final Setting<Float> lineWidth = num("LineWidth", 1.5f, .5f, 5f).setPage("Render");
    private final Setting<Color> pistonColor = color("PistonColor", 90, 220, 240, 220).setPage("Render");
    private final Setting<Color> powerColor = color("PowerColor", 255, 195, 65, 220).setPage("Render");
    private final Setting<Boolean> pushArrow = bool("PushArrow", true).setPage("Render");
    private float visualSelf = Float.NaN;
    private Setup visualSetup;

    private Setup   pending;
    private Setup   active;
    private int     delayTicks;
    private int     rotateHeld;
    private int     waitTicks;
    private Stage stage = Stage.BASE;
    private int stageAttempts;
    private long lastStageSubmit = Long.MIN_VALUE;
    private int trackedCrystal = -1;
    private ClientLevel sessionLevel;
    private LocalPlayer sessionPlayer;
    private final CrystalRetryTracker attacks = new CrystalRetryTracker();
    private enum Stage { RESET_PISTON, BASE, PISTON, CRYSTAL, POWER, EXTEND, ATTACK, CONFIRM }
    private final Object powerMineOwner = new Object();
    private float   lastDamage;

    private ArmorProfile                     targetProfile;
    private net.minecraft.world.Difficulty   targetDifficulty;

    public GuangxiPistonModule() {
        super("GuangxiPiston",
              "Pushes a crystal over a surrounded target with a piston and explodes it.",
              Category.COMBAT);
    }

    @Override
    public void onEnable() {
        resetState();
        delayTicks = delay.getValue();
    }

    @Override
    public void onDisable() {
        GuizhouWare.rotationManager.cancel(ROTATION_ID);
        resetState();
    }

    private void resetState() {
        releasePowerMine();
        pending    = null;
        active     = null;
        delayTicks = 0;
        rotateHeld = 0;
        waitTicks  = 0;
        lastDamage = 0;
        visuals.clear();
        visualSetup = null;
        attacks.clear();
        trackedCrystal = -1;
        stageAttempts = 0;
        lastStageSubmit = Long.MIN_VALUE;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) { dev.guizhou.manager.HeartbeatManager.notifyDeniedOnce(); return; }
        if (sessionLevel != mc.level || sessionPlayer != mc.player) {
            GuizhouWare.rotationManager.cancel(ROTATION_ID);
            resetState();
            sessionLevel = mc.level;
            sessionPlayer = mc.player;
        }
        if (nullCheck() || mc.player.isDeadOrDying()) return;
        if (GuizhouWare.tpsCounterService.isRubberbandRecent()) { clearActive(); return; }
        selfProfile = profileOf(mc.player);
        attacks.expire(System.currentTimeMillis(), 5000);

        if (active != null) {
            // Timeouts still advance while resources are owned by another module.
            if (++waitTicks > actionTimeout.getValue() || !validTarget(active)) { clearActive(); return; }
            if (!EatUtil.shouldDefer()) tickActive();
            return;
        }
        if (EatUtil.shouldDefer()) return;
        if (delayTicks < delay.getValue()) { delayTicks++; return; }
        Setup setup = findSetup();
        if (setup == null) { clearPending(); return; }
        // Only an actual clearing attack consumes this tick; an unreachable crystal cannot stall planning forever.
        if (breakCrystalsAround(setup.head(), crystalAt(setup.crystal()))) return;
        active = setup;
        pending = null;
        waitTicks = 0;
        trackedCrystal = -1;
        enterStage(setup.recyclePiston() ? Stage.RESET_PISTON : Stage.BASE);
        tickActive();
    }

    private void clearPending() {
        GuizhouWare.rotationManager.cancel(ROTATION_ID);
        pending = null;
        rotateHeld = 0;
        lastDamage = 0;
    }

    private void clearActive() {
        if (active != null) {
            boolean removed = stage == Stage.CONFIRM && trackedCrystal >= 0 && mc.level != null
                    && (mc.level.getEntity(trackedCrystal) == null || mc.level.getEntity(trackedCrystal).isRemoved());
            visuals.stage("main", removed ? "DONE" : "STOP");
            visuals.info("main", "");
        }
        releasePowerMine();
        active = null;
        trackedCrystal = -1;
        waitTicks = 0;
        delayTicks = 0;
        clearPending();
    }

    private void releasePowerMine() {
        if (GuizhouWare.moduleManager == null) return;
        var mine = GuizhouWare.moduleManager.getModuleByClass(GuangxiMineModule.class);
        if (mine != null) mine.cancelBreakOnce(powerMineOwner);
    }

    private boolean validTarget(Setup setup) {
        var entity = mc.level.getEntity(setup.targetId());
        if (!(entity instanceof LivingEntity target) || !target.isAlive() || target.isRemoved()) return false;
        TargetsModule targets = GuizhouWare.moduleManager.getModuleByClass(TargetsModule.class);
        return (targets == null || targets.isValidPlayerTarget(target))
                && target.position().distanceToSqr(setup.targetOrigin()) <= 1.0
                && mc.player.distanceToSqr(target) <= targetRange.getValue() * targetRange.getValue();
    }

    private void enterStage(Stage next) {
        stage = next;
        showVisualSetup();
        stageAttempts = 0;
        lastStageSubmit = Long.MIN_VALUE;
    }

    private boolean canSubmit() {
        return stageAttempts < 3 && (lastStageSubmit == Long.MIN_VALUE
                || (long)mc.player.tickCount - lastStageSubmit >= retryDelay.getValue());
    }

    private void submitted(BlockPos pos) {
        stageAttempts++;
        lastStageSubmit = mc.player.tickCount;
        showVisualSetup();
    }

    private void tickActive() {
        if (active == null || !validTarget(active)) { clearActive(); return; }
        Setup setup = active;
        if (stage != Stage.BASE && stage != Stage.RESET_PISTON && needsBase(setup.base())) { clearActive(); return; }
        if (stage == Stage.POWER || stage == Stage.EXTEND
                || stage == Stage.CRYSTAL && !setup.keepPower()) {
            BlockState piston = mc.level.getBlockState(setup.piston());
            if (!(piston.is(Blocks.PISTON) || piston.is(Blocks.STICKY_PISTON))
                    || piston.getValue(BlockStateProperties.FACING) != setup.dir().getOpposite()) { clearActive(); return; }
        }
        if (trackedCrystal >= 0 && mc.level.getEntity(trackedCrystal) == null) { clearActive(); return; }
        if (!selfSafe(setup.explosion(), needsBase(setup.base()) ? setup.base() : null)) { clearActive(); return; }
        showVisualSetup();
        // Several already-observed stages may advance in one tick; a submitted packet never counts as observation.
        for (int steps = 0; steps < Stage.values().length && active != null; steps++) {
            BlockPos interaction = switch (stage) {
                case RESET_PISTON -> setup.piston();
                case BASE -> setup.base();
                case PISTON -> setup.piston();
                case CRYSTAL -> setup.base();
                case POWER -> setup.keepPower() ? null : setup.redstone();
                default -> null;
            };
            if (interaction != null && mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(interaction))
                    > placeReach.getValue() * placeReach.getValue()) { clearActive(); return; }
            switch (stage) {
                case RESET_PISTON -> {
                    BlockState piston = mc.level.getBlockState(setup.piston());
                    var next = PistonPowerSequence.resetPiston(matchingPiston(piston, setup.dir()), piston.isAir(),
                            mc.level.getBlockState(setup.piston().relative(setup.dir().getOpposite())).isAir(),
                            mc.level.getBlockState(setup.redstone()).is(Blocks.REDSTONE_BLOCK));
                    if (next == PistonPowerSequence.Action.ABORT) { clearActive(); return; }
                    if (next == PistonPowerSequence.Action.WAIT) return;
                    if (next == PistonPowerSequence.Action.READY) {
                        releasePowerMine();
                        enterStage(Stage.BASE);
                        continue;
                    }
                    var mine = GuizhouWare.moduleManager.getModuleByClass(GuangxiMineModule.class);
                    if (mine == null || !mine.isAvailable() || !mine.inMineRange(setup.piston())) { clearActive(); return; }
                    if (canSubmit() && mine.requestBreakOnce(setup.piston(), powerMineOwner)) submitted(setup.piston());
                    return;
                }
                case BASE -> {
                    if (!needsBase(setup.base())) { enterStage(setup.keepPower() ? Stage.CRYSTAL : Stage.PISTON); continue; }
                    if (canSubmit() && placeStrictBlock(setup.base(), Items.OBSIDIAN, null)) submitted(setup.base());
                    return;
                }
                case CRYSTAL -> {
                    BlockState piston = mc.level.getBlockState(setup.piston());
                    if (setup.keepPower()) {
                        // An observed crystal must precede placement into an already powered cell.
                        if (!piston.isAir() || !mc.level.getBlockState(setup.redstone()).is(Blocks.REDSTONE_BLOCK)) {
                            clearActive(); return;
                        }
                    } else if (mc.level.hasNeighborSignal(setup.piston())
                            || piston.getValue(BlockStateProperties.EXTENDED)) { clearActive(); return; }
                    EndCrystal crystal = crystalAt(setup.crystal());
                    if (crystal != null) {
                        trackedCrystal = crystal.getId();
                        enterStage(setup.keepPower() ? Stage.PISTON : Stage.POWER);
                        continue;
                    }
                    if (mc.level.getBlockState(setup.crystal()).is(Blocks.FIRE)) {
                        clearFire(setup.crystal());
                        return;
                    }
                    if (canSubmit() && aimInteraction(GuizhouWare.placementManager.prepareInteractionHit(setup.base(), null,
                            mc.player.getEyePosition(), placeReach.getValue(), 0.0, false), null)
                            && placePistonCrystal(setup.base())) submitted(setup.crystal());
                    return;
                }
                case PISTON -> {
                    BlockState state = mc.level.getBlockState(setup.piston());
                    if (state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON)) {
                        if (!state.hasProperty(BlockStateProperties.FACING)
                                || state.getValue(BlockStateProperties.FACING) != setup.dir().getOpposite()) { clearActive(); return; }
                        enterStage(setup.keepPower() ? Stage.POWER : Stage.CRYSTAL); continue;
                    }
                    if (setup.keepPower()) {
                        if (!mc.level.getBlockState(setup.redstone()).is(Blocks.REDSTONE_BLOCK)
                                || !(mc.level.getEntity(trackedCrystal) instanceof EndCrystal crystal)
                                || crystal != crystalAt(setup.crystal())
                                || !mc.level.getBlockState(setup.piston().relative(setup.dir().getOpposite())).isAir()) {
                            clearActive(); return;
                        }
                    }
                    int slot = pistonSlot();
                    if (canSubmit() && slot >= 0) {
                        Item item = mc.player.getInventory().getItem(slot).getItem();
                        if (placeStrictBlock(setup.piston(), item, setup.dir())) submitted(setup.piston());
                    }
                    return;
                }
                case POWER -> {
                    if (mc.level.getBlockState(setup.redstone()).is(Blocks.REDSTONE_BLOCK)) { enterStage(Stage.EXTEND); continue; }
                    if (setup.keepPower()) { clearActive(); return; }
                    if (canSubmit() && placeStrictBlock(setup.redstone(), Items.REDSTONE_BLOCK, null)) submitted(setup.redstone());
                    return;
                }
                case EXTEND -> {
                    BlockState state = mc.level.getBlockState(setup.piston());
                    var crystal = mc.level.getEntity(trackedCrystal);
                    if (state.hasProperty(BlockStateProperties.EXTENDED) && state.getValue(BlockStateProperties.EXTENDED)
                            && crystal instanceof EndCrystal && PistonPowerSequence.pushed(
                                    Vec3.atBottomCenterOf(setup.crystal()), crystal.position(), setup.dir())) {
                        enterStage(Stage.ATTACK); continue;
                    }
                    return;
                }
                case ATTACK -> {
                    var entity = mc.level.getEntity(trackedCrystal);
                    if (!(entity instanceof EndCrystal crystal) || !crystal.isAlive()) { clearActive(); return; }
                    var victim = mc.level.getEntity(setup.targetId());
                    if (!(victim instanceof LivingEntity living) || calcDamage(living, crystal.position(), null) < minDamage.getValue()) {
                        clearActive(); return;
                    }
                    if (breakCrystal(crystal)) { enterStage(Stage.CONFIRM); lastStageSubmit = mc.player.tickCount; }
                    return;
                }
                case CONFIRM -> {
                    var entity = mc.level.getEntity(trackedCrystal);
                    if (entity == null || entity.isRemoved()) { clearActive(); return; }
                    if ((long)mc.player.tickCount - lastStageSubmit >= attackWait.getValue()) enterStage(Stage.ATTACK);
                    return;
                }
            }
        }
    }

    /** Visible face and piston orientation must be achievable by the same submitted rotation. */
    private boolean aimInteraction(net.minecraft.world.phys.BlockHitResult hit, Direction facing) {
        if (hit == null) return false;
        float[] angles = MathUtil.calcAngle(mc.player.getEyePosition(), hit.getLocation());
        if (facing != null && Direction.fromYRot(angles[0]) != facing) return false;
        if (rotate.getValue() && !GuizhouWare.rotationManager.tryAction(rotationOptions.request(
                ROTATION_ID, ROTATION_PRIORITY, angles[0], angles[1], RotationRequest.Mode.SILENT))) return false;
        float yaw = GuizhouWare.rotationManager.getServerYaw(), pitch = GuizhouWare.rotationManager.getServerPitch();
        return Math.abs(Mth.wrapDegrees(yaw - angles[0])) < 2.0 && Math.abs(pitch - angles[1]) < 2.0;
    }

    private boolean placeStrictBlock(BlockPos pos, Item item, Direction facing) {
        var manager = GuizhouWare.placementManager;
        net.minecraft.world.phys.BlockHitResult hit = null;
        for (Direction side : Direction.values()) {
            var candidate = manager.preparePlaceHit(pos, side, false, placeReach.getValue(), 0.0);
            if (candidate == null) continue;
            float[] angles = MathUtil.calcAngle(mc.player.getEyePosition(), candidate.getLocation());
            if (facing == null || Direction.fromYRot(angles[0]) == facing) { hit = candidate; break; }
        }
        if (!aimInteraction(hit, facing)) return false;
        var prepared = hit;
        boolean[] sent = {false};
        boolean swapped = GuizhouWare.swapManager.withSwap(item, itemScope(),
                swapMode(), dev.guizhou.util.inventory.SwapPriority.PLACEMENT,
                () -> {
                    if (PlaceUtil.canPlace(pos, placeReach.getValue())) sent[0] = manager.sendUseItemOnDirect(prepared,
                            InteractionHand.MAIN_HAND, mc.player.getEyePosition(), placeReach.getValue(), 0.0);
                });
        if (swapped && sent[0]) manager.notePlacement(pos);
        return swapped && sent[0];
    }

    private EndCrystal crystalAt(BlockPos pos) {
        for (var entity : mc.level.getEntities(null, new AABB(pos))) {
            if (entity instanceof EndCrystal crystal && crystal.isAlive() && !crystal.isRemoved()
                    && crystal.position().distanceToSqr(new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5)) < 0.25) return crystal;
        }
        return null;
    }

    private boolean breakCrystalsAround(BlockPos head, EndCrystal retained) {
        for (var entity : mc.level.getEntities(null, new AABB(head).inflate(1.0))) {
            if (entity instanceof EndCrystal crystal && crystal != retained && breakCrystal(crystal)) return true;
        }
        return false;
    }

    private boolean breakCrystal(EndCrystal crystal) {
        if (mc.gameMode == null || !crystal.isAlive() || crystal.isRemoved() || !selfSafe(crystal.position(), null)) return false;
        if (!dev.guizhou.util.combat.CrystalWeaponDamage.canDamage(mc.player.getAttribute(Attributes.ATTACK_DAMAGE),
                mc.player.getMainHandItem(), mc.player.getMainHandItem())) return false;
        long now = System.currentTimeMillis();
        if (!attacks.canAttempt(crystal.getId(), now, attackWait.getValue() * 50L, 3, 500)) return false;
        Vec3 eye = mc.player.getEyePosition();
        float[] angles = MathUtil.calcAngle(eye, closestPointOnBox(eye, crystal.getBoundingBox()));
        if (!canHitCrystal(crystal, angles[0], angles[1])) return false;
        if (rotate.getValue() && !GuizhouWare.rotationManager.tryAction(rotationOptions.request(
                "PistonCrystal_break", ROTATION_PRIORITY, angles[0], angles[1], RotationRequest.Mode.SILENT))) return false;
        if (!canHitCrystal(crystal, GuizhouWare.rotationManager.getServerYaw(), GuizhouWare.rotationManager.getServerPitch())) return false;
        if (!PacketSendUtil.send(ServerboundInteractPacket.createAttackPacket(crystal, mc.player.isShiftKeyDown()))) return false;
        attacks.markSent(crystal.getId(), now, 500);
        mc.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private boolean canHitCrystal(EndCrystal crystal, float yaw, float pitch) {
        AABB box = crystal.getBoundingBox();
        Vec3 eye = mc.player.getEyePosition();
        if (box.contains(eye)) return true;
        var hit = box.clip(eye, eye.add(getLookVector(yaw, pitch).scale(breakReach.getValue())));
        if (hit.isEmpty()) return false;
        var obstruction = mc.level.clip(new ClipContext(eye, hit.get(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return obstruction.getType() == HitResult.Type.MISS
                || eye.distanceToSqr(obstruction.getLocation()) + 1.0E-6 >= eye.distanceToSqr(hit.get());
    }

    private boolean selfSafe(Vec3 explosion, BlockPos phantomBase) {
        float damage = calcDamage(mc.player, explosion, phantomBase);
        visualSelf = damage;
        return Float.isFinite(damage) && damage <= maxSelfDamage.getValue()
                && mc.player.getHealth() + mc.player.getAbsorptionAmount() - damage >= reserve.getValue()
                && damage < mc.player.getHealth() + mc.player.getAbsorptionAmount();
    }

    private Setup findSetup() {
        TargetsModule targets = GuizhouWare.moduleManager.getModuleByClass(TargetsModule.class);
        java.util.List<Player> ordered = new java.util.ArrayList<>(mc.level.players());
        ordered.sort(java.util.Comparator.comparingDouble(mc.player::distanceToSqr));
        for (Player target : ordered) {
            if (target == mc.player || target.isDeadOrDying() || target.isSpectator()
                    || mc.player.distanceToSqr(target) > targetRange.getValue() * targetRange.getValue()) continue;
            if (targets != null && !targets.isValidPlayerTarget(target)) continue;
            if ((!inAirTarget.getValue() && !target.onGround()) || isPhased(target)) continue;
            if (damageSync.getValue() && DamageSyncTracker.shouldHold(target)) continue;
            Setup setup = findSetupFor(target);
            if (setup != null) { lastDamage = setup.damage(); return setup; }
        }
        return null;
    }

    private Setup findSetupFor(LivingEntity target) {
        targetProfile = profileOf(target);
        targetDifficulty = mc.level.getDifficulty();

        Vec3  eye   = mc.player.getEyePosition();
        double range = placeReach.getValue();

        AABB bb = target.getBoundingBox();
        int minX = Mth.floor(bb.minX), maxX = Mth.floor(bb.maxX - 1e-7);
        int minY = Mth.floor(bb.minY), maxY = Mth.floor(bb.maxY - 1e-7);
        int minZ = Mth.floor(bb.minZ), maxZ = Mth.floor(bb.maxZ - 1e-7);

        Setup best = null;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        boolean lowPose = target.getBbHeight() <= 1.0f;

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    cursor.set(bx, by, bz);
                    BlockPos cell = cursor.immutable();
                    BlockPos[] heads = lowPose
                            ? new BlockPos[]{cell, cell.above()}
                            : new BlockPos[]{cell.above()};

                    for (BlockPos head : heads) {
                        for (Direction dir : Direction.Plane.HORIZONTAL) {
                            for (int gap = 1; gap <= 2; gap++) {
                                Setup side = sideSetup(target, head, dir, eye, range, gap, best);
                                if (betterSetup(side, best)) best = side;
                                Setup top = topSetup(target, head, dir, eye, range, gap, best);
                                if (betterSetup(top, best)) best = top;
                            }
                        }
                    }
                }
            }
        }

        lastDamage = best != null ? best.damage() : 0;
        return best;
    }

    private static boolean betterSetup(Setup candidate, Setup current) {
        if (candidate == null) return false;
        if (current == null) return true;
        // All candidates already satisfy damage, reach and self-safety. Prefer using scene resources.
        if (candidate.keepPower() != current.keepPower()) return candidate.keepPower();
        if (candidate.recyclePiston() != current.recyclePiston()) return !candidate.recyclePiston();
        return candidate.damage() > current.damage();
    }

    private Setup sideSetup(LivingEntity target, BlockPos head, Direction dir,
                            Vec3 eye, double range, int gap, Setup best) {
        BlockPos crystalPos = head.relative(dir);
        BlockPos base       = crystalPos.below();
        BlockPos pistonPos  = crystalPos.relative(dir, gap);

        boolean placeBase = needsBase(base);
        if (placeBase && !canAutoBase(base)) return null;
        if (!crystalCellOpen(crystalPos, pistonPos, dir)) return null;
        if (!mc.level.getBlockState(head).isAir()) return null;

        Vec3 explosionPos = gap == 2 ? PistonPowerSequence.predictedPush(crystalPos, dir)
                : new Vec3(head.getX() + 0.5, head.getY(), head.getZ() + 0.5);
        return buildSetup(target, head, dir, crystalPos, base, pistonPos,
                          explosionPos, eye, range, placeBase, best);
    }

    private Setup topSetup(LivingEntity target, BlockPos head, Direction dir,
                           Vec3 eye, double range, int gap, Setup best) {
        BlockPos base       = head.relative(dir);
        BlockPos crystalPos = base.above();
        BlockPos pistonPos  = crystalPos.relative(dir, gap);

        boolean placeBase = needsBase(base);
        if (placeBase && !canAutoBase(base)) return null;
        if (!crystalCellOpen(crystalPos, pistonPos, dir)) return null;

        BlockPos dest = head.above();
        Vec3 explosionPos = gap == 2 ? PistonPowerSequence.predictedPush(crystalPos, dir)
                : mc.level.getBlockState(dest).isAir()
                ? new Vec3(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5)
                : new Vec3(crystalPos.getX() + 0.5 - dir.getStepX() * 0.5,
                           crystalPos.getY(),
                           crystalPos.getZ() + 0.5 - dir.getStepZ() * 0.5);
        return buildSetup(target, head, dir, crystalPos, base, pistonPos,
                          explosionPos, eye, range, placeBase, best);
    }

    private boolean needsBase(BlockPos base) {
        var s = mc.level.getBlockState(base);
        return !s.is(Blocks.OBSIDIAN) && !s.is(Blocks.BEDROCK);
    }

    private boolean crystalCellOpen(BlockPos cell, BlockPos piston, Direction dir) {
        BlockState cellState = mc.level.getBlockState(cell);
        if (!cellState.isAir() && !cellState.is(Blocks.FIRE)) return false;
        BlockPos head = piston.relative(dir.getOpposite());
        if (head.equals(cell)) return true;
        BlockState state = mc.level.getBlockState(head);
        return state.isAir() || reuseRedstone.getValue() && state.is(Blocks.PISTON_HEAD)
                && state.getValue(BlockStateProperties.FACING) == dir.getOpposite()
                && matchingPiston(mc.level.getBlockState(piston), dir);
    }

    private static boolean matchingPiston(BlockState state, Direction dir) {
        return (state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON))
                && state.getValue(BlockStateProperties.FACING) == dir.getOpposite();
    }

    private boolean canAutoBase(BlockPos base) {
        return autoBase.getValue()
                && itemSlot(Items.OBSIDIAN) >= 0
                && PlaceUtil.canPlace(base, placeReach.getValue());
    }

    private Setup buildSetup(LivingEntity target, BlockPos head, Direction dir,
                             BlockPos crystalPos, BlockPos base, BlockPos pistonPos,
                             Vec3 explosionPos, Vec3 eye, double range,
                             boolean placeBase, Setup best) {
        BlockState pistonState = mc.level.getBlockState(pistonPos);
        boolean existingPiston = (pistonState.is(Blocks.PISTON) || pistonState.is(Blocks.STICKY_PISTON))
                && pistonState.hasProperty(BlockStateProperties.FACING)
                && pistonState.getValue(BlockStateProperties.FACING) == dir.getOpposite();
        if (!existingPiston && (!PlaceUtil.canPlace(pistonPos, placeReach.getValue()) || pistonSlot() < 0)) return null;
        if (crystalAt(crystalPos) == null && !hasCrystalSupply()) return null;

        double rangeSq = range * range;
        if (eye.distanceToSqr(Vec3.atCenterOf(pistonPos)) > rangeSq) return null;
        if (eye.distanceToSqr(Vec3.atCenterOf(base))      > rangeSq) return null;

        AABB crystalBox = new AABB(
                explosionPos.x - 1, explosionPos.y,     explosionPos.z - 1,
                explosionPos.x + 1, explosionPos.y + 2, explosionPos.z + 1);
        double breakRangeSq = breakReach.getValue() * breakReach.getValue();
        if (distSqToBox(eye, crystalBox) > breakRangeSq) return null;

        float upper = optimisticDamage(target, explosionPos);
        if (upper < minDamage.getValue()) return null;

        RedstoneSpot redstone = findRedstoneSpot(pistonPos, dir, explosionPos, eye, rangeSq);
        if (redstone == null) return null;
        boolean keepPower = !redstone.place();
        int gap = pistonPos.distManhattan(crystalPos);
        boolean canRecycle = false;
        if (keepPower && existingPiston) {
            var mine = GuizhouWare.moduleManager.getModuleByClass(GuangxiMineModule.class);
            canRecycle = mine != null && mine.isAvailable() && mine.hasFreePrimary()
                    && !mine.isMining(pistonPos) && mine.inMineRange(pistonPos) && pistonSlot() >= 0;
        }
        var plan = PistonPowerSequence.plan(keepPower, reuseRedstone.getValue(), existingPiston, gap, canRecycle);
        if (plan == PistonPowerSequence.Plan.REJECT) return null;
        if (!keepPower && (itemSlot(Items.REDSTONE_BLOCK) < 0 || mc.level.hasNeighborSignal(pistonPos)
                || existingPiston && pistonState.getValue(BlockStateProperties.EXTENDED))) return null;
        if (keepPower && !PistonPowerSequence.clearForPiston(crystalPos, pistonPos)) return null;
        // The upper bound must obey the same resource priorities as the final choice.
        Setup bound = new Setup(dir, pistonPos, redstone.pos(), crystalPos, base, head,
                keepPower, plan == PistonPowerSequence.Plan.RECYCLE_PISTON,
                placeBase, upper, target.getId(), target.position(), explosionPos);
        if (!betterSetup(bound, best)) return null;
        float damage = calcDamage(target, explosionPos, placeBase ? base : null);
        if (damage < minDamage.getValue() || !selfSafe(explosionPos, placeBase ? base : null)) return null;


        return new Setup(dir, pistonPos, redstone.pos(), crystalPos, base, head,
                         keepPower, plan == PistonPowerSequence.Plan.RECYCLE_PISTON,
                         placeBase, damage, target.getId(), target.position(), explosionPos);
    }

    private RedstoneSpot findRedstoneSpot(BlockPos pistonPos, Direction dir,
                                          Vec3 explosionPos, Vec3 eye, double rangeSq) {
        BlockPos above       = pistonPos.above();
        BlockPos inDir       = pistonPos.relative(dir);
        BlockPos cw          = pistonPos.relative(dir.getClockWise());
        BlockPos ccw         = pistonPos.relative(dir.getCounterClockWise());
        BlockPos below       = pistonPos.below();

        if (mc.level.getBlockState(above).is(Blocks.REDSTONE_BLOCK)) return new RedstoneSpot(above, false);
        if (mc.level.getBlockState(inDir).is(Blocks.REDSTONE_BLOCK)) return new RedstoneSpot(inDir, false);
        if (mc.level.getBlockState(cw).is(Blocks.REDSTONE_BLOCK))    return new RedstoneSpot(cw,    false);
        if (mc.level.getBlockState(ccw).is(Blocks.REDSTONE_BLOCK))   return new RedstoneSpot(ccw,   false);
        if (mc.level.getBlockState(below).is(Blocks.REDSTONE_BLOCK)) return new RedstoneSpot(below, false);

        BlockPos fallback = null;
        for (BlockPos pos : new BlockPos[]{above, inDir, cw, ccw, below}) {
            if (!PlaceUtil.canPlace(pos, placeReach.getValue())) continue;
            if (eye.distanceToSqr(Vec3.atCenterOf(pos)) > rangeSq) continue;
            if (redstoneSafe(pos, explosionPos)) return new RedstoneSpot(pos, true);
            if (fallback == null) fallback = pos;
        }
        return fallback == null ? null : new RedstoneSpot(fallback, true);
    }

    private final BlockPos.MutableBlockPos rayCursor = new BlockPos.MutableBlockPos();

    private boolean redstoneSafe(BlockPos pos, Vec3 explosionPos) {
        Vec3 to = Vec3.atCenterOf(pos);
        if (explosionPos.distanceToSqr(to) > 36.0) return true;
        Vec3 diff  = to.subtract(explosionPos);
        int  steps = (int) Math.ceil(diff.length() / 0.25);
        long last  = Long.MIN_VALUE;
        for (int i = 1; i < steps; i++) {
            double s = (double) i / steps;
            rayCursor.set(Mth.floor(explosionPos.x + diff.x * s),
                          Mth.floor(explosionPos.y + diff.y * s),
                          Mth.floor(explosionPos.z + diff.z * s));
            long key = rayCursor.asLong();
            if (key == last) continue;
            last = key;
            if (rayCursor.equals(pos)) break;
            if (mc.level.getBlockState(rayCursor).getBlock().getExplosionResistance() >= 600.0f)
                return true;
        }
        return false;
    }

    private double calcExposure(Vec3 source, AABB box, BlockPos phantomBase, boolean projectedSelf) {
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

        for (double xi = 0; xi <= 1.0; xi += stepX) {
            for (double yi = 0; yi <= 1.0; yi += stepY) {
                for (double zi = 0; zi <= 1.0; zi += stepZ) {
                    Vec3 point = new Vec3(
                            box.minX + dx * xi + offsetX,
                            box.minY + dy * yi,
                            box.minZ + dz * zi + offsetZ);
                    if (!explosionBlocked(point, source, phantomBase, projectedSelf)) unblocked++;
                    total++;
                }
            }
        }
        return total == 0 ? 0 : (double) unblocked / total;
    }

    private boolean explosionBlocked(Vec3 from, Vec3 to, BlockPos phantomBase, boolean projectedSelf) {
        return net.minecraft.world.level.BlockGetter.traverseBlocks(from, to, phantomBase, (ignored, pos) -> {
            if (pos.equals(phantomBase)) return net.minecraft.world.phys.shapes.Shapes.block().clip(from, to, pos);
            BlockState state = mc.level.getBlockState(pos);
            if (state.isAir() || (projectedSelf && blockDestruction.getValue() && state.getBlock().getExplosionResistance() < 600.0f)) return null;
            VoxelShape shape = state.getCollisionShape(mc.level, pos);
            return shape.isEmpty() ? null : shape.clip(from, to, pos);
        }, ignored -> null) != null;
    }

    private boolean isPhased(LivingEntity target) {
        AABB bb = target.getBoundingBox().deflate(0.001);
        int minX = Mth.floor(bb.minX), maxX = Mth.floor(bb.maxX);
        int minY = Mth.floor(bb.minY), maxY = Mth.floor(bb.maxY);
        int minZ = Mth.floor(bb.minZ), maxZ = Mth.floor(bb.maxZ);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    BlockState state = mc.level.getBlockState(cursor);
                    if (state.isAir()) continue;
                    VoxelShape shape = state.getCollisionShape(mc.level, cursor);
                    if (shape.isEmpty()) continue;
                    if (shape.bounds().move(x, y, z).intersects(bb)) return true;
                }
            }
        }
        return false;
    }

    private float calcDamage(LivingEntity target, Vec3 explosionPos, BlockPos phantomBase) {

        double distSq = target.position().distanceToSqr(explosionPos);
        if (distSq > 144.0) return 0;
        double exposure = calcExposure(explosionPos, target.getBoundingBox(), phantomBase, target == mc.player);
        double impact = (1.0 - Math.sqrt(distSq) / 12.0) * exposure;

        float damage = (float)((impact * impact + impact) / 2.0 * 7.0 * 12.0 + 1.0);
        switch (mc.level.getDifficulty()) {
            case PEACEFUL -> damage = 0;
            case EASY -> damage = Math.min(damage / 2f + 1f, damage);
            case HARD -> damage *= 1.5f;
            default   -> {}
        }

        ArmorProfile p = profileOf(target);
        return ExplosionDamageModel.reduce(damage, p.armor(), p.toughness(), p.resistanceMul(), p.protPoints());
    }

    private ArmorProfile profileOf(LivingEntity target) {
        var snapshot = CombatSnapshotService.get(target);
        return new ArmorProfile(snapshot.armor(), snapshot.toughness(), snapshot.resistanceMultiplier(), snapshot.protection());
    }

    private Vec3 closestPointOnBox(Vec3 eye, AABB box) {
        boolean minX = eye.x < box.minX;
        boolean maxX = eye.x > box.maxX;
        boolean minZ = eye.z < box.minZ;
        boolean maxZ = eye.z > box.maxZ;

        double x = Mth.clamp(eye.x, box.minX, box.maxX);
        double y = Mth.clamp(eye.y, box.minY, box.maxY);
        double z = Mth.clamp(eye.z, box.minZ, box.maxZ);

        if      (minX) x += NUDGE;
        else if (maxX) x -= NUDGE;

        if      (minZ) z += NUDGE;
        else if (maxZ) z -= NUDGE;

        return new Vec3(x, y, z);
    }

    private static double distSqToBox(Vec3 p, AABB box) {
        double dx = p.x < box.minX ? box.minX - p.x : (p.x > box.maxX ? p.x - box.maxX : 0);
        double dy = p.y < box.minY ? box.minY - p.y : (p.y > box.maxY ? p.y - box.maxY : 0);
        double dz = p.z < box.minZ ? box.minZ - p.z : (p.z > box.maxZ ? p.z - box.maxZ : 0);
        return dx * dx + dy * dy + dz * dz;
    }

    private Vec3 getLookVector(float yaw, float pitch) {
        float f = (float) Math.cos(-yaw * 0.017453292F - Math.PI);
        float g = (float) Math.sin(-yaw * 0.017453292F - Math.PI);
        float h = -(float) Math.cos(-pitch * 0.017453292F);
        float k = (float) Math.sin(-pitch * 0.017453292F);
        return new Vec3(g * h, k, f * h);
    }

    /** Exposure 1 upper bound. Exact rays run only when this can beat the current best. */
    private float optimisticDamage(LivingEntity target, Vec3 explosionPos) {
        double distSq = target.position().distanceToSqr(explosionPos);
        if (distSq > 144.0) return 0f;
        double impact = 1.0 - Math.sqrt(distSq) / 12.0;
        if (impact <= 0) return 0f;
        float damage = (float) ((impact * impact + impact) / 2.0 * 7.0 * 12.0 + 1.0);
        switch (mc.level.getDifficulty()) {
            case PEACEFUL -> damage = 0f;
            case EASY -> damage = Math.min(damage / 2f + 1f, damage);
            case HARD -> damage *= 1.5f;
            default -> { }
        }
        ArmorProfile profile = target == mc.player ? (selfProfile == null ? profileOf(target) : selfProfile) : targetProfile;
        return ExplosionDamageModel.reduce(damage, profile.armor(), profile.toughness(), profile.resistanceMul(), profile.protPoints());
    }

    private int pistonSlot() {
        int s = itemSlot(Items.PISTON);
        return s >= 0 ? s : itemSlot(Items.STICKY_PISTON);
    }

    private SwapMode swapMode() {
        return inventorySwap.getValue() ? SwapMode.ALTSILENT : SwapMode.SILENT;
    }

    private java.util.EnumSet<ResultType> itemScope() {
        return inventorySwap.getValue() ? InventoryUtil.FULL_SCOPE : InventoryUtil.HOTBAR_SCOPE;
    }

    private int itemSlot(Item item) {
        var r = InventoryUtil.find(item, itemScope());
        return (r.found() && r.type() != ResultType.OFFHAND) ? r.slot() : -1;
    }

    private boolean hasCrystalSupply() {
        return mc.player.getMainHandItem().is(Items.END_CRYSTAL)
                || mc.player.getOffhandItem().is(Items.END_CRYSTAL)
                || itemSlot(Items.END_CRYSTAL) >= 0;
    }

    private boolean placePistonCrystal(BlockPos base) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return false;
        var options = new dev.guizhou.manager.PlacementManager.CrystalOptions(
                placeReach.getValue(), 0.0, false);
        boolean main = mc.player.getMainHandItem().is(Items.END_CRYSTAL);
        boolean off = mc.player.getOffhandItem().is(Items.END_CRYSTAL);
        if (main || off) {
            return GuizhouWare.placementManager.placeCrystalDirect(base, false,
                    off && !main ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, null, options);
        }
        return GuizhouWare.placementManager.placeCrystal(base, false,
                swapMode(), itemScope(), null, options);
    }

    /** Fire in the crystal cell is broken with the same instant dig CrystalAura uses, then placed next tick. */
    private void clearFire(BlockPos cell) {
        if (mc.getConnection() == null || !mc.level.getBlockState(cell).is(Blocks.FIRE)) return;
        mc.getConnection().send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, cell, Direction.DOWN));
        mc.getConnection().send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, cell, Direction.DOWN));
    }

    private void showVisualSetup() {
        if (active == null || !render.getValue()) return;
        visualSetup = active;
        Setup s = active;
        String text = switch(stage) {
            case RESET_PISTON -> "RESET";
            case BASE -> "BASE";
            case PISTON -> "PISTON";
            case CRYSTAL -> "CRYSTAL";
            case POWER -> s.keepPower() ? "R=" : "POWER";
            case EXTEND -> "PUSH";
            case ATTACK -> "HIT";
            case CONFIRM -> "WAIT";
        };
        visuals.main(s.base(), new AABB(s.base()), Vec3.atCenterOf(s.base()), s.damage(), visualSelf,
                text);
        String piston = mc.level.getBlockState(s.piston()).isAir() ? "P?" : "P+";
        String power = s.keepPower() ? "R="
                : mc.level.getBlockState(s.redstone()).is(Blocks.REDSTONE_BLOCK) ? "R+" : "R?";
        visuals.mark("piston", new AABB(s.piston()), s.piston(), Vec3.atCenterOf(s.piston()),
                Float.NaN, Float.NaN, piston, false, pistonColor.getValue());
        visuals.mark("power", new AABB(s.redstone()), s.redstone(), Vec3.atCenterOf(s.redstone()),
                Float.NaN, Float.NaN, power, false, powerColor.getValue());
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (!render.getValue()) { visuals.clear(); visualSetup = null; return; }
        visuals.render(event, visualOptions, fillColor.getValue(), outlineColor.getValue(), lineWidth.getValue(), fadeTime.getValue());
        if (active != null && visualSetup == active && pushArrow.getValue() && !nullCheck()) {
            Vec3 from = Vec3.atCenterOf(active.piston()).add(0,.65,0);
            Vec3 toward = Vec3.atCenterOf(active.crystal()).add(0,.65,0);
            visuals.arrow(from, toward, pistonColor.getValue(), lineWidth.getValue(), visualOptions);
        }
    }

    @Override
    public String getDisplayInfo() {
        return lastDamage > 0 ? String.format("%.1f", lastDamage) : null;
    }

    private record Setup(Direction dir, BlockPos piston, BlockPos redstone,
                         BlockPos crystal, BlockPos base, BlockPos head,
                         boolean keepPower, boolean recyclePiston, boolean placeBase, float damage, int targetId, Vec3 targetOrigin, Vec3 explosion) {}

    private record RedstoneSpot(BlockPos pos, boolean place) {}

    private ArmorProfile selfProfile;

    private record ArmorProfile(float armor, float toughness, float resistanceMul, int protPoints) {}
}
