package dev.guizhou.features.modules.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.render.Render3DEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.client.TargetsModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.RotationRequest;
import dev.guizhou.mixin.client.ClientLevelAccessor;
import dev.guizhou.util.AnchorDamageUtil;
import dev.guizhou.util.DamageSyncTracker;
import dev.guizhou.util.MathUtil;
import dev.guizhou.util.PlaceUtil;
import dev.guizhou.util.PacketSendUtil;
import dev.guizhou.util.combat.AnchorSequence;
import dev.guizhou.util.inventory.SwapMode;
import dev.guizhou.util.inventory.SwapPriority;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.inventory.Result;
import dev.guizhou.util.inventory.ResultType;
import dev.guizhou.util.render.RenderUtil;
import dev.guizhou.util.player.EatUtil;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

public class AutoAnchorModule extends Module {

    private final Setting<Double>  minDamage     = num("MinDamage", 6.0, 0.0, 36.0).setPage("General");
    private final Setting<Double>  maxSelfDamage = num("MaxSelfDamage", 4.0, 0.0, 36.0).setPage("General");
    private final Setting<Double> placeRange = num("PlaceRange", 5.0, 1.0, 6.0).setPage("General");
    private final Setting<Boolean> rotate = bool("Rotate", true).setPage("General");
    private final Setting<Boolean> assumeBestArmor = bool("AssumeBestArmor", false).setPage("General");
    private enum AnchorSwap { SILENT, INVENTORY }
    private final Setting<AnchorSwap> autoSwap = mode("AutoSwap", AnchorSwap.SILENT).setPage("General");
    private final Setting<Integer> confirmTicks = num("ConfirmTicks", 8, 2, 40).setPage("General");
    private final Setting<Integer> retries = num("Retries", 2, 1, 5).setPage("General");
    private final Setting<Integer> delay         = num("Delay", 2, 0, 20).setPage("General");

    private final Setting<Boolean> damageSync    = bool("DamageSync", false).setPage("General");
    private final Setting<Boolean> render        = bool("Render", true).setPage("Render");
    private final Setting<Float>   fadeTime      = num("FadeTime", 1.0f, 0.05f, 2.0f).setPage("Render");
    private final Setting<Color>   fillColor     = color("FillColor", 130, 80, 255, 45).setPage("Render");
    private final Setting<Color>   outlineColor  = color("OutlineColor", 130, 80, 255, 255).setPage("Render");

    private int lastBurstTick = -1;
    private final AnchorSequence sequence = new AnchorSequence();
    private BlockPos activePos;
    private ClientLevel sessionLevel;
    private LocalPlayer sessionPlayer;
    private float lastDamage = 0f;
    private BlockPos renderPos = null;
    private long renderStart = 0L;

