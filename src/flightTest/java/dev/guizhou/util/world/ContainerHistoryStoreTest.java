package dev.guizhou.util.world;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/** Headless checks of the container history rules ported from SlimefunHelper ChestHistory. */
public final class ContainerHistoryStoreTest {
    private static int checks;

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        recordAndSnapshot();
        doubleChestRules();
        invalidation();
        selfPlacement();
        scopeAndExpiry();
        System.out.println("ContainerHistoryStoreTest passed: " + checks + " checks");
    }

    private static void recordAndSnapshot() {
        ContainerHistoryStore store = new ContainerHistoryStore();
        store.rescope("server#overworld");
        BlockPos pos = new BlockPos(10, 64, -3);
        store.recordOpened(pos, Blocks.CHEST, "Chest",
                List.of(stack(Items.OBSIDIAN, 32), ItemStack.EMPTY, stack(Items.ARROW, 5)),
                null, 1000L);
        ContainerHistoryStore.Entry entry = store.snapshot(pos);
        check(entry != null && entry.itemCount() == 2, "recorded snapshot counts non-empty slots");
        check(entry.title().equals("Chest") && entry.block() == Blocks.CHEST, "recorded metadata");
        check(!entry.doubleChest() && !entry.selfPlaced(), "plain chest flags");
        check(store.snapshot(new BlockPos(0, 0, 0)) == null, "unopened positions are never guessed");
    }

    private static void doubleChestRules() {
        ContainerHistoryStore store = new ContainerHistoryStore();
        store.rescope("server#overworld");
        BlockPos left = new BlockPos(1, 60, 1);
        BlockPos right = left.east();
        store.recordOpened(left, Blocks.CHEST, "Large Chest", List.of(stack(Items.OBSIDIAN, 64)),
                right, 1000L);
        check(store.snapshot(left) != null && store.snapshot(right) != null, "double chest filed under both halves");
        check(store.snapshot(right).pairPos().equals(left), "pair key points back");
        check(store.size() == 2, "both halves present");
        store.remove(right);
        check(store.snapshot(left) == null, "removing one half drops the pair");
    }

    private static void invalidation() {
        ContainerHistoryStore store = new ContainerHistoryStore();
        store.rescope("server#overworld");
        BlockPos pos = new BlockPos(4, 64, 4);
        store.recordOpened(pos, Blocks.CHEST, "Chest", List.of(stack(Items.ARROW, 3)), null, 1000L);
        check(!store.invalidate(pos, Blocks.CHEST), "same block keeps the entry");
        check(store.invalidate(pos, Blocks.BARREL), "a replaced block drops the entry");
        check(store.snapshot(pos) == null, "entry gone after replacement");

        BlockPos left = new BlockPos(6, 64, 6);
        store.recordOpened(left, Blocks.CHEST, "Large Chest", List.of(stack(Items.ARROW, 3)), left.east(), 1000L);
        check(!store.invalidate(left, Blocks.CHEST), "half still a chest keeps the double");
        check(store.invalidate(left, Blocks.STONE), "either half changing drops both");
        check(store.snapshot(left.east()) == null, "pair removed with the entry");
    }

    private static void selfPlacement() {
        ContainerHistoryStore store = new ContainerHistoryStore();
        store.rescope("server#overworld");
        BlockPos placed = new BlockPos(8, 70, 8);
        store.proposeSelfPlacement(placed, Blocks.SHULKER_BOX, 1000L);
        check(!store.isSelfPlaced(placed), "prediction alone is not self-placed");
        store.recordOpened(placed, Blocks.SHULKER_BOX, "Shulker Box", List.of(stack(Items.OBSIDIAN, 7)),
                null, 1100L);
        check(store.confirmSelfPlacement(placed), "appeared block confirms the placement");
        check(store.isSelfPlaced(placed), "confirmed placement is self-placed");
        check(store.snapshot(placed).selfPlaced(), "recorded entry carries the marker");

        check(!store.confirmSelfPlacement(placed), "double confirm is rejected");
        store.proposeSelfPlacement(placed.north(), Blocks.SHULKER_BOX, 1000L);
        check(store.expirePendingPlacements(22_000L, 20_000L) == 1, "stale prediction expires");
        check(!store.confirmSelfPlacement(placed.north()), "expired prediction cannot confirm");
    }

    private static void scopeAndExpiry() {
        ContainerHistoryStore store = new ContainerHistoryStore();
        store.rescope("server#overworld");
        BlockPos pos = new BlockPos(2, 64, 2);
        store.recordOpened(pos, Blocks.BARREL, "Barrel", List.of(stack(Items.BREAD, 5)), null, 1000L);
        store.recordEnderChest(List.of(stack(Items.ENDER_PEARL, 12)), 1000L);
        check(store.enderChest() != null && store.enderChest().itemCount() == 1, "ender chest recorded");
        check(!store.rescope("server#overworld"), "same scope keeps everything");
        check(store.snapshot(pos) != null, "entry survives same-scope tick");
        check(store.rescope("server#the_nether"), "a new scope is detected");
        check(store.snapshot(pos) == null && store.enderChest() == null && store.size() == 0,
                "server or dimension change clears every position");
        check(!store.isSelfPlaced(pos), "self markers are scoped too");

        store.recordOpened(pos, Blocks.BARREL, "Barrel", List.of(stack(Items.BREAD, 5)), null, 1000L);
        store.recordOpened(pos.east(), Blocks.CHEST, "Chest", List.of(stack(Items.BREAD, 5)), null, 5000L);
        check(store.expire(304_000L, 300_000L) == 1, "only stale entries expire");
        check(store.snapshot(pos.east()) != null && store.snapshot(pos) == null, "fresh entry kept");
    }

    private static ItemStack stack(net.minecraft.world.item.Item item, int count) {
        ItemStack stack = new ItemStack(item);
        stack.setCount(count);
        return stack;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
