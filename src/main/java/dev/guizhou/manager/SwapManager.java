package dev.guizhou.manager;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.Feature;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.inventory.Result;
import dev.guizhou.util.inventory.ResultType;
import dev.guizhou.util.inventory.SwapMode;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;
import java.util.function.Predicate;

/**
 * Owns the selected hotbar slot.
 *
 * <p>Two shapes of swap, and every module uses one of them:
 *
 * <ul>
 *   <li>{@link SwapMode#SILENT} / {@link SwapMode#ALTSILENT} — sub-tick. Swap to the item, run the
 *       action, swap back, all inside one {@link #withSwap} call, so the slot the player sees never
 *       changes. SILENT moves the selected slot; ALTSILENT leaves it alone and window-clicks the
 *       item into it instead, which also reaches the main inventory.</li>
 *   <li>{@link SwapMode#LATCH} — visible. {@link #latch} switches to the slot and stays there,
 *       re-pinning it against desync each tick, until someone calls {@link #release()}.</li>
 * </ul>
 */
public class SwapManager extends Feature {

    /**
     * Traces every swap through the game log. Off by default — this sits on the hot path of every
     * combat module, and {@link #caller()} walks the stack.
     */
    public static boolean logging;

    private static final StackWalker WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    /**
     * How many {@link SwapMode#SILENT} swaps may actually go out in one tick.
     *
     * <p>A silent swap is two extra packets around the action (select, act, select back). A tick
     * that placed obsidian, broke a crystal, swung a sword and threw a pearl sent four of those
     * bursts, and a hotbar slot that changes several times inside one tick is not something a
     * legitimate client ever produces. Three covers the place/break/weapon triple combat actually
     * needs; anything past it waits for the next tick.
     *
     * <p>Only SILENT is rationed — {@link SwapMode#ALTSILENT} moves items with window clicks and
     * never touches the selected slot, so it is unaffected.
     */
    private static final int SILENT_BUDGET = 3;

    private HeldSwap held;
    private static final Object LEGACY_LATCH_OWNER = new Object();

    /** SILENT swaps that have gone out this tick, against {@link #SILENT_BUDGET}. */
    private int silentUsed;

    /** Priority the last slot of this tick's budget is held for; see {@link #claimSilent(int)}. */
    private int reserved = Integer.MIN_VALUE;

    /** Highest priority refused budget this tick — becomes next tick's {@link #reserved}. */
    private int starved = Integer.MIN_VALUE;

    /** Last slot the server was told we hold, tracked off the outgoing packet. */
    private int serverSlot = -1;

    public void init() {
        EVENT_BUS.register(this, "SwapManager");
    }

    // ---------------------------------------------------------------- sub-tick swaps

    public boolean withSwap(Result target, SwapMode mode, Runnable action) {
        return withSwap(target, mode, 0, action);
    }

    /**
     * Runs {@code action} holding {@code target}, swapping to it and back if needed.
     *
     * <p>{@code priority} ranks this swap against the other {@link SwapMode#SILENT} swaps competing
     * for the tick's {@link #SILENT_BUDGET} (higher wins); it is ignored for every other mode.
     * Returns false when the swap did not happen — including when it lost the budget — so callers
     * must be able to retry next tick.
     */
    public boolean withSwap(Result target, SwapMode mode, int priority, Runnable action) {
        if (mode == SwapMode.LATCH) throw new IllegalArgumentException("LATCH is held, not run: use latch()");
        if (!onClientThread()) {
            // A failed synchronous request must not secretly execute later with a stale target.
            return false;
        }

        Result resolved = resolve(target, mode);
        if (resolved == null || !resolved.found()) {
            if (logging) log("swap {} caller={} -> no result", mode, caller());
            return false;
        }

        // Already in hand: no swap goes out, so this costs no budget and skips the ranking.
        if (resolved.holding()) {
            if (logging) log("swap {} {} caller={} -> already holding", mode, describe(resolved), caller());
            action.run();
            return true;
        }

        boolean silent = mode == SwapMode.SILENT;
        // Checked before the latch is interrupted: losing the budget after releasing a latch would
        // stop an eat for a swap that then never happens.
        if (silent && !claimSilent(priority)) {
            if (logging) {
                log("swap SILENT {} priority={} caller={} -> out of budget ({}/{} used, reserved={})",
                        describe(resolved), priority, caller(), silentUsed, SILENT_BUDGET, reserved);
            }
            return false;
        }

        // Releasing the latch selects its origin slot back, so holding() — computed above against
        // the slot the latch was pinning — is stale. Re-resolve: the target is often the slot we
        // just came back to, and swapping to the slot we are already on is a no-op that would still
        // spend a budget slot and log as a real swap.
        if (silent && interruptLatch()) {
            resolved = resolve(resolved, mode);
            if (resolved == null || !resolved.found()) {
                if (logging) log("swap {} caller={} -> no result after latch interrupt", mode, caller());
                return false;
            }
            if (resolved.holding()) {
                if (logging) {
                    log("swap {} {} caller={} -> already holding (latch released)", mode,
                            describe(resolved), caller());
                }
                action.run();
                return true;
            }
        }

        if (!permitted(mode)) {
            if (logging) log("swap {} {} caller={} -> blocked (using item)", mode, describe(resolved), caller());
            return false;
        }

        int last = InventoryUtil.selected();
        if (!mode.strategy().swap(resolved)) {
            if (logging) log("swap {} {} caller={} -> strategy refused", mode, describe(resolved), caller());
            return false;
        }
        // The swap back is not optional: if the action throws we are left visibly holding the
        // swapped item, which is the worse of the two outcomes.
        try {
            action.run();
        } finally {
            mode.strategy().swapBack(last, resolved);
        }

        if (silent) commitSilent(priority);
        if (logging) log("swap {} {} from={} caller={} -> done", mode, describe(resolved), last, caller());
        return true;
    }

