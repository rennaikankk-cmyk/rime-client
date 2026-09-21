package dev.guizhou.manager;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.TickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.Feature;
import dev.guizhou.mixin.client.ClientLevelAccessor;
import dev.guizhou.util.MathUtil;
import dev.guizhou.util.PlaceUtil;
import dev.guizhou.util.inventory.ResultType;
import dev.guizhou.util.inventory.SwapMode;
import dev.guizhou.util.inventory.SwapPriority;
import dev.guizhou.util.player.EatUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static dev.guizhou.manager.PlacementGeometry.faceCenter;

/**
 * Rate-limited block placement.
 *
 * <p>Modules {@link #enqueue} a position and the item to place there; the manager batches every
 * queued position that wants the same item, takes one {@link SwapMode#SILENT} swap to that item and
 * sends the whole batch from the mainhand inside it. Two limits keep the burst plausible: a sliding
 * {@value #MAX_PER_WINDOW}-per-{@value #WINDOW_MS}ms window across all placements, and a per-block
 * cooldown so a position that has not been confirmed yet is not spammed.
 */
public class PlacementManager extends Feature {

    private static final long WINDOW_MS = 300L;
    private static final int MAX_PER_WINDOW = 9;

    private static final long PER_BLOCK_COOLDOWN_MS = 50L;

    /** Placement holds the item in the mainhand, so it can only reach the hotbar. */
    private static final EnumSet<ResultType> SCOPE = EnumSet.of(ResultType.HOTBAR);

    private final Map<BlockPos, Item> queue = new LinkedHashMap<>();
    private final Deque<Long> window = new ArrayDeque<>();
    private final Map<BlockPos, Long> cooldowns = new ConcurrentHashMap<>();
    private net.minecraft.client.multiplayer.ClientLevel sessionLevel;
    private net.minecraft.client.player.LocalPlayer sessionPlayer;

    private void ensureSession() {
        if (sessionLevel == mc.level && sessionPlayer == mc.player) return;
        sessionLevel = mc.level;
        sessionPlayer = mc.player;
        queue.clear();
        window.clear();
        cooldowns.clear();
    }

    public interface PlacementListener {
        void onBlockUpdate(BlockPos pos, boolean nowAir);
    }

    private final CopyOnWriteArrayList<PlacementListener> listeners = new CopyOnWriteArrayList<>();

    /** Per-call policy: reactive placements cannot inherit another module's transient settings. */
    public record CrystalOptions(double range, double wallRange, boolean sneak) {
        public static final CrystalOptions DEFAULT = new CrystalOptions(6.0, 0.0, false);
    }

    public void addListener(PlacementListener listener)    { listeners.addIfAbsent(listener); }
    public void removeListener(PlacementListener listener) { listeners.remove(listener); }

    public PlacementManager() {
        EVENT_BUS.register(this, "PlacementManager");
    }

    // ---------------------------------------------------------------- queue

    /**
     * Queues {@code item} to be placed at {@code pos}. Returns false when the position is on
     * cooldown or already queued, so callers can use it to decide whether they now own the
     * placement.
     */
    public boolean enqueue(BlockPos pos, Item item) {
        if (!mc.isSameThread()) return false;
        ensureSession();
        if (nullCheck()) return false;
        if (pos == null || item == null) return false;
        if (onCooldown(pos)) return false;
        return queue.putIfAbsent(pos.immutable(), item) == null;
    }

    public void removeQueuedFor(Predicate<BlockPos> filter) {
        queue.keySet().removeIf(filter);
    }

    /** Drops the per-block cooldown, letting {@code pos} be retried immediately. */
    public void forceResetPlaceCooldown(BlockPos pos) {
        cooldowns.remove(pos);
    }

    /** Books a placement this manager did not send, so it still counts against the rate window. */
    public void notePlacement(BlockPos pos) {
        long now = System.currentTimeMillis();
        cooldowns.put(pos.immutable(), now);
        window.addLast(now);
    }

    private boolean onCooldown(BlockPos pos) {
        Long last = cooldowns.get(pos);
        return last != null && System.currentTimeMillis() - last < PER_BLOCK_COOLDOWN_MS;
    }

    private int budget() {
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        while (!window.isEmpty() && window.peekFirst() < cutoff) {
            window.pollFirst();
        }
        return MAX_PER_WINDOW - window.size();
    }

    // ---------------------------------------------------------------- flushing

    @Subscribe
    private void onTick(TickEvent event) {
        flushQueue();
    }

