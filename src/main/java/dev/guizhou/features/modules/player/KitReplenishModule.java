package dev.guizhou.features.modules.player;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.TickEvent;
import dev.guizhou.event.impl.network.DisconnectEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.HeartbeatManager;
import dev.guizhou.util.MathUtil;
import dev.guizhou.util.compat.ClientCoexistence;
import dev.guizhou.util.compat.ForeignClientProbe;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.inventory.ItemUseTracker;
import dev.guizhou.util.inventory.KitDemand;
import dev.guizhou.util.inventory.ReplenishPlanning;
import dev.guizhou.util.inventory.Result;
import dev.guizhou.util.inventory.ResultType;
import dev.guizhou.util.lang.ClientLanguage;
import dev.guizhou.util.player.ScreenGate;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Counts the inventory against a saved kit and resupplies in stages, ported from SlimefunHelper
 * KitReplenish: partial stacks merge first, needed items are pulled from whatever container the
 * player deliberately opened (including the ender chest), and the optional shulker chain places
 * one of your own boxes, waits for the server window, takes only what is missing, closes and
 * breaks the box back up. Every stage aborts on deviation instead of forcing its plan.
 */
public class KitReplenishModule extends Module {

    private static KitReplenishModule instance;
    public static final String DEFAULT_KIT = "default";
    private static final String OWNER = "KitReplenish";
    private static final double REACH = 4.25;

    private final Setting<Boolean> autoResupply = bool("AutoResupply", false);
    private final Setting<Boolean> fromOpenContainer = bool("FromOpenContainer", true);
    private final Setting<Boolean> compactFirst = bool("CompactFirst", true);
    private final Setting<Boolean> shulkerChain = bool("ShulkerChain", false);
    private final Setting<Integer> movesPerTick = num("MovesPerTick", 2, 1, 6);
    private final Setting<Integer> openTimeout = num("OpenTimeoutTicks", 40, 10, 120);
    private final Setting<Integer> breakTimeout = num("BreakTimeoutTicks", 120, 20, 300);
    private final Setting<Integer> chainCooldown = num("ChainCooldownTicks", 10, 0, 100);

    /** kit name -> item id -> target count held across inventory and offhand. */
    private final Map<String, Map<String, Integer>> kits = new LinkedHashMap<>();
    private String activeKit = DEFAULT_KIT;

    private enum Stage { IDLE, COMPACT, PLACE, WAIT_OPEN, TAKE, BREAK }

    private Stage stage = Stage.IDLE;
    private BlockPos chainPos;
    private int stageTimer;
    private int cooldown;
    private boolean runRequested;

    public KitReplenishModule() {
        super("KitReplenish", "Counts your inventory against a saved kit and resupplies in stages: merge partial "
                + "stacks, pull needed items from the container you opened (ender chest included), and optionally "
                + "place one of your own shulker boxes, take from it after the server window confirms, then close "
                + "and break the box back up.", Category.PLAYER);
        instance = this;
    }

    public static KitReplenishModule instance() {
        return instance;
    }

    // ---------------------------------------------------------------- kit api (commands)

    public int saveKit(String name) {
        if (nullCheck()) return 0;
        Map<String, Integer> kit = KitDemand.count(inventorySnapshot());
        if (kit.isEmpty()) return 0;
        kits.put(name, kit);
        activeKit = name;
        return kit.size();
    }

    public boolean loadKit(String name) {
        if (!kits.containsKey(name)) return false;
        activeKit = name;
        return true;
    }

    public Set<String> kitNames() {
        return kits.keySet();
    }

    public String activeKit() {
        return activeKit;
    }

    /** Starts one resupply pass now; auto mode does this by itself on shortage. */
    public void requestRun() {
        runRequested = true;
    }

    public void stopChain() {
        finishChain(false);
    }

    // ---------------------------------------------------------------- ticking