    public boolean withSwap(Item item, SwapMode mode, Runnable action) {
        return withSwap(item, mode.scope(), mode, 0, action);
    }

    public boolean withSwap(Item item, SwapMode mode, int priority, Runnable action) {
        return withSwap(item, mode.scope(), mode, priority, action);
    }

    public boolean withSwap(Item item, EnumSet<ResultType> scope, SwapMode mode, int priority,
                            Runnable action) {
        return withSwap(InventoryUtil.find(item, scope), mode, priority, action);
    }

    public boolean withSwap(Predicate<ItemStack> predicate, SwapMode mode, int priority,
                            Runnable action) {
        return withSwap(InventoryUtil.find(predicate, mode.scope()), mode, priority, action);
    }

    /** Explicit empty-hand hotbar action. A missing inventory search result is never accepted. */
    public boolean withEmptySlot(int slot, int priority, Runnable action) {
        if (!onClientThread() || nullCheck() || slot < 0 || slot > 8) return false;
        ItemStack empty = mc.player.getInventory().getItem(slot);
        if (!empty.isEmpty()) return false;
        return withSwap(new Result(slot, empty, ResultType.HOTBAR), SwapMode.SILENT, priority, action);
    }

    // ---------------------------------------------------------------- latching

    /**
     * Visibly switches to {@code target} and stays there until {@link #release()}. Fails while
     * another latch is up, or while an item is being used.
     */
    public boolean latch(Result target) {
        return latch(LEGACY_LATCH_OWNER, target);
    }

    /** Claims a visible hold for one identity; callers may release only their own hold. */
    public boolean latch(Object owner, Result target) {
        if (!onClientThread()) return false;
        if (owner == null) throw new IllegalArgumentException("Latch owner is required");

        Result result = resolve(target, SwapMode.LATCH);
        if (result == null || !result.found()) {
            if (logging) log("latch caller={} -> no result", caller());
            return false;
        }
        if (held != null) {
            if (held.owner == owner && held.result.slot() == result.slot()) return true;
            if (logging) log("latch {} caller={} -> busy (held {} ticks)", describe(result), caller(), held.ticksHeld);
            return false;
        }
        if (!permitted(SwapMode.LATCH)) {
            if (logging) log("latch {} caller={} -> blocked (using item)", describe(result), caller());
            return false;
        }

        int last = InventoryUtil.selected();
        if (!SwapMode.LATCH.strategy().swap(result)) {
            if (logging) log("latch {} caller={} -> strategy refused", describe(result), caller());
            return false;
        }

        held = new HeldSwap(owner, result, last);
        if (logging) log("latch {} from={} caller={}", describe(result), last, caller());
        return true;
    }

    public boolean latch(Item item) {
        return latch(InventoryUtil.find(item, SwapMode.LATCH.scope()));
    }

    /** Ends the active latch, selecting the slot it came from back. No-op when nothing is latched. */
    public void release() {
        if (held == null) return;
        if (logging) {
            log("release {} back={} after {} ticks{}", describe(held.result), held.last, held.ticksHeld,
                    nullCheck() ? " (no player, dropped)" : "");
        }
        if (!nullCheck() && mc.player == held.player && mc.level == held.level)
            SwapMode.LATCH.strategy().swapBack(held.last, held.result);
        held = null;
    }

