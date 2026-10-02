package dev.guizhou.util.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client-side record of container contents the player has actually observed, scoped to one
 * server and dimension. Ported from SlimefunHelper ChestHistory with the store kept a pure
 * state machine: the module feeds it opens, block reads and the clock, so the rules stay
 * testable without a world.
 *
 * <p>Entries are last-observation snapshots, never live contents, and nothing is ever inferred
 * for containers that were not opened. A double chest is one snapshot filed under both halves.
 */
public final class ContainerHistoryStore {

    /** One observed container; {@code slots} is owned by the store and never mutated after. */
    public record Entry(String title, Block block, List<ItemStack> slots, long seenAtMs,
                        BlockPos pairPos, boolean selfPlaced) {
        public boolean doubleChest() {
            return pairPos != null;
        }

        public int itemCount() {
            int count = 0;
            for (ItemStack stack : slots) if (!stack.isEmpty()) count++;
            return count;
        }
    }

    /** Predicted self placement of a shulker box, awaiting the block actually appearing. */
    public record PendingPlacement(BlockPos pos, Block expected, long proposedAtMs) {}

    private final Map<BlockPos, Entry> containers = new HashMap<>();
    private final Set<BlockPos> selfPlaced = new HashSet<>();
    private final List<PendingPlacement> pendingPlacements = new ArrayList<>();
    private Entry enderChest;
    private String scope;

    /** A different server or dimension invalidates every position; returns true when cleared. */
    public boolean rescope(String serverAndDimension) {
        if (serverAndDimension.equals(scope)) return false;
        clear();
        scope = serverAndDimension;
        return true;
    }

    public String scope() {
        return scope;
    }

    public void clear() {
        containers.clear();
        selfPlaced.clear();
        pendingPlacements.clear();
        enderChest = null;
    }

    /**
     * Files one observed snapshot. {@code slots} must already be private copies; a non-null
     * {@code pairPos} files the same snapshot under both halves of a double chest.
     */
    public void recordOpened(BlockPos pos, Block block, String title, List<ItemStack> slots,
                             BlockPos pairPos, long nowMs) {
        containers.put(pos, new Entry(title, block, List.copyOf(slots), nowMs, pairPos, selfPlaced.contains(pos)));
        if (pairPos != null) {
            containers.put(pairPos, new Entry(title, block, List.copyOf(slots), nowMs, pos, selfPlaced.contains(pairPos)));
        }
    }

    public void recordEnderChest(List<ItemStack> slots, long nowMs) {
        enderChest = new Entry("Ender Chest", null, List.copyOf(slots), nowMs, null, true);
    }

    public Entry snapshot(BlockPos pos) {
        return containers.get(pos);
    }

    public Entry enderChest() {
        return enderChest;
    }

    public Collection<Map.Entry<BlockPos, Entry>> entries() {
        return containers.entrySet();
    }

    public int size() {
        return containers.size();
    }

    /**
     * Drops the entry at {@code pos} when the block there is no longer the one that was opened.
     * A double chest falls when either half changes. Self-placement markers fall with the block.
     */
    public boolean invalidate(BlockPos pos, Block currentBlock) {
        Entry entry = containers.get(pos);
        if (entry == null) return false;
        if (entry.block() != null && entry.block() == currentBlock) return false;
        remove(pos);
        return true;
    }

    public void remove(BlockPos pos) {
        Entry entry = containers.remove(pos);
        if (entry != null && entry.pairPos() != null) containers.remove(entry.pairPos());
        selfPlaced.remove(pos);
        if (entry != null && entry.pairPos() != null) selfPlaced.remove(entry.pairPos());
    }

    public void proposeSelfPlacement(BlockPos pos, Block expected, long nowMs) {
        pendingPlacements.add(new PendingPlacement(pos, expected, nowMs));
    }

    /** Pending placements that have waited too long for their block to appear. */
    public int expirePendingPlacements(long nowMs, long timeoutMs) {
        int before = pendingPlacements.size();
        pendingPlacements.removeIf(pending -> nowMs - pending.proposedAtMs() >= timeoutMs);
        return before - pendingPlacements.size();
    }

    public List<PendingPlacement> pending() {
        return List.copyOf(pendingPlacements);
    }

    /** The predicted block appeared: the position becomes self-placed for this scope. */
    public boolean confirmSelfPlacement(BlockPos pos) {
        boolean removed = pendingPlacements.removeIf(pending -> pending.pos().equals(pos));
        if (!removed) return false;
        selfPlaced.add(pos);
        markSelfPlaced(pos);
        return true;
    }

    private void markSelfPlaced(BlockPos pos) {
        Entry entry = containers.get(pos);
        if (entry == null || entry.selfPlaced()) return;
        Entry marked = new Entry(entry.title(), entry.block(), entry.slots(), entry.seenAtMs(),
                entry.pairPos(), true);
        containers.put(pos, marked);
        if (marked.pairPos() != null) {
            Entry pair = containers.get(marked.pairPos());
            if (pair != null && !pair.selfPlaced()) {
                containers.put(marked.pairPos(), new Entry(pair.title(), pair.block(), pair.slots(),
                        pair.seenAtMs(), pair.pairPos(), true));
            }
        }
    }

    public boolean isSelfPlaced(BlockPos pos) {
        return selfPlaced.contains(pos);
    }

    /** Entries older than the ttl disappear; a double chest expires with either half's clock. */
    public int expire(long nowMs, long ttlMs) {
        int before = containers.size();
        Set<BlockPos> doomed = new HashSet<>();
        for (Map.Entry<BlockPos, Entry> entry : containers.entrySet()) {
            if (nowMs - entry.getValue().seenAtMs() >= ttlMs) {
                doomed.add(entry.getKey());
                if (entry.getValue().pairPos() != null) doomed.add(entry.getValue().pairPos());
            }
        }
        for (BlockPos pos : doomed) remove(pos);
        return before - containers.size();
    }
}