    @Subscribe
    private void onTick(TickEvent event) {
        if (nullCheck()) return;
        if (!HeartbeatManager.authorized()) {
            HeartbeatManager.notifyDeniedOnce();
            finishChain(false);
            return;
        }
        if (ClientCoexistence.blocks(ForeignClientProbe.Domain.INVENTORY)) {
            finishChain(false);
            return;
        }
        if (mc.player.isDeadOrDying()) {
            finishChain(false);
            return;
        }
        if (cooldown > 0) cooldown--;

        Map<String, Integer> needs = shortage();
        if (isOpenContainer() && (fromOpenContainer.getValue() || stage != Stage.IDLE)) {
            serveOpenContainer(needs);
            return;
        }
        if (stage == Stage.TAKE) {
            // the box window vanished without us closing it: still break our placed box
            advanceToBreak();
        }
        if (stage != Stage.IDLE) {
            pumpChain(needs);
            return;
        }
        if (needs.isEmpty()) {
            runRequested = false;
            return;
        }
        if (!autoResupply.getValue() && !runRequested) return;
        if (cooldown > 0) return;
        if (!ScreenGate.allowsWorldUpkeep() || mc.player.isUsingItem()
                || GuizhouWare.swapManager == null || GuizhouWare.swapManager.isLatched()) return;
        stage = compactFirst.getValue() ? Stage.COMPACT : Stage.PLACE;
        stageTimer = 0;
    }

    /** Stage B: restock from whatever container the player deliberately opened. */
    private void serveOpenContainer(Map<String, Integer> needs) {
        if (stage == Stage.WAIT_OPEN) {
            stage = Stage.TAKE;
            stageTimer = 0;
        }
        int moves = takeFromOpenContainer(needs);
        if (stage != Stage.TAKE) return;
        if (moves > 0 && !shortage().isEmpty()) return;
        mc.player.closeContainer();   // window finished: our box, close before breaking
        advanceToBreak();
    }

    private void advanceToBreak() {
        stage = chainPos != null ? Stage.BREAK : Stage.IDLE;
        stageTimer = 0;
    }

    private void pumpChain(Map<String, Integer> needs) {
        switch (stage) {
            case COMPACT -> {
                if (compact() == 0) stage = shulkerChain.getValue() && !needs.isEmpty() ? Stage.PLACE : finish();
            }
            case PLACE -> {
                if (needs.isEmpty()) {
                    finishChain(false);
                    return;
                }
                Result box = bestOwnBox(needs);
                BlockPos spot = box == null ? null : findPlacementSpot();
                if (box == null || spot == null) {
                    Command.sendMessage(ClientLanguage.text("{red} 套装补给：找不到可用的补给盒或放置位置"));
                    finishChain(false);
                    return;
                }
                if (placeShulker(box, spot)) {
                    chainPos = spot;
                    stage = Stage.WAIT_OPEN;
                    stageTimer = 0;
                } else if (++stageTimer > openTimeout.getValue()) {
                    finishChain(false);
                }
            }
            case WAIT_OPEN -> {
                if (chainPos == null) {
                    stage = finish();
                } else if (!isShulkerBlock(chainPos)) {
                    // placement was undone or replaced: nothing of ours to clean up
                    stage = finish();
                } else if (++stageTimer > openTimeout.getValue()) {
                    // placed but the window never came: take the box back
                    advanceToBreak();
                }
            }
            case BREAK -> breakPlacedShulker();
            default -> stage = finish();
        }
    }

    private Stage finish() {
        finishChain(false);
        return Stage.IDLE;
    }

