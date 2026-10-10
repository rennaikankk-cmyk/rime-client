package dev.guizhou.util.player;

public final class ItemUseOwnershipTest {
    private static int checks;

    public static void main(String[] args) {
        ownershipIdentityAndMismatch();
        manualTakeover();
        sessionChange();
        retryThrottle();
        System.out.println("ItemUseOwnershipTest passed: " + checks + " checks");
    }

    private static void ownershipIdentityAndMismatch() {
        Object player = new Object();
        Object level = new Object();
        Object stack = new Object();
        Object otherStack = new Object();
        ItemUseOwnership ownership = new ItemUseOwnership();
        check(!ownership.owned(), "A fresh ownership has nothing held");
        ownership.start(player, level, stack, ItemUseOwnership.Hand.MAIN);
        check(ownership.owned() && ownership.hand() == ItemUseOwnership.Hand.MAIN
                && ownership.stack() == stack, "Start records stack identity and hand");
        check(ownership.ownsActiveUse(player, level, stack, true),
                "The exact stack, hand and session are recognized as ours");
        check(!ownership.ownsActiveUse(player, level, otherStack, true),
                "A different stack identity is not ours");
        check(!ownership.ownsActiveUse(player, level, stack, false),
                "A different hand is not ours");
        check(!ownership.ownsActiveUse(new Object(), level, stack, true),
                "A different player token is not ours");
        check(!ownership.ownsActiveUse(player, new Object(), stack, true),
                "A different world token is not ours");
        check(ownership.ownsHeldUse(player, level, stack, stack, true, 2, 2), "Held identity and selection match");
        check(!ownership.ownsHeldUse(player, level, stack, stack, true, 3, 2), "Manual selection relinquishes use immediately");
        check(!ownership.ownsHeldUse(player, level, stack, otherStack, true, 2, 2), "A stale use stack does not own the newly held item");
        ownership.start(player, level, stack, ItemUseOwnership.Hand.OFF);
        check(ownership.ownsHeldUse(player, level, stack, stack, false, 2, 2), "Offhand ownership works");
        check(ownership.ownsHeldUse(player, level, stack, stack, false, 3, 2),
                "Offhand use survives a main-hand slot scroll, as in vanilla");
        check(!ownership.ownsHeldUse(player, level, stack, otherStack, false, 2, 2),
                "Offhand ownership still requires the held stack identity");
    }

    private static void manualTakeover() {
        Object player = new Object();
        Object level = new Object();
        ItemUseOwnership ownership = new ItemUseOwnership();
        ownership.start(player, level, new Object(), ItemUseOwnership.Hand.OFF);
        check(!ownership.shouldYieldToManual(false),
                "A released use key alone does not force a yield");
        check(ownership.shouldYieldToManual(true),
                "A held use key must take over the owned charge");
        ownership.forget();
        check(!ownership.owned() && !ownership.shouldYieldToManual(true),
                "After yielding, nothing is owned anymore");
        check(ownership.hand() == null && ownership.stack() == null,
                "Yield drops the stack and hand bookkeeping");
    }

    private static void sessionChange() {
        Object player = new Object();
        Object level = new Object();
        ItemUseOwnership ownership = new ItemUseOwnership();
        check(!ownership.sessionChanged(player, level),
                "Without ownership there is no session to change");
        ownership.start(player, level, new Object(), ItemUseOwnership.Hand.MAIN);
        check(!ownership.sessionChanged(player, level),
                "The same player and world keep the session");
        check(ownership.sessionChanged(new Object(), level),
                "A new player instance changes the session");
        check(ownership.sessionChanged(player, new Object()),
                "A new world changes the session");
        ownership.forget();
        check(!ownership.sessionChanged(new Object(), new Object()),
                "After forget, session tokens are irrelevant");
    }

    private static void retryThrottle() {
        ItemUseOwnership ownership = new ItemUseOwnership();
        check(ownership.canAttempt(0), "The first attempt is always allowed");
        ownership.recordAttempt(10);
        check(!ownership.canAttempt(11) && !ownership.canAttempt(13),
                "Attempts within the interval are rejected");
        check(ownership.canAttempt(14), "Exactly the interval later is allowed");
        check(ownership.canAttempt(9), "A rewound tick counter never deadlocks the throttle");
        ownership.forget();
        check(!ownership.canAttempt(12),
                "Forgetting ownership does not reset attempt spacing");
        ownership.recordAttempt(20);
        check(ownership.canAttempt(24) && !ownership.canAttempt(23),
                "A new attempt re-arms the full interval");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
