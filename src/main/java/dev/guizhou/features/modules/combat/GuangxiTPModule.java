package dev.guizhou.features.modules.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.render.Render3DEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.client.TargetsModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.RotationRequest;
import dev.guizhou.util.DamageUtil;
import dev.guizhou.util.PacketSendUtil;
import java.util.function.BooleanSupplier;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.inventory.SwapMode;
import dev.guizhou.util.inventory.SwapPriority;
import dev.guizhou.util.models.Timer;
import dev.guizhou.util.render.RenderUtil;
import dev.guizhou.util.render.placement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;

/**
 * TP crystal: long-range crystal placement/breaking while far above the target, by
 * sandwiching the interaction between a fake move packet (near the ground base) and a
 * restore move packet. Only works on servers with lenient move-distance validation
 * (2b2t-style anarchy); strict vanilla/Grim servers reject or flag the desync move.
 */
public class GuangxiTPModule extends Module {

    private final Setting<Boolean> place     = bool("Place", true);
    private final Setting<Boolean> doBreak   = bool("Break", true);
    private final Setting<Double>  tpRange   = num("TpRange", 200.0, 16.0, 512.0);
    private final Setting<Double>  scanRange = num("TargetScan", 4.0, 1.0, 8.0);
    private final Setting<Double>  minDamage = num("MinDamage", 6.0, 0.0, 36.0);
    private final Setting<Double>  maxSelf   = num("MaxSelfDamage", 8.0, 0.0, 36.0);
    private final Setting<Integer> placeDelay = num("PlaceDelay", 250, 0, 2000);
    private final Setting<Integer> breakDelay = num("BreakDelay", 250, 0, 2000);
    private final Setting<Boolean> basePlace  = bool("BasePlace", true);
    private final Setting<Integer> baseDelay  = num("BaseDelay", 1000, 0, 5000);
    private final Setting<Boolean> rotate     = bool("Rotate", true);
    /** Stretches delays by measured TPS and pauses after a server rubberband (Mio TpsSync). */
    private final Setting<Boolean> tpsSync    = bool("TpsSync", true);

    /**
     * 1.12 mode: legacy servers do not validate interaction reach or LOS at all, and their
     * move check (>10 blocks/packet) rejects the desync sandwich with a rubberband. Send the
     * placement/attack packets directly from the real position — no fake moves, no self-damage
     * at the fake spot, no "moved too quickly" bounce.
     */
    private final Setting<Boolean> legacy112  = bool("1.12", false);

    private final Setting<Boolean> render      = bool("Render", true).setPage("Render");
    private final Setting<Color>   renderColor = color("Color", 130, 80, 255, 60).setPage("Render");

    /** Per-second decision diagnostics: which gate killed the placement attempt. */
    private final Setting<Boolean> debug = bool("Debug", false);

    // ------------------------------------------------------------ TPDiag counters
    private int dNoTarget, dBaseNull, dOppNull, dObsFail, dSwapFail, dSent, dBreakSent;
    private int dEntRej, dDmgRej, dSelfRej;
    private double dLastTargetDist = -1;
    private long dWindow = 0;

    private void dbgFlush() {
        long now = System.currentTimeMillis();
        if (dWindow == 0) { dWindow = now; return; }
        if (now - dWindow < 1000) return;
        if (debug.getValue()) {
            int rejects = dNoTarget + dBaseNull + dOppNull + dObsFail + dSwapFail;
            if (rejects > 0 || dSent > 0 || dBreakSent > 0 || dEntRej > 0 || dDmgRej > 0 || dSelfRej > 0) {
                GuizhouWare.LOGGER.info(
                        "[TPDiag] legacy={} tps={} rub={} | sent={} brk={} swapFail={} | noTgt={} noBase={} oppNull={} obsFail={} | reject[ent={} dmg={} self={}] tgtD={}",
                        legacy112.getValue(),
                        String.format("%.1f", GuizhouWare.tpsCounterService.getAverageTPS()),
                        GuizhouWare.tpsCounterService.isRubberbandRecent(),
                        dSent, dBreakSent, dSwapFail,
                        dNoTarget, dBaseNull, dOppNull, dObsFail,
                        dEntRej, dDmgRej, dSelfRej,
                        dLastTargetDist < 0 ? "-" : String.format("%.0f", dLastTargetDist));
            }
        }
        dNoTarget = dBaseNull = dOppNull = dObsFail = dSwapFail = dSent = dBreakSent = 0;
        dEntRej = dDmgRej = dSelfRej = 0;
        dLastTargetDist = -1;
        dWindow = now;
    }

    private final Timer placeTimer = new Timer();
    private final Timer breakTimer = new Timer();
    private final Timer baseTimer = new Timer();

