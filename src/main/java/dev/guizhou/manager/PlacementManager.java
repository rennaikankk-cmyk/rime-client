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
        sneakSandwich = false;
        crystalWallRangeSq = -1.0;
    }

    public interface PlacementListener {
        void onBlockUpdate(BlockPos pos, boolean nowAir);
    }

    private final CopyOnWriteArrayList<PlacementListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * When set, the next placement UseItemOn is wrapped in a sneak-state sandwich
     * (PRESS_SHIFT → place → RELEASE_SHIFT, or the reverse if we already sneak) so the
     * server sees a sneak interaction. Modules set this around a single placement call.
     */
    private boolean sneakSandwich = false;

    public void setSneakSandwich(boolean enabled) {
        ensureSession();
        this.sneakSandwich = enabled;
    }

    /**
     * WallPlace (Mio): squared reduced range within which a crystal placement is attempted even
     * when the base has no line of sight. Servers without placement-LOS validation accept it;
     * LOS-checking servers just drop the packet. &lt;= 0 keeps the strict LOS gate.
     */
    private double crystalWallRangeSq = -1.0;

    public void setCrystalWallRangeSq(double sqRange) {
        ensureSession();
        this.crystalWallRangeSq = sqRange;
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
        if (!mc.isSameThread()) return false;
        ensureSession();
        if (nullCheck() || EatUtil.shouldDefer()) return false;
        if (budget() <= 0 || onCooldown(pos)) return false;
        if (!PlaceUtil.canPlace(pos)) return false;

        boolean[] sent = {false};
        boolean swapped = GuizhouWare.swapManager.withSwap(item, scope, mode,
                SwapPriority.PLACEMENT, () -> sent[0] = sendPlace(pos, face));
        if (!swapped || !sent[0]) return false;

        notePlacement(pos);
        return true;
    }

    /** Sends one {@code UseItemOn} for {@code pos}. Must run inside a swap holding the block item. */
    private boolean sendPlace(BlockPos pos, @Nullable Direction face) {
        Direction side = face != null ? face : placeSide(pos);

        BlockPos clicked;
        Vec3 hitPos;
        Direction hitSide;
        if (side == null) {
            // Nothing solid to click against: aim at the face of the target cell itself.
            hitSide = airPlaceDirection(pos);
            clicked = pos;
            hitPos = faceCenter(pos, hitSide);
        } else {
            BlockHitResult hit = PlacementGeometry.neighbourHit(pos, side);
            clicked = hit.getBlockPos();
            hitSide = hit.getDirection();
            hitPos = hit.getLocation();
        }

        // Logs and pillars take their axis from the face that was clicked, so a batch placed off the
        // nearest neighbour would come out rotated at random.
        if (mc.player.getMainHandItem().getItem() instanceof BlockItem blockItem) {
            BlockState desired = blockItem.getBlock().defaultBlockState();
            if (desired.hasProperty(BlockStateProperties.AXIS)) {
                hitSide = switch (desired.getValue(BlockStateProperties.AXIS)) {
                    case X -> Direction.EAST;
                    case Z -> Direction.SOUTH;
                    case Y -> Direction.UP;
                };
                hitPos = faceCenter(clicked, hitSide);
            }
        }

        return send(new BlockHitResult(hitPos, hitSide, clicked, false), InteractionHand.MAIN_HAND);
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
        if (!mc.isSameThread()) return false;
        ensureSession();
        if (nullCheck() || EatUtil.shouldDefer()) return false;

        BlockHitResult hit = crystalHit(base, trustBase, allowOccupant);
        if (hit == null) return false;

        boolean[] sent = {false};
        boolean swapped = GuizhouWare.swapManager.withSwap(Items.END_CRYSTAL, scope, mode,
                SwapPriority.CRYSTAL, () -> sent[0] = send(hit, InteractionHand.MAIN_HAND));
        return swapped && sent[0];
    }

    /** Places with the given hand directly, without any swap — for when a crystal is already held. */
    public boolean placeCrystalDirect(BlockPos base, boolean trustBase, InteractionHand hand) {
        return placeCrystalDirect(base, trustBase, hand, null);
    }

    public boolean placeCrystalDirect(BlockPos base, boolean trustBase, InteractionHand hand,
                                       net.minecraft.world.entity.LivingEntity allowOccupant) {
        if (!mc.isSameThread()) return false;
        ensureSession();
        if (nullCheck() || EatUtil.shouldDefer()) return false;

        BlockHitResult hit = crystalHit(base, trustBase, allowOccupant);
        if (hit == null) return false;

        return send(hit, hand);
    }

    @Nullable
    private BlockHitResult crystalHit(BlockPos base, boolean trustBase) {
        return crystalHit(base, trustBase, null);
    }

    @Nullable
    private BlockHitResult crystalHit(BlockPos base, boolean trustBase,
                                      net.minecraft.world.entity.LivingEntity allowOccupant) {
        if (!trustBase) {
            BlockState baseState = mc.level.getBlockState(base);
            if (!baseState.is(Blocks.OBSIDIAN) && !baseState.is(Blocks.BEDROCK)) return null;
        }

        BlockPos airPos = base.above();
        BlockState airState = mc.level.getBlockState(airPos);
        if (!airState.isAir() && !(airState.is(Blocks.FIRE) && mc.level.dimension().equals(Level.END))) return null;

        for (Entity e : mc.level.getEntities(null, new AABB(airPos))) {
            if (e == allowOccupant) continue;
            if (e instanceof ItemEntity) continue;
            if (e instanceof EndCrystal crystal
                    && (crystal.tickCount < 5 || crystal.blockPosition().equals(airPos))) continue;
            return null;
        }

        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 baseCenter = Vec3.atCenterOf(base);
        double dx = eye.x - baseCenter.x, dy = eye.y - baseCenter.y, dz = eye.z - baseCenter.z;
        double absX = Math.abs(dx), absY = Math.abs(dy), absZ = Math.abs(dz);
        Direction clickFace;
        if (absY >= absX && absY >= absZ)      clickFace = dy > 0 ? Direction.UP    : Direction.DOWN;
        else if (absX >= absZ)                 clickFace = dx > 0 ? Direction.EAST  : Direction.WEST;
        else                                   clickFace = dz > 0 ? Direction.SOUTH : Direction.NORTH;

        // Preserve the existing fractional-position bias; this is not a nearest-point projection.
        Vec3 playerPos = mc.player.position();
        double offX = Math.clamp(playerPos.x - Math.floor(playerPos.x), 0.2, 0.8) - 0.5;
        double offY = Math.clamp(playerPos.y - Math.floor(playerPos.y), 0.2, 0.8) - 0.5;
        double offZ = Math.clamp(playerPos.z - Math.floor(playerPos.z), 0.2, 0.8) - 0.5;
        // Retain the inset used for the server tested on 2026-09-16. Boundary rejection is
        // server-specific, not a vanilla protocol requirement.
        Vec3 hitVec = switch (clickFace) {
            case UP, DOWN -> baseCenter.add(offX, clickFace == Direction.UP ? 0.45 : -0.45, offZ);
            case NORTH, SOUTH -> baseCenter.add(offX, offY, clickFace == Direction.SOUTH ? 0.45 : -0.45);
            case EAST, WEST -> baseCenter.add(clickFace == Direction.EAST ? 0.45 : -0.45, offY, offZ);
        };

        AABB baseBox = new AABB(base);
        if (!baseBox.contains(eye)) {
            float[] angles = MathUtil.calcAngle(eye, hitVec);
            Vec3 reachEnd = eye.add(lookVector(angles[0], angles[1]).scale(6.0));
            if (baseBox.clip(eye, reachEnd).isEmpty()
                    && (crystalWallRangeSq <= 0.0
                        || eye.distanceToSqr(hitVec) > crystalWallRangeSq)) {
                return null;
            }
        }

        return new BlockHitResult(hitVec, clickFace, base, false);
    }

    // ---------------------------------------------------------------- fireworks

    /** Uses a firework against the given positions from {@code face}, inside one silent swap. */
    public boolean useFireworks(List<BlockPos> positions, Direction face) {
        if (nullCheck() || positions.isEmpty()) return false;

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

    /**
     * The neighbour face to click to place at {@code pos}: the closest solid, non-interactable,
     * non-fluid side. Null when the position is floating and needs an air place.
     */
    @Nullable
    private Direction placeSide(BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition();
        Direction best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (Direction side : Direction.values()) {
            BlockPos neighbour = pos.relative(side);
            BlockState state = mc.level.getBlockState(neighbour);
            if (state.isAir()) continue;
            if (!state.getFluidState().isEmpty()) continue;
            if (isInteractable(state.getBlock())) continue;

            double distSq = eye.distanceToSqr(Vec3.atCenterOf(neighbour));
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = side;
            }
        }
        return best;
    }

    /**
     * The hit result for a placement at {@code pos}, for callers that emit the use packet
     * themselves. Prefers a real neighbour whose face we can actually see, and falls back to an
     * air place against the cell itself.
     */
    @Nullable
    public BlockHitResult prepareAirPlaceHit(BlockPos pos) {
        if (nullCheck() || pos == null || onCooldown(pos) || !PlaceUtil.canPlace(pos)) return null;

        Vec3 eye = mc.player.getEyePosition();
        BlockHitResult bestHit = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Direction side : Direction.values()) {
            BlockPos neighbour = pos.relative(side);
            BlockState state = mc.level.getBlockState(neighbour);
            if (state.isAir()) continue;
            if (!state.getFluidState().isEmpty()) continue;
            if (isInteractable(state.getBlock())) continue;

            BlockHitResult candidate = PlacementGeometry.neighbourHit(pos, side);
            Vec3 hit = candidate.getLocation();
            // Apply the facing and distance checks to the same point we will send.
            Direction face = candidate.getDirection();
            Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
            if (eye.subtract(hit).dot(normal) <= 0.01) continue;

            double distSq = eye.distanceToSqr(hit);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestHit = candidate;
            }
        }

        if (bestHit != null) return bestHit;
        Direction dir = airPlaceDirection(pos);
        return new BlockHitResult(faceCenter(pos, dir), dir, pos, false);
    }

    private Direction airPlaceDirection(BlockPos pos) {
        if (mc.player == null) return Direction.UP;

        Vec3 eye = mc.player.getEyePosition();
        Direction best = Direction.UP;
        double bestDist = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            double dist = eye.distanceToSqr(faceCenter(pos, d));
            if (dist < bestDist) {
                bestDist = dist;
                best = d;
            }
        }
        return best;
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

    private static Vec3 lookVector(float yaw, float pitch) {
        float f = (float) Math.cos(-yaw * 0.017453292F - Math.PI);
        float g = (float) Math.sin(-yaw * 0.017453292F - Math.PI);
        float h = -(float) Math.cos(-pitch * 0.017453292F);
        float i = (float) Math.sin(-pitch * 0.017453292F);
        return new Vec3(g * h, i, f * h);
    }

    private boolean send(BlockHitResult hit, InteractionHand hand) {
        if (nullCheck() || !mc.isSameThread()) return false;
        try (var prediction = ((ClientLevelAccessor) mc.level)
                .guizhouware$getBlockStatePredictionHandler().startPredicting()) {
            if (sneakSandwich && mc.player != null) {
                // 1.21.11: sneak state rides in the input packet — flip shift, place, restore.
                net.minecraft.world.entity.player.Input cur = mc.player.getLastSentInput();
                net.minecraft.world.entity.player.Input flipped = new net.minecraft.world.entity.player.Input(
                        cur.forward(), cur.backward(), cur.left(), cur.right(), cur.jump(),
                        !cur.shift(), cur.sprint());
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
    public void sendUseItemOnDirect(BlockHitResult hit, InteractionHand hand) {
        send(hit, hand);
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