    /** Bounded stack merges so incoming supplies find free slots; returns moves made. */
    private int compact() {
        if (!HeartbeatManager.authorized()) return 0;
        if (!ScreenGate.allowsInventoryUpkeep(false)) return 0;
        List<ItemStack> slots = inventorySnapshot();
        int moves = 0;
        for (int i = 0; i < 36 && moves < movesPerTick.getValue(); i++) {
            ItemStack small = slots.get(i);
            if (small.isEmpty() || small.getMaxStackSize() <= 1
                    || small.getCount() >= small.getMaxStackSize()) continue;
            int partner = ReplenishPlanning.mergePartner(slots, i, this::isProtectedHotbarSlot);
            if (partner == -1) continue;
            mergeInto(partner, i);
            moves++;
        }
        return moves;
    }

    /** Every stack merge goes through here, behind one authorization boundary. */
    private void mergeInto(int largeIndex, int smallIndex) {
        if (!HeartbeatManager.authorized()) return;
        if (!InventoryUtil.cursor().isEmpty()) return;
        int largeSlot = containerSlotOf(largeIndex);
        int smallSlot = containerSlotOf(smallIndex);
        InventoryUtil.click(smallSlot, 0, ClickType.PICKUP);
        InventoryUtil.click(largeSlot, 0, ClickType.PICKUP);
        if (!InventoryUtil.cursor().isEmpty()) InventoryUtil.click(smallSlot, 0, ClickType.PICKUP);
    }