    /** Sends what the rate window allows right now. Safe to call straight after queueing. */
    public void flushQueue() {
        if (!mc.isSameThread()) return;
        ensureSession();
        if (nullCheck() || queue.isEmpty()) return;
        if (EatUtil.shouldDefer()) return;

        long now = System.currentTimeMillis();
        cooldowns.values().removeIf(t -> now - t >= PER_BLOCK_COOLDOWN_MS);

        int budget = budget();
        if (budget <= 0) return;

        // One item per flush: the batch rides a single swap, so mixing items would need one swap
        // each and blow the tick's silent budget. The rest keeps its place for the next flush.
        Item item = queue.values().iterator().next();

        List<BlockPos> batch = new ArrayList<>();
        List<BlockPos> stale = new ArrayList<>();
        for (Map.Entry<BlockPos, Item> entry : queue.entrySet()) {
            if (entry.getValue() != item) continue;
            BlockPos pos = entry.getKey();
            if (!mc.level.getBlockState(pos).canBeReplaced()) {
                stale.add(pos);
                continue;
            }
            if (!PlaceUtil.canPlace(pos)) continue;
            if (cooldowns.containsKey(pos)) continue;
            batch.add(pos);
            if (batch.size() >= budget) break;
        }
        stale.forEach(queue::remove);
        if (batch.isEmpty()) return;

        place(batch, item, SwapPriority.PLACEMENT).forEach(queue::remove);
    }

    /**
     * Places a batch inside one silent swap and returns only locally submitted positions.
     * Refused or cancelled entries remain available for retry.
     */
    private List<BlockPos> place(List<BlockPos> batch, Item item, int priority) {
        List<BlockPos> submitted = new ArrayList<>();
        boolean swapped = GuizhouWare.swapManager.withSwap(item, SCOPE, SwapMode.SILENT, priority,
                () -> batch.forEach(pos -> { if (sendPlace(pos, null)) submitted.add(pos); }));
        if (!swapped) return List.of();

        long stamp = System.currentTimeMillis();
        for (BlockPos pos : submitted) {
            cooldowns.put(pos, stamp);
            window.addLast(stamp);
        }
        return submitted;
    }

    /**
     * Places {@code item} at the given positions right now, bypassing the queue but not the rate
     * window. Returns the positions actually sent.
     */
    public List<BlockPos> placeBatch(List<BlockPos> positions, Item item) {
        if (!mc.isSameThread()) return List.of();
        ensureSession();
        if (nullCheck() || positions.isEmpty() || EatUtil.shouldDefer()) return List.of();

        int budget = budget();
        if (budget <= 0) return List.of();

        List<BlockPos> batch = new ArrayList<>();
        for (BlockPos pos : positions) {
            if (batch.size() >= budget) break;
            if (batch.contains(pos)) continue;
            if (onCooldown(pos)) continue;
            if (!PlaceUtil.canPlace(pos)) continue;
            batch.add(pos.immutable());
        }
        if (batch.isEmpty()) return List.of();

        return place(batch, item, SwapPriority.PLACEMENT);
    }

    /** Single placement, bypassing the queue. {@code face} pins the side to click against. */
    public boolean placeDirect(BlockPos pos, @Nullable Direction face, Item item) {
        return placeDirect(pos, face, item, SwapMode.SILENT, SCOPE);
    }

    /** Single placement with an explicit swap mode/scope (e.g. BasePlace following AutoSwap). */
    public boolean placeDirect(BlockPos pos, @Nullable Direction face, Item item,
                               SwapMode mode, EnumSet<ResultType> scope) {
        return placeDirect(pos, face, item, mode, scope, 5.154);
    }

    public boolean placeDirect(BlockPos pos, @Nullable Direction face, Item item,
                               SwapMode mode, EnumSet<ResultType> scope, double range) {
        if (!mc.isSameThread()) return false;
        ensureSession();
        if (nullCheck() || EatUtil.shouldDefer()) return false;
        if (budget() <= 0 || onCooldown(pos)) return false;
        if (!PlaceUtil.canPlace(pos, range)) return false;

        boolean[] sent = {false};
        boolean swapped = GuizhouWare.swapManager.withSwap(item, scope, mode,
                SwapPriority.PLACEMENT, () -> sent[0] = sendPlace(pos, face, range));
        if (!swapped || !sent[0]) return false;

        notePlacement(pos);
        return true;
    }

    /** Sends one {@code UseItemOn} for {@code pos}. Must run inside a swap holding the block item. */
    private boolean sendPlace(BlockPos pos, @Nullable Direction face) {
        return sendPlace(pos, face, 5.154);
    }