    /**
     * Anti ping-pong epsilon: a candidate only displaces the current pick when it beats it by
     * more than this. Kills the jitter flip-flop between near-equal free spots, while
     * occupancy-driven alternation (spot busy with a live crystal → use the other) still works,
     * which is the two-in-flight throughput mechanism.
     */
    private static final float SELECT_EPSILON = 1.0f;

    private final PlacementVisualOptions visualOptions = new PlacementVisualOptions(this, false);
    private final PlacementVisuals visuals = new PlacementVisuals("TP水晶");
    private final Setting<Float> fadeTime = num("FadeTime", .8f, .05f, 2f).setPage("Render");
    private final Setting<Float> lineWidth = num("LineWidth", 1.5f, .5f, 5f).setPage("Render");
    private final Setting<Color> outlineColor = color("OutlineColor", 130, 80, 255, 220).setPage("Render");
    private float visualDamage = Float.NaN, visualSelf = Float.NaN;

    public GuangxiTPModule() {
        super("GuangxiTP", "Long-range crystal placement via desync move packets. Lenient-move-check servers only.", Category.COMBAT);
    }

    private long tpsMs(long baseMs) {
        if (!tpsSync.getValue()) return baseMs;
        return (long) (baseMs * GuizhouWare.tpsCounterService.delayScale());
    }

    @Override
    public void onEnable() {
        placeTimer.reset();
        breakTimer.reset();
    }

    @Override
    public void onDisable() {
        visuals.clear();
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        dbgFlush();
        if (nullCheck() || mc.player.isDeadOrDying()) return;
        if (tpsSync.getValue() && GuizhouWare.tpsCounterService.isRubberbandRecent()) return;

        Player target = findTarget();
        if (target == null) {
            dNoTarget++;
            return;
        }
        dLastTargetDist = Math.sqrt(mc.player.distanceToSqr(target));

        if (place.getValue() && placeTimer.passedMs(tpsMs(placeDelay.getValue()))) {
            BlockPos base = findBaseNear(target);
            if (base != null) {
                if (tpPlace(base)) {
                    placeTimer.reset();
                    noteRender(base, visualDamage, visualSelf);
                }
            } else {
                dBaseNull++;
                if (basePlace.getValue() && baseTimer.passedMs(tpsMs(baseDelay.getValue()))) {
                    // No natural base: desync-place obsidian next to the target, then chain the
                    // crystal on top in the same tick — the server processes the packets in order,
                    // so the base already exists when the crystal use arrives.
                    BaseOpportunity opp = findBaseOpportunity(target);
                    if (opp == null) dOppNull++;
                    else if (!tpBasePlaceChain(opp)) dObsFail++;
                    else noteRender(opp.base(), opp.damage(), opp.selfDamage());
                }
            }
        }

        if (doBreak.getValue() && breakTimer.passedMs(tpsMs(breakDelay.getValue()))) {
            EndCrystal crystal = findCrystalNear(target);
            if (crystal != null && tpBreak(crystal)) {
                breakTimer.reset();
            }
        }
    }