    public AutoAnchorModule() {
        super("AutoAnchor", "Places, charges and detonates anchors after observing each block state.", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        resetSession();
    }

    @Override
    public void onDisable() {
        resetSession();
    }

    private void resetSession() {
        lastBurstTick = -1;
        lastDamage = 0f;
        renderPos = null;
        activePos = null;
        sequence.reset();
        sessionLevel = mc.level;
        sessionPlayer = mc.player;
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) {
            dev.guizhou.manager.HeartbeatManager.notifyDeniedOnce();
            return;
        }
        if (sessionLevel != mc.level || sessionPlayer != mc.player) resetSession();
        if (nullCheck() || mc.player.isDeadOrDying()) return;
        if (mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.inventoryMenu.getCarried().isEmpty()
                || mc.player.isShiftKeyDown() || EatUtil.shouldDefer()) return;
        int tick = mc.player.tickCount;
        if (activePos == null && lastBurstTick >= 0 && tick - lastBurstTick < delay.getValue()) return;

        List<AnchorDamageUtil.Target> targets = null;
        AnchorDamageUtil.Target self = null;
        if (activePos == null) {
            targets = collectTargets();
            self = AnchorDamageUtil.Target.of(mc.player);
            Candidate best = findBest(targets, self);
            if (best == null) { lastDamage = 0f; return; }
            activePos = best.pos;
            sequence.reset();
        }
        BlockState state = mc.level.getBlockState(activePos);
        boolean anchor = state.is(Blocks.RESPAWN_ANCHOR);
        AnchorSequence.Action action = sequence.next(anchor, anchor ? state.getValue(RespawnAnchorBlock.CHARGE) : 0,
                state.canBeReplaced(), tick, confirmTicks.getValue(), retries.getValue());
        if (action == AnchorSequence.Action.WAIT) return;
        if (action == AnchorSequence.Action.COMPLETE || action == AnchorSequence.Action.ABORT) {
            activePos = null;
            sequence.reset();
            lastBurstTick = tick;
            return;
        }
        // Waiting for confirmation does not require scanning targets or reading equipment again.
        if (targets == null) targets = collectTargets();
        if (self == null) self = AnchorDamageUtil.Target.of(mc.player);
        // Revalidate target damage, health and range at every action, including delayed detonations.
        Vec3 center = Vec3.atCenterOf(activePos);
        float damage = score(targets, center, activePos);
        float selfDamage = AnchorDamageUtil.damage(self, center, activePos);
        if (targets.isEmpty() || damage <= 0f || damage < minDamage.getValue() || selfDamage > maxSelfDamage.getValue()
                || selfDamage > 0f && selfDamage + 1.5f >= mc.player.getHealth() + mc.player.getAbsorptionAmount()
                || mc.player.getEyePosition().distanceToSqr(center) > placeRange.getValue() * placeRange.getValue()) {
            activePos = null;
            sequence.reset();
            lastDamage = 0f;
            return;
        }
        if (mc.level.environmentAttributes().getValue(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS, activePos)
                || !canSupply(action, anchor ? state.getValue(RespawnAnchorBlock.CHARGE) : 0)) {
            activePos = null;
            sequence.reset();
            return;
        }
        if (perform(action, activePos)) {
            sequence.sent(action, tick);
            lastDamage = damage;
            if (render.getValue()) { renderPos = activePos; renderStart = System.currentTimeMillis(); }
        }
    }

    private SwapMode swapMode() {
        return autoSwap.getValue() == AnchorSwap.INVENTORY ? SwapMode.ALTSILENT : SwapMode.SILENT;
    }

    private Result itemFor(AnchorSequence.Action action) {
        return InventoryUtil.find(stack -> switch (action) {
            case PLACE -> stack.is(Items.RESPAWN_ANCHOR);
            case CHARGE -> stack.is(Items.GLOWSTONE);

            default -> false;
        }, swapMode().scope());
    }