    private boolean sendPlace(BlockPos pos, @Nullable Direction face, double range) {
        if (!PlaceUtil.canPlace(pos, range)) return false;
        // Axis blocks need a neighbour on the intended axis; changing just the packet face
        // would redirect placement into a different cell.
        Direction side = face;
        if (side == null && mc.player.getMainHandItem().getItem() instanceof BlockItem item
                && item.getBlock().defaultBlockState().hasProperty(BlockStateProperties.AXIS)) {
            side = Direction.DOWN;
        }
        BlockHitResult hit = preparePlaceHit(pos, side, true, range, range);
        return hit != null && send(hit, InteractionHand.MAIN_HAND, mc.player.getEyePosition(), range, range, false);
    }

    // ---------------------------------------------------------------- crystals

    /**
     * Places an end crystal on {@code base} inside a silent swap. {@code trustBase} skips the
     * obsidian/bedrock check for a base we are about to place ourselves.
     */
    public boolean placeCrystal(BlockPos base, boolean trustBase) {
        return placeCrystal(base, trustBase, SwapMode.SILENT, SCOPE);
    }

    public boolean placeCrystal(BlockPos base, boolean trustBase, SwapMode mode, EnumSet<ResultType> scope) {
        return placeCrystal(base, trustBase, mode, scope, null);
    }

    /** {@code allowOccupant} lets the cell contain that entity (FacePlace target). */
    public boolean placeCrystal(BlockPos base, boolean trustBase, SwapMode mode, EnumSet<ResultType> scope,
                                 net.minecraft.world.entity.LivingEntity allowOccupant) {
        return placeCrystal(base, trustBase, mode, scope, allowOccupant, CrystalOptions.DEFAULT);
    }

    public boolean placeCrystal(BlockPos base, boolean trustBase, SwapMode mode, EnumSet<ResultType> scope,
                                net.minecraft.world.entity.LivingEntity allowOccupant, CrystalOptions options) {
        if (!mc.isSameThread()) return false;
        ensureSession();
        if (nullCheck() || EatUtil.shouldDefer()) return false;

        BlockHitResult hit = crystalHit(base, trustBase, allowOccupant, options);
        if (hit == null) return false;

        boolean[] sent = {false};
        boolean swapped = GuizhouWare.swapManager.withSwap(Items.END_CRYSTAL, scope, mode,
                SwapPriority.CRYSTAL, () -> sent[0] = send(hit, InteractionHand.MAIN_HAND,
                        mc.player.getEyePosition(), options.range(), options.wallRange(), options.sneak()));
        return swapped && sent[0];
    }

    /** Places with the given hand directly, without any swap — for when a crystal is already held. */
    public boolean placeCrystalDirect(BlockPos base, boolean trustBase, InteractionHand hand) {
        return placeCrystalDirect(base, trustBase, hand, null);
    }

    public boolean placeCrystalDirect(BlockPos base, boolean trustBase, InteractionHand hand,
                                       net.minecraft.world.entity.LivingEntity allowOccupant) {
        return placeCrystalDirect(base, trustBase, hand, allowOccupant, CrystalOptions.DEFAULT);
    }

    public boolean placeCrystalDirect(BlockPos base, boolean trustBase, InteractionHand hand,
                                      net.minecraft.world.entity.LivingEntity allowOccupant, CrystalOptions options) {
        if (!mc.isSameThread()) return false;
        ensureSession();
        if (nullCheck() || EatUtil.shouldDefer()) return false;

        BlockHitResult hit = crystalHit(base, trustBase, allowOccupant, options);
        if (hit == null) return false;

        return send(hit, hand, mc.player.getEyePosition(), options.range(), options.wallRange(), options.sneak());
    }

    @Nullable
    private BlockHitResult crystalHit(BlockPos base, boolean trustBase,
                                      net.minecraft.world.entity.LivingEntity allowOccupant, CrystalOptions options) {
        if (!trustBase) {
            BlockState baseState = mc.level.getBlockState(base);
            if (!baseState.is(Blocks.OBSIDIAN) && !baseState.is(Blocks.BEDROCK)) return null;
        }

        BlockPos airPos = base.above();
        BlockState airState = mc.level.getBlockState(airPos);
        if (!airState.isAir()) return null;

        // EndCrystalItem.useOn checks a 1 x 2 x 1 entity column, including dropped items.
        for (Entity e : mc.level.getEntities(null, new AABB(airPos).expandTowards(0, 1, 0))) {
            if (e == allowOccupant) continue;
            return null;
        }

        return prepareInteractionHit(base, null, mc.player.getEyePosition(),
                options.range(), options.wallRange(), trustBase);
    }

    // ---------------------------------------------------------------- fireworks