    private Player findTarget() {
        TargetsModule targets = GuizhouWare.moduleManager.getModuleByClass(TargetsModule.class);
        double rangeSq = tpRange.getValue() * tpRange.getValue();
        Player best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(tpRange.getValue()))) {
            if (!(e instanceof Player p) || p.isDeadOrDying()) continue;
            if (targets != null && !targets.isValidPlayerTarget(p)) continue;
            double d = mc.player.distanceToSqr(p);
            if (d > rangeSq) continue;
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    private BlockPos findBaseNear(Player target) {
        BlockPos feet = target.blockPosition();
        int r = (int) Math.ceil(scanRange.getValue());
        BlockPos best = null;
        float bestDmg = 0f;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    cursor.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    BlockState baseState = mc.level.getBlockState(cursor);
                    if (!baseState.is(Blocks.OBSIDIAN) && !baseState.is(Blocks.BEDROCK)) continue;

                    BlockPos airPos = cursor.above();
                    if (!mc.level.getBlockState(airPos).isAir()) continue;
                    if (crystalCellBlocked(airPos)) { dEntRej++; continue; }

                    Vec3 center = new Vec3(airPos.getX() + 0.5, airPos.getY(), airPos.getZ() + 0.5);
                    float dmg = estimateDamage(target, center);
                    if (dmg < minDamage.getValue()) { dDmgRej++; continue; }
                    float self = DamageUtil.crystalMaxSelfDamage(center);
                    if (self > maxSelf.getValue()) { dSelfRej++; continue; }

                    if (dmg > bestDmg + SELECT_EPSILON) {
                        bestDmg = dmg;
                        best = cursor.immutable();
                        visualDamage = dmg; visualSelf = self;
                    }
                }
            }
        }
        return best;
    }

    private boolean crystalCellBlocked(BlockPos pos) {
        return !mc.level.getEntities(null, new AABB(pos).expandTowards(0, 1, 0)).isEmpty();
    }

    private EndCrystal findCrystalNear(Player target) {
        EndCrystal best = null;
        float bestDmg = 0f;
        for (Entity e : mc.level.getEntities(null, target.getBoundingBox().inflate(scanRange.getValue() + 2))) {
            if (!(e instanceof EndCrystal crystal) || !crystal.isAlive()) continue;
            float dmg = estimateDamage(target, crystal.position());
            if (dmg < minDamage.getValue()) continue;
            if (DamageUtil.crystalMaxSelfDamage(crystal.position()) > maxSelf.getValue()) continue;
            if (dmg > bestDmg) { bestDmg = dmg; best = crystal; }
        }
        return best;
    }

    private record BaseOpportunity(BlockPos base, BlockPos clickNeighbor, Direction clickFace, float damage, float selfDamage) {}

    private static final Direction[] BASE_CLICK_DIRS = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN, Direction.UP
    };

    /** Finds a spot near the target where we can desync-place obsidian (air + air above + a
     *  clickable neighbor) and the future crystal on it clears {@code MinDamage}. */
    private BaseOpportunity findBaseOpportunity(Player target) {
        BlockPos feet = target.blockPosition();
        int r = (int) Math.ceil(scanRange.getValue());
        BaseOpportunity best = null;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    cursor.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    BlockState baseState = mc.level.getBlockState(cursor);
                    if (!baseState.isAir() && !baseState.canBeReplaced()) continue;
                    if (!mc.level.getBlockState(cursor.above()).isAir() || crystalCellBlocked(cursor.above())) continue;

                    // clickable neighbor: click its face that points back into our base cell
                    BlockPos neighbor = null;
                    Direction face = null;
                    for (Direction dir : BASE_CLICK_DIRS) {
                        BlockPos n = cursor.relative(dir);
                        if (mc.level.getBlockState(n).canBeReplaced()
                                || !mc.level.getBlockState(n).getFluidState().isEmpty()) continue;
                        neighbor = n;
                        face = dir.getOpposite(); // face of the neighbor pointing at the base cell
                        break;
                    }
                    if (neighbor == null) continue;

                    Vec3 crystalCenter = new Vec3(cursor.getX() + 0.5, cursor.getY() + 1.0, cursor.getZ() + 0.5);
                    float dmg = estimateDamage(target, crystalCenter);
                    if (dmg < minDamage.getValue()) continue;
                    float self = DamageUtil.crystalMaxSelfDamage(crystalCenter);
                    if (self > maxSelf.getValue()) continue;

                    if (best == null || dmg > best.damage() + SELECT_EPSILON) {
                        best = new BaseOpportunity(cursor.immutable(), neighbor.immutable(), face, dmg, self);
                    }
                }
            }
        }
        return best;
    }

    /** Obsidian desync place, then the crystal on top in the same tick (server packet order). */
    private boolean tpBasePlaceChain(BaseOpportunity opp) {
        if (!InventoryUtil.find(Items.OBSIDIAN, InventoryUtil.HOTBAR_SCOPE).found()) return false;
        if (!InventoryUtil.find(Items.END_CRYSTAL, InventoryUtil.HOTBAR_SCOPE).found()) return false;

        BlockPos base = opp.base();
        BlockPos n = opp.clickNeighbor();
        Vec3 fake = fakeFeet(base);
        Vec3 eye = fake.add(0, mc.player.getEyeHeight(), 0);
        BlockHitResult hit = GuizhouWare.placementManager.prepareInteractionHit(n, opp.clickFace(), eye, 6.0, 6.0, false);
        if (hit == null || !mc.level.getBlockState(base).canBeReplaced()
                || !dev.guizhou.util.PlaceUtil.noEntityCollision(base)) return false;
        boolean[] sent = {false};
        boolean swapped = GuizhouWare.swapManager.withSwap(Items.OBSIDIAN, InventoryUtil.HOTBAR_SCOPE,
                SwapMode.SILENT, SwapPriority.PLACEMENT, () -> sent[0] =
                        sandwich(fake, () -> GuizhouWare.placementManager.sendUseItemOnDirect(
                                hit, InteractionHand.MAIN_HAND, eye, 6.0, 6.0)));
        if (!swapped) dSwapFail++;
        if (!swapped || !sent[0]) return false;
        dSent++;
        // Optimistic packet ordering is retained, but a cancelled base packet cannot advance it.
        return tpPlace(base, true);
    }

    private Vec3 fakeFeet(BlockPos base) {
        return new Vec3(base.getX() + 0.5, base.getY() + 2.0, base.getZ() + 0.5);
    }

    private boolean tpPlace(BlockPos base) { return tpPlace(base, false); }

    private boolean tpPlace(BlockPos base, boolean pendingBase) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return false;
        if (!InventoryUtil.find(Items.END_CRYSTAL, InventoryUtil.HOTBAR_SCOPE).found()) return false;
        BlockState state = mc.level.getBlockState(base);
        if (!pendingBase && !state.is(Blocks.OBSIDIAN) && !state.is(Blocks.BEDROCK)) return false;
        if (!mc.level.getBlockState(base.above()).isAir() || crystalCellBlocked(base.above())) return false;
        Vec3 fake = fakeFeet(base);
        Vec3 eye = fake.add(0, mc.player.getEyeHeight(), 0);
        BlockHitResult hit = GuizhouWare.placementManager.prepareInteractionHit(base, Direction.UP,
                eye, 6.0, 6.0, pendingBase);
        if (hit == null) return false;
        boolean[] submitted = {false};
        boolean swapped = GuizhouWare.swapManager.withSwap(Items.END_CRYSTAL, InventoryUtil.HOTBAR_SCOPE, SwapMode.SILENT,
                SwapPriority.CRYSTAL, () -> submitted[0] =
                        sandwich(fake, () -> GuizhouWare.placementManager.sendUseItemOnDirect(
                                hit, InteractionHand.MAIN_HAND, eye, 6.0, 6.0)));
        if (!swapped) dSwapFail++;
        if (swapped && submitted[0]) dSent++;
        return swapped && submitted[0];
    }

    private boolean tpBreak(EndCrystal crystal) {
        // Fake stand ~2.2 above the crystal: inside the 3-block attack reach.
        Vec3 cp = crystal.position();
        Vec3 fake = new Vec3(cp.x, cp.y + 2.2, cp.z);

        if (rotate.getValue()) {
            if (!GuizhouWare.rotationManager.trySubmit(new RotationRequest(
                    "GuangxiTP_break", 40,
                    GuizhouWare.rotationManager.getServerYaw(),
                    GuizhouWare.rotationManager.getServerPitch(),
                    RotationRequest.Mode.SILENT))) return false;
        }

        boolean sent = sandwich(fake, () -> PacketSendUtil.send(
                ServerboundInteractPacket.createAttackPacket(crystal, mc.player.isShiftKeyDown())));
        if (sent) dBreakSent++;
        return sent;
    }

    /** Restore the real position even when an interaction throws or is cancelled. */
    private boolean sandwich(Vec3 fake, BooleanSupplier action) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return false;
        if (legacy112.getValue()) return action.getAsBoolean();
        return dev.guizhou.util.InteractionTransaction.run(() -> sendDesyncMove(fake), action, this::sendRealMove);
    }

    private boolean sendDesyncMove(Vec3 pos) {
        return PacketSendUtil.send(new ServerboundMovePlayerPacket.PosRot(
                pos.x, pos.y, pos.z,
                GuizhouWare.rotationManager.getServerYaw(),
                GuizhouWare.rotationManager.getServerPitch(),
                false, false));
    }

    private void sendRealMove() {
        mc.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                GuizhouWare.rotationManager.getServerYaw(),
                GuizhouWare.rotationManager.getServerPitch(),
                mc.player.onGround(), mc.player.horizontalCollision));
    }

    /** Optimistic explosion damage (exposure 1.0) with armor reduction; prot skipped on purpose. */
    private float estimateDamage(LivingEntity target, Vec3 center) {
        double dist = target.position().distanceTo(center);
        if (dist > 12.0) return 0f;
        double impact = 1.0 - dist / 12.0;
        float dmg = (float) ((impact * impact + impact) / 2.0 * 7.0 * 12.0 + 1.0);
        float armor = (float) target.getAttributeValue(Attributes.ARMOR);
        float toughness = (float) target.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        float i = 2.0f + toughness / 4.0f;
        float j = Mth.clamp(armor - dmg / i, armor * 0.2f, 20.0f);
        return dmg * (1.0f - j / 25.0f);
    }

    private void noteRender(BlockPos base, float damage, float self) {
        if (render.getValue()) visuals.main(base, new AABB(base), Vec3.atCenterOf(base), damage, self,
                "TP WAIT");
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (!render.getValue()) { visuals.clear(); return; }
        visuals.render(event, visualOptions, renderColor.getValue(), outlineColor.getValue(), lineWidth.getValue(), fadeTime.getValue());
    }
}
