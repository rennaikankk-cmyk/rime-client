package dev.guizhou.util.elytra;

public final class MaceDiveStateTest {
    private static int checks;

    public static void main(String[] args) {
        equipmentBarrier();
        boundedFall();
        recoveryLifecycle();
        recoveryDuringItemUse();
        System.out.println("MaceDiveStateTest passed: " + checks + " checks");
    }

    private static void equipmentBarrier() {
        var state = new MaceDiveState();
        check(state.phase() == MaceDiveState.Phase.IDLE, "Initial state is idle");
        check(!state.canAttack(false, true, true), "Idle cannot attack");
        check(state.begin(100), "Start an equipment exchange");
        check(!state.begin(101), "Do not overwrite an active exchange");
        check(!state.canAttack(true, true, true), "A swap does not make gliding smash-ready");
        state.observe(100, false, false);
        check(state.phase() == MaceDiveState.Phase.WAIT_GLIDE_END, "Require a movement tick after swapping");
        state.observe(101, true, false);
        check(state.phase() == MaceDiveState.Phase.WAIT_GLIDE_END, "Wait for actual glide exit");
        state.observe(102, false, false);
        check(state.phase() == MaceDiveState.Phase.DIVING, "Actual glide exit starts the fall window");
        check(!state.canAttack(false, true, false), "Insufficient vanilla fall distance is not a smash");
        check(!state.canAttack(false, false, true), "Ascending is not an attack opportunity");
        check(!state.canAttack(true, true, true), "A resumed glide is not a smash");
        check(state.canAttack(false, true, true), "A real downward smash window can attack");
    }

    private static void boundedFall() {
        var state = new MaceDiveState();
        state.begin(10);
        state.observe(15, true, false);
        check(state.phase() == MaceDiveState.Phase.WAIT_GLIDE_END, "Retain the last wait tick");
        state.observe(16, true, false);
        check(state.phase() == MaceDiveState.Phase.RECOVERING, "A glide that never ends times out");
        state.reset();
        state.begin(10);
        state.observe(11, false, false);
        state.observe(22, false, false);
        check(state.phase() == MaceDiveState.Phase.DIVING, "Retain the last natural fall tick");
        state.observe(23, false, false);
        check(state.phase() == MaceDiveState.Phase.RECOVERING, "Bound an unsuccessful fall");
        state.reset();
        state.begin(10);
        state.observe(9, false, false);
        check(state.phase() == MaceDiveState.Phase.RECOVERING, "A backwards clock cannot extend a fall");
        state.reset();
        state.begin(10);
        state.observe(40, false, false);
        check(state.phase() == MaceDiveState.Phase.RECOVERING, "A skipped tick cannot start a stale fall");
        state.reset();
        state.begin(10);
        state.observe(11, false, false, 0.2, 1.5);
        state.observe(23, false, false, 0.4, 1.5);
        check(state.phase() == MaceDiveState.Phase.DIVING, "Short fall distance may continue past the 12-tick cap");
        state.observe(52, false, false, 0.4, 1.5);
        check(state.phase() == MaceDiveState.Phase.RECOVERING, "An extended fall still ends");
    }

    private static void recoveryLifecycle() {
        var state = new MaceDiveState();
        state.requestRecovery();
        check(state.phase() == MaceDiveState.Phase.IDLE, "Idle recovery is a no-op");
        state.begin(10);
        state.observe(11, false, true);
        check(state.phase() == MaceDiveState.Phase.RECOVERING, "Unsafe inventory or movement starts recovery");
        check(!state.canAttack(false, true, true), "Recovery cannot attack");
        state.observe(1000, false, false);
        check(state.phase() == MaceDiveState.Phase.RECOVERING, "Recovery waits for a safe inventory");
        check(!state.begin(1001), "Pending recovery must finish before a new dive");
        state.reset();
        check(state.begin(1001), "Restored equipment starts a new lifecycle");
        state.observe(1002, false, false);
        state.observe(1003, true, false);
        check(state.phase() == MaceDiveState.Phase.RECOVERING, "Unexpected glide during a dive restores equipment");
        state.reset();
        state.begin(2000);
        state.observe(2001, false, false);
        state.requestRecovery();
        check(state.phase() == MaceDiveState.Phase.RECOVERING, "Attack, target loss and module shutdown can recover");
    }

    private static void recoveryDuringItemUse() {
        var state = new MaceDiveState();
        state.begin(40);
        state.observe(41, false, false);
        state.observe(42, false, true);
        check(state.phase() == MaceDiveState.Phase.RECOVERING, "Starting food use ends the dive immediately");
        check(!state.canAttack(false, true, true), "Food-triggered recovery does not attack");
        check(MaceDiveController.recoverySlotAvailable(2, 6), "Food in another slot allows immediate wing recovery");
        check(MaceDiveController.recoverySlotAvailable(8, 0), "Both ends of the hotbar support recovery");
        check(!MaceDiveController.recoverySlotAvailable(6, 6), "Selecting stored wings does not replace the held stack");
        check(MaceDiveController.recoverySlotAvailable(5, 6), "Switching away from stored wings unblocks recovery");
        check(!MaceDiveController.recoverySlotAvailable(-1, 6), "Unknown selection cannot recover");
        check(!MaceDiveController.recoverySlotAvailable(2, -1), "Unknown stored slot cannot recover");
        check(!MaceDiveController.recoverySlotAvailable(9, 6), "A non-hotbar selection cannot recover");
        check(!MaceDiveController.recoverySlotAvailable(2, 9), "Main inventory is not a recovery swap button");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