    /** Uses a firework against the given positions from {@code face}, inside one silent swap. */
    public boolean useFireworks(List<BlockPos> positions, Direction face) {
        if (!mc.isSameThread() || nullCheck() || positions.isEmpty()) return false;
        ensureSession();

        boolean[] submitted = {false};
        boolean swapped = GuizhouWare.swapManager.withSwap(Items.FIREWORK_ROCKET, SCOPE, SwapMode.SILENT,
                SwapPriority.USER_ACTION, () -> {
            for (BlockPos pos : new java.util.LinkedHashSet<>(positions)) {
                submitted[0] |= send(new BlockHitResult(fireworkHit(pos, face), face.getOpposite(),
                        pos.relative(face), false), InteractionHand.MAIN_HAND);
            }
        });
        return swapped && submitted[0];
    }

    private Vec3 fireworkHit(BlockPos pos, Direction face) {
        if (face != Direction.DOWN) return PlacementGeometry.neighbourHit(pos, face).getLocation();

        // Lean the hit toward the player so the rocket clears our own hitbox.
        Vec3 player = mc.player.position();
        double x = pos.getX() + (player.x >= pos.getX() + 0.5 ? 0.15 : 0.85);
        double z = pos.getZ() + (player.z >= pos.getZ() + 0.5 ? 0.15 : 0.85);
        return new Vec3(x, pos.getY(), z);
    }

    // ---------------------------------------------------------------- geometry

    /** Compatibility entry: callers explicitly choose whether unsupported air placement is allowed. */
    @Nullable
    public BlockHitResult prepareAirPlaceHit(BlockPos pos) {
        return preparePlaceHit(pos, null, true, 5.154, 5.154);
    }

    @Nullable
    public BlockHitResult preparePlaceHit(BlockPos pos, @Nullable Direction side, boolean allowAir,
                                          double range, double wallRange) {
        if (!mc.isSameThread() || nullCheck() || pos == null) return null;
        ensureSession();
        if (budget() <= 0 || onCooldown(pos) || !PlaceUtil.canPlace(pos, range)) return null;
        Vec3 eye = mc.player.getEyePosition();
        BlockHitResult best = null;
        boolean bestVisible = false;
        for (Direction candidateSide : Direction.values()) {
            if (side != null && side != candidateSide) continue;
            BlockPos neighbour = pos.relative(candidateSide);
            BlockState state = mc.level.getBlockState(neighbour);
            if (state.canBeReplaced() || !state.getFluidState().isEmpty() || isInteractable(state.getBlock())) continue;
            BlockHitResult hit = prepareInteractionHit(neighbour, candidateSide.getOpposite(), eye, range, wallRange, false);
            if (hit == null) continue;
            boolean visible = visible(hit, eye);
            if (best == null || visible && !bestVisible || visible == bestVisible
                    && eye.distanceToSqr(hit.getLocation()) < eye.distanceToSqr(best.getLocation())) {
                best = hit;
                bestVisible = visible;
            }
        }
        if (best != null || !allowAir) return best;
        // Air placement has a distinct target and policy; never reuse an invalid neighbour hit.
        return prepareInteractionHit(pos, side == null ? null : side.getOpposite(), eye, range, wallRange, true);
    }

    /** Select against the real outline; preferredFace preserves bed/piston direction semantics. */
    @Nullable
    public BlockHitResult prepareInteractionHit(BlockPos clicked, @Nullable Direction preferredFace,
                                                Vec3 eye, double range, double wallRange, boolean allowAir) {
        if (!mc.isSameThread() || nullCheck() || clicked == null || eye == null
                || mc.level.isOutsideBuildHeight(clicked) || !mc.level.getWorldBorder().isWithinBounds(clicked)) return null;
        var shape = mc.level.getBlockState(clicked).getShape(mc.level, clicked);
        if (shape.isEmpty() && !allowAir) return null;
        var boxes = shape.isEmpty() ? List.of(new AABB(clicked))
                : shape.toAabbs().stream().map(box -> box.move(clicked)).toList();
        BlockHitResult best = null;
        boolean bestVisible = false;
        for (AABB box : boxes) for (Direction face : Direction.values()) {
            if (preferredFace != null && face != preferredFace) continue;
            BlockHitResult hit = new BlockHitResult(PlacementGeometry.facePoint(box, face, eye), face, clicked.immutable(), false);
            if (!validInteractionGeometry(hit, eye, range)) continue;
            boolean visible = visible(hit, eye);
            if (!visible && !(wallRange > 0 && PlacementGeometry.withinRange(eye, hit.getLocation(), Math.min(range, wallRange)))) continue;
            if (best == null || visible && !bestVisible || visible == bestVisible
                    && eye.distanceToSqr(hit.getLocation()) < eye.distanceToSqr(best.getLocation())) {
                best = hit;
                bestVisible = visible;
            }
        }
        return best;
    }