    private Result detonationSlot(int excluded) {
        int size = swapMode() == SwapMode.ALTSILENT ? 36 : 9;
        for (int slot = 0; slot < size; slot++) {
            if (slot == excluded) continue;
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.is(Items.GLOWSTONE) || stack.isEmpty() && slot >= 9) continue;
            return new Result(slot, stack, slot < 9 ? ResultType.HOTBAR : ResultType.INVENTORY);
        }
        return null;
    }

    private int offhandClearSlot() {
        int size = swapMode() == SwapMode.ALTSILENT ? 36 : 9;
        for (int slot = 0; slot < size; slot++) {
            if (!mc.player.getInventory().getItem(slot).is(Items.GLOWSTONE) && detonationSlot(slot) != null) return slot;
        }
        return -1;
    }

    private boolean canSupply(AnchorSequence.Action action, int charges) {
        if (action != AnchorSequence.Action.DETONATE) return itemFor(action).found();
        if (charges >= 4) return true;
        return mc.player.getOffhandItem().is(Items.GLOWSTONE) ? offhandClearSlot() >= 0 : detonationSlot(-1) != null;
    }

    private boolean detonate(BlockHitResult hit, int charges) {
        // In 1.21.11 only MAIN_HAND reaches useWithoutItem. A glowstone offhand defers
        // that main-hand interaction while the anchor can still accept a charge.
        if (charges >= 4) return sendUseItemOn(hit, InteractionHand.MAIN_HAND);
        int cleared = mc.player.getOffhandItem().is(Items.GLOWSTONE) ? offhandClearSlot() : -1;
        if (mc.player.getOffhandItem().is(Items.GLOWSTONE) && cleared < 0) return false;
        if (cleared >= 0) InventoryUtil.swapToOffhand(cleared);
        try {
            if (mc.player.getOffhandItem().is(Items.GLOWSTONE)) return false;
            Result item = detonationSlot(cleared);
            if (item == null) return false;
            boolean[] sent = {false};
            Runnable action = () -> sent[0] = sendUseItemOn(hit, InteractionHand.MAIN_HAND);
            boolean swapped = item.stack().isEmpty()
                    ? GuizhouWare.swapManager.withEmptySlot(item.slot(), SwapPriority.CRYSTAL, action)
                    : GuizhouWare.swapManager.withSwap(item, swapMode(), SwapPriority.CRYSTAL, action);
            return swapped && sent[0];
        } finally {
            if (cleared >= 0) InventoryUtil.swapToOffhand(cleared);
        }
    }

    private boolean perform(AnchorSequence.Action action, BlockPos pos) {
        ClientPacketListener conn = mc.getConnection();
        if (conn == null) return false;
        BlockHitResult hit = action == AnchorSequence.Action.PLACE
                ? GuizhouWare.placementManager.prepareAirPlaceHit(pos)
                : anchorHit(pos, mc.player.getEyePosition());
        if (hit == null || action == AnchorSequence.Action.PLACE && !PlaceUtil.canPlace(pos)) return false;
        if (mc.player.getEyePosition().distanceToSqr(hit.getLocation()) > placeRange.getValue() * placeRange.getValue()) return false;
        if (rotate.getValue()) {
            float[] angles = MathUtil.calcAngle(mc.player.getEyePosition(), hit.getLocation());
            if (!GuizhouWare.rotationManager.trySubmit(new RotationRequest(
                    "AutoAnchor", 62, angles[0], angles[1], RotationRequest.Mode.SILENT))) return false;
        }
        if (action == AnchorSequence.Action.DETONATE)
            return detonate(hit, mc.level.getBlockState(pos).getValue(RespawnAnchorBlock.CHARGE));
        Result item = itemFor(action);
        if (!item.found()) return false;
        boolean[] sent = {false};
        boolean swapped = GuizhouWare.swapManager.withSwap(item, swapMode(), SwapPriority.CRYSTAL,
                () -> sent[0] = sendUseItemOn(hit, item.hand()));
        if (swapped && sent[0] && action == AnchorSequence.Action.PLACE)
            GuizhouWare.placementManager.notePlacement(pos);
        return swapped && sent[0];
    }

    private boolean sendUseItemOn(BlockHitResult hit, InteractionHand hand) {
        try (var handler = ((ClientLevelAccessor) mc.level).guizhouware$getBlockStatePredictionHandler().startPredicting()) {
            boolean sent = PacketSendUtil.send(new ServerboundUseItemOnPacket(hand, hit, handler.currentSequence()));
            if (sent) mc.getConnection().send(new ServerboundSwingPacket(hand));
            return sent;
        }
    }

    private BlockHitResult anchorHit(BlockPos pos, Vec3 eye) {
        Vec3 center = Vec3.atCenterOf(pos);
        double dx = eye.x - center.x, dy = eye.y - center.y, dz = eye.z - center.z;
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        Direction face;
        if (ay >= ax && ay >= az)  face = dy > 0 ? Direction.UP    : Direction.DOWN;
        else if (ax >= az)         face = dx > 0 ? Direction.EAST  : Direction.WEST;
        else                       face = dz > 0 ? Direction.SOUTH : Direction.NORTH;
        Vec3 hitVec = center.add(face.getStepX() * 0.45, face.getStepY() * 0.45, face.getStepZ() * 0.45);
        return new BlockHitResult(hitVec, face, pos, false);
    }

    private Candidate findBest(List<AnchorDamageUtil.Target> targets, AnchorDamageUtil.Target selfTarget) {
        if (targets.isEmpty()) return null;
        Vec3 eye = mc.player.getEyePosition(1.0f);
        BlockPos playerPos = mc.player.blockPosition();
        double maxSelf = maxSelfDamage.getValue();
        double min = minDamage.getValue();
        double rangeSq = placeRange.getValue() * placeRange.getValue();
        float playerHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        int r = (int) Math.ceil(placeRange.getValue());
        int rr = r * r;

        boolean canPlaceAnchor = canSupply(AnchorSequence.Action.PLACE, 0);
        boolean canChargeAnchor = canSupply(AnchorSequence.Action.CHARGE, 0);
        boolean canDetonateAnchor = canSupply(AnchorSequence.Action.DETONATE, 1);
        Candidate best = null;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = -r; x <= r; x++) {
            int xx = x * x;
            for (int y = -r; y <= r; y++) {
                int xxyy = xx + y * y;
                for (int z = -r; z <= r; z++) {
                    if (xxyy + z * z > rr) continue;
                    cursor.set(playerPos.getX() + x, playerPos.getY() + y, playerPos.getZ() + z);
                    Vec3 center = Vec3.atCenterOf(cursor);
                    if (eye.distanceToSqr(center) > rangeSq) continue;

                    BlockState state = mc.level.getBlockState(cursor);
                    boolean existing = state.is(Blocks.RESPAWN_ANCHOR);
                    if (!existing && (!state.canBeReplaced() || !canPlaceAnchor)) continue;
                    if (existing) {
                        int charges = state.getValue(RespawnAnchorBlock.CHARGE);
                        if (charges == 0 && !canChargeAnchor || charges > 0 && charges < 4 && !canDetonateAnchor) continue;
                    }
                    if (mc.level.environmentAttributes().getValue(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS, cursor)) continue;
                    BlockPos pos = null;
                    // Sum upper bounds using the same group-damage scoring as the exact pass.
                    float upper = 0f;
                    for (AnchorDamageUtil.Target target : targets) {
                        if (damageSync.getValue() && DamageSyncTracker.shouldHold(target.entity())) continue;
                        upper += AnchorDamageUtil.maxDamage(target, center);
                    }
                    if (upper < min || best != null && upper <= best.damage) continue;

                    if (existing) {
                        pos = cursor.immutable();
                    } else {
                        if (!state.canBeReplaced()) continue;
                        if (!PlaceUtil.canPlace(cursor)) continue;
                        pos = cursor.immutable();
                    }

                    float total = score(targets, center, pos);
                    if (total <= 0f || total < min || best != null && total <= best.damage) continue;
                    float self = AnchorDamageUtil.damage(selfTarget, center, pos);
                    if (self > maxSelf) continue;

                    if (self > 0f && self + 1.5f >= playerHealth) continue;

                    best = new Candidate(pos, total);
                }
            }
        }
        return best;
    }

    private List<AnchorDamageUtil.Target> collectTargets() {
        TargetsModule targets = GuizhouWare.moduleManager.getModuleByClass(TargetsModule.class);
        List<AnchorDamageUtil.Target> out = new ArrayList<>();
        AABB area = mc.player.getBoundingBox().inflate(placeRange.getValue() + AnchorDamageUtil.DIAMETER);
        for (Entity e : mc.level.getEntities(mc.player, area)) {
            if (!(e instanceof LivingEntity living)) continue;
            if (living.isDeadOrDying()) continue;
            if (targets != null && !targets.isValidPlayerTarget(e)) continue;
            out.add(AnchorDamageUtil.Target.of(living, assumeBestArmor.getValue()));
        }
        return out;
    }

    private float score(List<AnchorDamageUtil.Target> targets, Vec3 center, BlockPos pos) {
        float total = 0f;
        for (AnchorDamageUtil.Target target : targets) {
            if (damageSync.getValue() && DamageSyncTracker.shouldHold(target.entity())) continue;
            total += AnchorDamageUtil.damage(target, center, pos);
        }
        return total;
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (!render.getValue() || renderPos == null) return;
        long age = System.currentTimeMillis() - renderStart;
        double fadeMs = fadeTime.getValue() * 1000.0;
        if (age > fadeMs) {
            renderPos = null;
            return;
        }
        double t = age / fadeMs;
        Color fc = fillColor.getValue();
        Color oc = outlineColor.getValue();
        RenderUtil.drawBoxFilled(event.getMatrix(), renderPos,
                withAlpha(fc, (int) (fc.getAlpha() * (1 - t))));
        RenderUtil.drawBox(event.getMatrix(), renderPos,
                withAlpha(oc, (int) (oc.getAlpha() * (1 - t))), 1.0f);
    }

    private static Color withAlpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Mth.clamp(a, 0, 255));
    }

    @Override
    public String getDisplayInfo() {
        return lastDamage > 0 ? String.format("%.1f", lastDamage) : null;
    }

    private record Candidate(BlockPos pos, float damage) {}
}