    public void release(Object owner) {
        if (held != null && held.owner == owner) release();
    }

    public boolean isLatched(Object owner) {
        return held != null && held.owner == owner && mc.player == held.player && mc.level == held.level;
    }

    public boolean isLatched() {
        return held != null;
    }

    /** The slot a latch is pinning, or -1. Lets a latch owner detect that its hold was interrupted. */
    public int latchedSlot() {
        return held == null ? -1 : held.result.slot();
    }

    /**
     * A {@link SwapMode#SILENT} swap outranks an active latch: release it and stop the item use it
     * was holding (eating), so the swap can run this tick instead of stalling on
     * {@link #permitted(SwapMode)}. The latching module sees {@link #isLatched()} go false and may
     * re-latch afterwards.
     *
     * @return true when a latch was actually released — the selected slot has moved back to the
     *         latch's origin, so any {@link Result#holding()} taken before this call is stale.
     */
    private boolean interruptLatch() {
        if (held == null) return false;
        if (logging) log("latch interrupted by silent swap after {} ticks", held.ticksHeld);
        release();
        if (!nullCheck() && mc.player.isUsingItem()) mc.player.stopUsingItem();
        return true;
    }

    // ---------------------------------------------------------------- budget

    /**
     * Rations the tick's {@link #SILENT_BUDGET} silent swaps, ranked by {@code priority}.
     *
     * <p>The swap and its action run inline, so a granted request cannot be taken back once a
     * better one shows up later in the tick. Ranking therefore lands one tick late: the highest
     * priority refused this tick is {@linkplain #reserved held} for the next, where the budget's
     * <i>last</i> slot is kept for it and lower-priority requests stop at {@code SILENT_BUDGET - 1}.
     * The reservation is dropped the moment it is honoured, and expires after one tick either way,
     * so a requester that never returns costs at most one slot of one tick.
     *
     * <p>This is a peek: it does not spend the slot. {@link #commitSilent(int)} does, once the swap
     * has actually gone out.
     */
    private boolean claimSilent(int priority) {
        int available = priority < reserved ? SILENT_BUDGET - 1 : SILENT_BUDGET;
        if (silentUsed >= available) {
            if (priority > starved) starved = priority;
            return false;
        }
        return true;
    }

    private void commitSilent(int priority) {
        silentUsed++;
        if (priority >= reserved) reserved = Integer.MIN_VALUE;
    }

    private boolean permitted(SwapMode mode) {
        if (nullCheck()) return false;
        if (mode == SwapMode.ALTSILENT) return true;
        return !mc.player.isUsingItem();
    }

    // ---------------------------------------------------------------- ticking

    /**
     * Opens the tick: re-pins the latch, then refills the silent-swap budget.
     *
     * <p>Ranked to the front of {@link PreTickEvent}, and deliberately, for both halves.
     *
     * <p>The latch goes first so it is holding its slot before anything else in the tick runs. A
     * latch is the only visible swap, so it has to be the tick's starting state: re-pin it later
     * and a module that reads the selected slot early — or a silent swap that reverts to whatever
     * was selected at the time — works off a slot the latch has not reclaimed yet. Ordering it
     * first also means a silent swap that wants the hotbar this tick sees a live latch and
     * {@linkplain #interruptLatch() takes it down} on purpose, rather than racing a re-pin that
     * has not happened.
     *
     * <p>The budget reset follows, and must still precede {@link RotationManager}, which runs its
     * deferred SYNC callbacks at the <i>back</i> of this same event — and those callbacks run
     * swaps. Reset after them and a SYNC swap is charged to the previous tick's budget, which is
     * then wiped, letting one tick emit up to twice {@link #SILENT_BUDGET} slot changes.
     */
    @Subscribe(priority = 2000)
    private void onPreTick(PreTickEvent event) {
        repinLatch();

        silentUsed = 0;
        reserved = starved;
        starved = Integer.MIN_VALUE;
    }

    private void repinLatch() {
        if (held == null) return;

        if (nullCheck() || mc.player != held.player || mc.level != held.level) {
            if (logging) log("latch dropped: no player/world");
            held = null;
            return;
        }

        held.ticksHeld++;

        if (InventoryUtil.selected() != held.result.slot()) {
            if (logging) {
                log("re-pin latch slot {} (was {}) after {} ticks", held.result.slot(),
                        InventoryUtil.selected(), held.ticksHeld);
            }
            InventoryUtil.swap(held.result.slot());
        }
    }

