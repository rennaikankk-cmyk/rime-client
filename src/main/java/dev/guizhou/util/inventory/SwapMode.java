package dev.guizhou.util.inventory;

import dev.guizhou.util.inventory.strategy.HotbarStrategy;
import dev.guizhou.util.inventory.strategy.InventoryStrategy;
import dev.guizhou.util.inventory.strategy.SwapStrategy;

import java.util.EnumSet;

/**
 * Binds a swap <i>mechanism</i> ({@link SwapStrategy}) to the lifecycle metadata the
 * {@link dev.guizhou.manager.SwapManager} needs to drive it. The strategy is the "how", this enum
 * is the "when/where".
 */
public enum SwapMode {
    /**
     * Client-side hotbar select, reverted within the same tick before the next render — no visible
     * switch. Reaches hotbar/offhand items only.
     *
     * <p>The only rationed mode: the {@link dev.guizhou.manager.SwapManager} allows a few of these
     * per tick and ranks the rest by priority, so a swap can be refused and has to be retried.
     */
    SILENT(HotbarStrategy.INSTANCE, InventoryUtil.HOTBAR_SCOPE, true),

    /**
     * Window {@code SWAP} click that moves the target into the held slot and back within the tick;
     * the selected slot index never moves, so it is invisible and can pull from the main inventory
     * too. Unrationed, and permitted while an item is being used.
     */
    ALTSILENT(InventoryStrategy.INSTANCE, InventoryUtil.FULL_SCOPE, false),

    /**
     * Visible hotbar switch with no tick timeout: the latch stays held (and re-pinned against
     * desync) until a module ends it via {@link dev.guizhou.manager.SwapManager#release()}.
     * Reaches hotbar/offhand items only.
     */
    LATCH(HotbarStrategy.INSTANCE, InventoryUtil.HOTBAR_SCOPE, true);

    private final SwapStrategy strategy;
    private final EnumSet<ResultType> scope;
    private final boolean pinsSelectedSlot;

    SwapMode(SwapStrategy strategy, EnumSet<ResultType> scope, boolean pinsSelectedSlot) {
        this.strategy = strategy;
        this.scope = scope;
        this.pinsSelectedSlot = pinsSelectedSlot;
    }

    public SwapStrategy strategy() {
        return strategy;
    }

    /** The default search scope this mode can actually reach. */
    public EnumSet<ResultType> scope() {
        return scope;
    }

    /**
     * True when the mechanism works by changing the selected hotbar slot, and therefore needs the
     * manager to re-pin that slot each tick while a swap is held.
     */
    public boolean pinsSelectedSlot() {
        return pinsSelectedSlot;
    }
}