    public boolean canInteract(BlockHitResult hit, Vec3 eye, double range, double wallRange) {
        return validInteractionGeometry(hit, eye, range) && (visible(hit, eye) || wallRange > 0
                && PlacementGeometry.withinRange(eye, hit.getLocation(), Math.min(range, wallRange)));
    }

    private boolean validInteractionGeometry(BlockHitResult hit, Vec3 eye, double range) {
        if (!mc.isSameThread() || nullCheck() || !PlacementGeometry.validCursor(hit)
                || !PlacementGeometry.withinRange(eye, hit.getLocation(), range)
                || mc.level.isOutsideBuildHeight(hit.getBlockPos())
                || !mc.level.getWorldBorder().isWithinBounds(hit.getBlockPos())) return false;
        return true;
    }

    private boolean visible(BlockHitResult hit, Vec3 eye) {
        BlockHitResult trace = mc.level.clip(new ClipContext(eye, hit.getLocation(),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return trace.getType() == HitResult.Type.MISS
                || trace.getBlockPos().equals(hit.getBlockPos()) && trace.getDirection() == hit.getDirection();
    }

    private static boolean isInteractable(Block block) {
        return block instanceof BaseEntityBlock
            || block instanceof DoorBlock
            || block instanceof TrapDoorBlock
            || block instanceof FenceGateBlock
            || block instanceof ButtonBlock
            || block instanceof LeverBlock
            || block instanceof BedBlock
            || block instanceof NoteBlock
            || block instanceof AnvilBlock;
    }

    private boolean send(BlockHitResult hit, InteractionHand hand) {
        return send(hit, hand, mc.player == null ? null : mc.player.getEyePosition(), 5.154, 5.154, false);
    }

    private boolean send(BlockHitResult hit, InteractionHand hand, Vec3 eye,
                         double range, double wallRange, boolean sneak) {
        if (!HeartbeatManager.authorized() || !canInteract(hit, eye, range, wallRange)) return false;
        try (var prediction = ((ClientLevelAccessor) mc.level)
                .guizhouware$getBlockStatePredictionHandler().startPredicting()) {
            if (sneak && !mc.player.getLastSentInput().shift()) {
                // Already-sneaking players stay sneaking; otherwise press, place, restore.
                net.minecraft.world.entity.player.Input cur = mc.player.getLastSentInput();
                net.minecraft.world.entity.player.Input flipped = new net.minecraft.world.entity.player.Input(
                        cur.forward(), cur.backward(), cur.left(), cur.right(), cur.jump(),
                        true, cur.sprint());
                if (!dev.guizhou.util.PacketSendUtil.send(new ServerboundPlayerInputPacket(flipped))) return false;
                try {
                    return dev.guizhou.util.PacketSendUtil.send(new ServerboundUseItemOnPacket(hand, hit, prediction.currentSequence()));
                } finally {
                    mc.getConnection().send(new ServerboundPlayerInputPacket(cur));
                }
            } else {
                return dev.guizhou.util.PacketSendUtil.send(new ServerboundUseItemOnPacket(hand, hit, prediction.currentSequence()));
            }
        }
    }

    /** Raw UseItemOn send with a fresh prediction sequence, for desync-based placement (GuangxiTP). */
    public boolean sendUseItemOnDirect(BlockHitResult hit, InteractionHand hand) {
        return send(hit, hand);
    }

    /** TP callers supply the eye position corresponding to their accepted synthetic move. */
    public boolean sendUseItemOnDirect(BlockHitResult hit, InteractionHand hand, Vec3 eye,
                                       double range, double wallRange) {
        return send(hit, hand, eye, range, wallRange, false);
    }

    // ---------------------------------------------------------------- block updates

    /** Called after vanilla applies the update, so reactive scans observe the new block. */
    public void onBlockUpdateApplied(ClientboundBlockUpdatePacket packet) {
        if (!mc.isSameThread() || mc.level == null || mc.player == null) return;
        ensureSession();
        BlockPos pos = packet.getPos().immutable();
        boolean nowAir = packet.getBlockState().isAir();
        // The server confirmed the block: the cooldown that was suppressing retries has done its job.
        if (!nowAir) cooldowns.remove(pos);

        for (PlacementListener listener : listeners) {
            listener.onBlockUpdate(pos, nowAir);
        }
        flushQueue();
    }
}