    @Subscribe
    private void onPacketSend(PacketEvent.Send event) {
        if (event.getPacket() instanceof ServerboundSetCarriedItemPacket packet) {
            serverSlot = packet.getSlot();
        }
    }

    /** The slot the server believes we hold, falling back to the client's selection. */
    public int serverSlot() {
        if (mc.player == null) return serverSlot;
        return serverSlot == -1 ? InventoryUtil.selected() : serverSlot;
    }

    // ---------------------------------------------------------------- internals

    /**
     * Re-resolves a {@link Result} against the live inventory.
     *
     * <p>A result is a snapshot: {@code slot}, the {@link ItemStack} object and — critically —
     * {@link Result#holding()}, all frozen at {@link InventoryUtil#find} time. Modules capture it
     * a tick before they use it, and a latch, a restock click or a slot consumption in between makes
     * every one of those fields a lie. The two failure modes are the same bug:
     *
     * <ul>
     *   <li>stale {@code holding == true} takes the "already holding" fast path and fires the
     *       action with whatever is actually in hand — the crystal place that swings the sword;</li>
     *   <li>stale {@code slot} selects a slot the stack has since moved out of — the sword swing
     *       that goes out holding a crystal.</li>
     * </ul>
     *
     * <p>Stack identity is the test: the inventory keeps the same object in a slot until that slot
     * is clicked or emptied, so {@code live == snapshot} means nothing moved and only
     * {@code holding} has to be recomputed. Otherwise the item is re-found in the mode's scope, and
     * a swap that can no longer be satisfied is refused instead of firing on the wrong slot.
     */
    private Result resolve(Result result, SwapMode mode) {
        if (result == null || !result.found() || nullCheck()) return result;

        if (result.type() == ResultType.OFFHAND) {
            if (mc.player.getOffhandItem() == result.stack()) return result;
        } else {
            ItemStack live = mc.player.getInventory().getItem(result.slot());
            // Rebuild rather than return: the constructor recomputes holding() against the slot
            // that is selected *now*, which is the whole point of the refresh.
            boolean explicitEmptySlot = mode == SwapMode.SILENT && result.type() == ResultType.HOTBAR
                    && result.slot() >= 0 && result.slot() <= 8;
            if (live == result.stack() && (!live.isEmpty() || explicitEmptySlot)) {
                return new Result(result.slot(), live, result.type());
            }
        }

        if (result.stack().isEmpty()) return null;
        Result found = InventoryUtil.find(result.stack().getItem(), mode.scope());
        if (logging && found.found() && found.slot() != result.slot()) {
            log("resolve {} moved {}@{} -> {} caller={}", mode,
                    BuiltInRegistries.ITEM.getKey(result.stack().getItem()), result.slot(),
                    found.slot(), caller());
        }
        return found;
    }

    /**
     * Swaps mutate the inventory and send packets, so they have to run on the client thread. A
     * caller on the netty thread (a packet handler reacting to a block update) is bounced onto it
     * and told the swap did not happen, which is true for this tick.
     */
    private boolean onClientThread() {
        return mc.isSameThread();
    }

    /** {@code item@slot(type)} — the identity of one swap, for the log line. */
    private static String describe(Result result) {
        return BuiltInRegistries.ITEM.getKey(result.stack().getItem())
                + "@" + result.slot() + "(" + result.type() + ")";
    }

    /**
     * The class that asked for the swap. Walked off the stack rather than passed in so the call
     * sites stay untouched; only ever computed while {@link #logging} is on.
     */
    private static String caller() {
        return WALKER.walk(frames -> frames
                .map(StackWalker.StackFrame::getDeclaringClass)
                .filter(c -> c != SwapManager.class)
                .map(Class::getSimpleName)
                .findFirst()
                .orElse("?"));
    }

    private void log(String format, Object... args) {
        Object[] all = new Object[args.length + 1];
        all[0] = nullCheck() ? -1 : mc.player.tickCount;
        System.arraycopy(args, 0, all, 1, args.length);
        GuizhouWare.LOGGER.info("[Swap] tick={} " + format, all);
    }

    private static final class HeldSwap {
        private final Object owner;
        private final net.minecraft.client.player.LocalPlayer player;
        private final net.minecraft.client.multiplayer.ClientLevel level;
        private final Result result;
        private final int last;
        private int ticksHeld;

        private HeldSwap(Object owner, Result result, int last) {
            this.owner = owner;
            this.player = mc.player;
            this.level = mc.level;
            this.result = result;
            this.last = last;
        }
    }
}