    /** Quick-moves needed items out of the open container; returns moves made this tick. */
    private int takeFromOpenContainer(Map<String, Integer> needs) {
        if (!HeartbeatManager.authorized()) return 0;
        if (needs.isEmpty()) return 0;
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == null || !InventoryUtil.cursor().isEmpty()) return 0;
        int containerCount = Math.max(0, menu.slots.size() - 36);
        int moves = 0;
        for (int i = 0; i < containerCount && moves < movesPerTick.getValue(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack.isEmpty()) continue;
            String id = KitDemand.id(stack.getItem());
            int missing = needs.getOrDefault(id, 0);
            if (missing <= 0) continue;
            InventoryUtil.click(i, 0, ClickType.QUICK_MOVE);
            needs.put(id, Math.max(0, missing - stack.getCount()));
            moves++;
        }
        return moves;
    }

    /** Places one own box against the top face of the support below {@code spot}. */
    private boolean placeShulker(Result box, BlockPos spot) {
        if (!HeartbeatManager.authorized()) return false;
        if (!ScreenGate.allowsWorldUpkeep() || mc.player.isUsingItem()) return false;
        Vec3 target = Vec3.atCenterOf(spot);
        float[] angles = MathUtil.calcAngle(mc.player.getEyePosition(), target);
        BlockPos support = spot.below();
        BlockHitResult hit = new BlockHitResult(
                new Vec3(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5), Direction.UP, support, false);
        return ItemUseTracker.use(OWNER, box, angles[0], angles[1], hit);
    }

    /** Holds a pickaxe and grinds the placed box down; picks up by standing next to it. */
    private void breakPlacedShulker() {
        if (!HeartbeatManager.authorized()) {
            releasePickaxe();
            return;
        }
        if (chainPos == null || !mc.level.hasChunkAt(chainPos)) {
            releasePickaxe();
            finishChain(true);
            return;
        }
        if (!isShulkerBlock(chainPos)) {
            releasePickaxe();
            finishChain(true);
            return;
        }
        if (mc.screen != null) {
            mc.player.closeContainer();
            return;
        }
        if (mc.gameMode == null) return;
        if (++stageTimer > breakTimeout.getValue()) {
            releasePickaxe();
            finishChain(false);
            return;
        }
        holdPickaxe();
        mc.gameMode.startDestroyBlock(chainPos, Direction.UP);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private void holdPickaxe() {
        if (GuizhouWare.swapManager.isLatched(this)) return;
        Result pickaxe = InventoryUtil.find(stack -> stack.is(ItemTags.PICKAXES),
                EnumSet.of(ResultType.HOTBAR, ResultType.INVENTORY));
        if (pickaxe.found()) GuizhouWare.swapManager.latch(this, pickaxe);
    }

    private void releasePickaxe() {
        if (GuizhouWare.swapManager != null) GuizhouWare.swapManager.release(this);
    }

    // ---------------------------------------------------------------- selection helpers

    private Map<String, Integer> shortage() {
        Map<String, Integer> kit = kits.get(activeKit);
        if (kit == null || kit.isEmpty()) return Map.of();
        return KitDemand.shortage(kit, KitDemand.count(inventorySnapshot()));
    }

    /** The own box covering the most of the current shortage. */
    private Result bestOwnBox(Map<String, Integer> needs) {
        Result best = null;
        int bestValue = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!KitDemand.isSupplyShulker(stack)) continue;
            int value = KitDemand.boxValue(stack, needs);
            if (value > bestValue) {
                bestValue = value;
                best = new Result(i, stack, i < 9 ? ResultType.HOTBAR : ResultType.INVENTORY);
            }
        }
        return best;
    }

    /** An air block beside the player on sturdy ground, within reach and unoccupied. */
    private BlockPos findPlacementSpot() {
        BlockPos feet = mc.player.blockPosition();
        Vec3 eye = mc.player.getEyePosition();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos candidate = feet.offset(dx, 0, dz);
                if (!mc.level.getBlockState(candidate).isAir()) continue;
                BlockPos support = candidate.below();
                if (!mc.level.getBlockState(support).isFaceSturdy(mc.level, support, Direction.UP)) continue;
                if (mc.player.getBoundingBox().intersects(new AABB(candidate))) continue;
                if (eye.distanceToSqr(Vec3.atCenterOf(candidate)) > REACH * REACH) continue;
                return candidate;
            }
        }
        return null;
    }

    private boolean isShulkerBlock(BlockPos pos) {
        return mc.level.getBlockState(pos).getBlock() instanceof ShulkerBoxBlock;
    }

    private boolean isProtectedHotbarSlot(int hotbarSlot) {
        if (hotbarSlot > 8) return false;
        if (GuizhouWare.swapManager != null && GuizhouWare.swapManager.latchedSlot() == hotbarSlot) return true;
        return mc.player.isUsingItem() && mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND
                && InventoryUtil.selected() == hotbarSlot;
    }

    private boolean isOpenContainer() {
        return mc.screen instanceof AbstractContainerScreen<?>
                && !(mc.screen instanceof InventoryScreen)
                && !(mc.screen instanceof CreativeModeInventoryScreen);
    }

    private List<ItemStack> inventorySnapshot() {
        List<ItemStack> slots = new ArrayList<>(ReplenishPlanning.INVENTORY_SIZE);
        for (int i = 0; i < ReplenishPlanning.INVENTORY_SIZE; i++) slots.add(mc.player.getInventory().getItem(i));
        return slots;
    }

    private static int containerSlotOf(int inventoryIndex) {
        return inventoryIndex < 9 ? inventoryIndex + 36 : inventoryIndex;
    }

    // ---------------------------------------------------------------- lifecycle

    private void finishChain(boolean keepTrying) {
        releasePickaxe();
        stage = Stage.IDLE;
        chainPos = null;
        stageTimer = 0;
        cooldown = chainCooldown.getValue();
        if (!keepTrying) runRequested = false;
    }

    @Override
    public void onDisable() {
        finishChain(false);
    }

    @Subscribe
    private void onDisconnect(DisconnectEvent event) {
        finishChain(false);
    }

    @Override
    public String getDisplayInfo() {
        Map<String, Integer> needs = nullCheck() ? Map.of() : shortage();
        String foreign = ClientCoexistence.reason(ForeignClientProbe.Domain.INVENTORY);
        if (foreign != null) return ClientLanguage.text("让行：") + foreign;
        if (stage == Stage.IDLE) return needs.isEmpty() ? null : ClientLanguage.text("缺") + " " + needs.size();
        return stage.name() + (needs.isEmpty() ? "" : " " + ClientLanguage.text("缺") + " " + needs.size());
    }
}
