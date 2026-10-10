package dev.guizhou.util.inventory;

/**
 * The ranking every module competes under for the {@link dev.guizhou.manager.SwapManager}'s
 * rationed per-tick {@link SwapMode#SILENT} swaps.
 *
 * <p>Higher wins. The numbers are spaced so a module can offset itself a step either way without
 * needing a new constant; only their order matters. They live in one file because the whole point
 * of the ranking is that it can be read as a list — a tick that cannot do everything should drop
 * the firework, never the crystal.
 *
 * <p>The budget is granted first-come and ranks one tick late (a packet already sent cannot be
 * recalled), so a priority here is a claim on the <i>next</i> tick, not a guarantee for this one.
 * Callers must still handle refusal by retrying.
 */
public final class SwapPriority {
    /** Gapple/totem management. Held as a {@link SwapMode#LATCH}, and outranks everything. */
    public static final int OFFHAND = 500;

    /** Block placement (surround, traps, holes). The whole batch rides one swap. */
    public static final int PLACEMENT = 400;

    /** Crystal placement — sets up the damage. */
    public static final int CRYSTAL = 350;

    /** Anchor burst: comparable damage to a crystal, but slower and easier to retry. */
    public static final int ANCHOR = 300;

    /** Weapon swap behind a melee attack; a miss costs a cooldown, not a life. */
    public static final int WEAPON = 200;

    /** Escape items (phase pearl). Rare and user-driven, so it rarely displaces combat. */
    public static final int ESCAPE = 175;

    /** Explicit user input — keybound potions, buckets, portal ignites. */
    public static final int USER_ACTION = 150;

    /** Finishing a break early. Purely a speed gain — nothing breaks if it slips a tick. */
    public static final int MINING = 50;

    /** Pearls, rockets, and everything else with no deadline at all. */
    public static final int UTILITY = 0;

    private SwapPriority() {
        throw new AssertionError();
    }
}
