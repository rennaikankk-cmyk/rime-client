package dev.guizhou.util.player;

/**
 * Pure bookkeeping for one client-started item use: the ItemStack identity that
 * started it, the hand it started on, and the player/world session it belongs
 * to. No Minecraft types, so the ownership rules stay unit-testable.
 */
public final class ItemUseOwnership {
    public enum Hand { MAIN, OFF }

    /** Minimum gap between two use attempts (failed attempts included), in caller ticks. */
    public static final long RETRY_INTERVAL_TICKS = 4L;

    private Object player;
    private Object level;
    private Object stack;
    private Hand hand;
    private boolean owned;
    private long lastAttemptTick = Long.MIN_VALUE;

    public boolean owned() {
        return owned;
    }

    public Hand hand() {
        return hand;
    }

    public Object stack() {
        return stack;
    }

    /** True when the ongoing use is exactly the one this object started. */
    public boolean ownsActiveUse(Object currentPlayer, Object currentLevel, Object usingStack, boolean mainHand) {
        return owned && this.player == currentPlayer && this.level == currentLevel
                && this.stack == usingStack && this.hand == (mainHand ? Hand.MAIN : Hand.OFF);
    }

    public boolean ownsHeldUse(Object currentPlayer, Object currentLevel, Object usingStack,
                               Object heldStack, boolean mainHand, int selected, int originallySelected) {
        // Only a main-hand use follows the selected hotbar slot; vanilla keeps an
        // off-hand use running through a main-hand scroll, and dropping ownership
        // there would let the key-release guard put a held shield down.
        return (!mainHand || selected == originallySelected) && heldStack == stack
                && ownsActiveUse(currentPlayer, currentLevel, usingStack, mainHand);
    }

    /** True when the player or the world changed since the owned use started. */
    public boolean sessionChanged(Object currentPlayer, Object currentLevel) {
        return owned && (this.player != currentPlayer || this.level != currentLevel);
    }

    public void start(Object player, Object level, Object stack, Hand hand) {
        this.player = player;
        this.level = level;
        this.stack = stack;
        this.hand = hand;
        this.owned = true;
    }

    /** Drop the bookkeeping without touching game state; a manual press or a foreign use takes over. */
    public void forget() {
        owned = false;
        player = null;
        level = null;
        stack = null;
        hand = null;
    }

    /** A held use key outranks the module: the running charge transfers to the player. */
    public boolean shouldYieldToManual(boolean useKeyDown) {
        return owned && useKeyDown;
    }

    /** Spaced retries; a rewound tick counter (new session) never deadlocks the throttle. */
    public boolean canAttempt(long tick) {
        return lastAttemptTick == Long.MIN_VALUE
                || tick >= lastAttemptTick + RETRY_INTERVAL_TICKS
                || tick < lastAttemptTick;
    }

    public void recordAttempt(long tick) {
        lastAttemptTick = tick;
    }
}
