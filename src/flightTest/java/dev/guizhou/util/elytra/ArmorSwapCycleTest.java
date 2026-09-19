package dev.guizhou.util.elytra;

import java.util.ArrayList;
import java.util.List;

public final class ArmorSwapCycleTest {
    private static int checks;

    public static void main(String[] args) {
        continuousExchange();
        delayedServerClearKeepsWingsOnEveryMove();
        rocketWaitsForRestartWindow();
        ownershipLostDuringDelayedClearAbandons();
        explicitDiveHandoverDuringDelayedClear();
        interruptionBetweenMovementHooks();
        stopDefersAllEquipmentCallbacks();
        failedEquipmentExchange();
        rejectedGlideRestart();
        glideLostDuringMovement();
        manualInventoryChanges();
        invalidBeginAndAbandon();
        maceDescentYieldsAndReacquires();
        explicitDiveOwnsEquipmentUntilRecovery();
        System.out.println("ArmorSwapCycleTest passed: " + checks + " checks");
    }

    private static void continuousExchange() {
        Model player = new Model();
        ArmorSwapCycle cycle = begin(player);
        for (int tick = 0; tick < 128; tick++) {
            player.newTick(true);
            cycle.beforeMovement(true, true, true);
            check(player.chest == player.wings, "Movement must observe the original wings on tick " + tick);
            check(player.gliding, "Movement must observe a started glide on tick " + tick);
            player.move();
            cycle.afterMovement(true, true, true);
            check(player.chest == player.armor && player.reserve == player.wings,
                    "The same owned chestplate must be equipped after movement on tick " + tick);
            check(player.events.equals(tick == 0
                            ? List.of("MOVE_WINGS", "SWAP_ARMOR")
                            : List.of("SWAP_WINGS", "START", "MOVE_WINGS", "SWAP_ARMOR")),
                    "Equipment and restart packets must surround movement in order on tick " + tick);
            player.assertTickBounds();
        }
        check(player.duplicateStarts == 0, "A repeated START must never toggle an existing glide off");
        check(player.restorations == 0, "An ongoing cycle must not release equipment ownership");
    }

    private static void delayedServerClearKeepsWingsOnEveryMove() {
        Model player = new Model();
        ArmorSwapCycle cycle = begin(player);
        cycle.beforeMovement(true, true, true);
        player.move();
        cycle.afterMovement(true, true, true);
        for (int tick = 0; tick < 8; tick++) {
            player.newTick(false);
            cycle.beforeMovement(true, true, true);
            check(player.chest == player.wings, "A delayed server clear must not postpone the movement wing swap on tick " + tick);
            check(player.gliding, "The still-running glide must be ridden, not restarted, on tick " + tick);
            player.move();
            check(player.events.equals(List.of("SWAP_WINGS", "MOVE_WINGS")),
                    "A delayed-clear tick exchanges wings for movement without any START on tick " + tick);
            cycle.afterMovement(true, true, true);
            check(player.chest == player.armor && player.reserve == player.wings,
                    "Armor returns after each delayed-clear movement on tick " + tick);
            player.assertTickBounds();
        }
        check(player.duplicateStarts == 0 && player.startsThisTick == 0,
                "Persistent wing exchanges must never toggle the glide flag");
        player.newTick(true);
        cycle.beforeMovement(true, true, true);
        check(player.events.equals(List.of("SWAP_WINGS", "START")),
                "Restart exactly once after the server finally clears the glide");
        check(player.gliding && player.duplicateStarts == 0, "The observed restart must leave the player gliding");
        player.move();
        cycle.afterMovement(true, true, true);
        check(player.events.equals(List.of("SWAP_WINGS", "START", "MOVE_WINGS", "SWAP_ARMOR")),
                "The restarted glide resumes the regular per-movement exchange");
        player.assertTickBounds();
    }

    private static void rocketWaitsForRestartWindow() {
        Model player = new Model();
        ArmorSwapCycle cycle = begin(player);
        check(!cycle.rocketWindowOpen(), "Acquiring wings does not prove server-side flight for a rocket");
        cycle.beforeMovement(true, true, true);
        check(!cycle.rocketWindowOpen(), "A locally persistent glide flag must not authorize armor-cycle rockets");
        cycle.afterMovement(true, true, true);
        for (int i = 0; i < 4; i++) {
            player.newTick(false);
            cycle.beforeMovement(true, true, true);
            check(!cycle.rocketWindowOpen(), "Delayed metadata must defer the rocket without duplicate START");
            cycle.afterMovement(true, true, true);
        }
        player.newTick(true);
        cycle.beforeMovement(true, true, true);
        check(cycle.rocketWindowOpen(), "A successful restart must permit the rocket in that movement window");
        check(player.events.equals(List.of("SWAP_WINGS", "START")), "The rocket window follows wings then START");
        cycle.afterMovement(true, true, true);
        check(!cycle.rocketWindowOpen(), "Returning armor closes the rocket window immediately");
        player.newTick(true);
        cycle.beforeMovement(true, true, true);
        check(cycle.rocketWindowOpen(), "A later restart can open another rocket window");
        cycle.abandon();
        check(!cycle.rocketWindowOpen(), "A dive or session handover must discard the rocket window");
        check(player.duplicateStarts == 0, "Deferring rockets must not invent duplicate glide commands");

        player = new Model();
        cycle = begin(player);
        cycle.beforeMovement(true, true, true);
        cycle.afterMovement(true, true, true);
        player.newTick(true);
        player.canStart = false;
        cycle.beforeMovement(true, true, true);
        check(!cycle.rocketWindowOpen(), "Rejected glide restart must not permit a rocket");
    }

    private static void ownershipLostDuringDelayedClearAbandons() {
        Model player = new Model();
        ArmorSwapCycle cycle = begin(player);
        cycle.beforeMovement(true, true, true);
        cycle.afterMovement(true, true, true);
        player.newTick(false);
        player.chest = new Object();
        cycle.beforeMovement(true, true, true);
        cycle.afterMovement(true, true, true);
        cycle.stop(true);
        check(player.events.isEmpty() && player.restorations == 0 && !cycle.active(),
                "Losing the pair inside a delayed-clear window abandons without any swap or restore");
    }

    private static void explicitDiveHandoverDuringDelayedClear() {
        Model player = new Model();
        ArmorSwapCycle cycle = begin(player);
        cycle.beforeMovement(true, true, true);
        player.move();
        cycle.afterMovement(true, true, true);
        player.newTick(false);
        cycle.beforeMovement(true, true, true);
        check(player.chest == player.wings && player.events.equals(List.of("SWAP_WINGS")),
                "Wings are equipped for movement when the dive owner takes over");
        cycle.abandon();
        player.move();
        cycle.afterMovement(true, true, true);
        cycle.stop(true);
        check(player.events.equals(List.of("SWAP_WINGS", "MOVE_WINGS")),
                "A dive handover keeps the dive's wings and never returns armor");
        check(player.restorations == 0 && !cycle.active(), "The dive owns the pair without a stale restore callback");
    }

    private static void interruptionBetweenMovementHooks() {
        for (String reason : List.of("gui", "disabled", "landed")) {
            Model player = new Model();
            ArmorSwapCycle cycle = begin(player);
            player.newTick(false);
            cycle.beforeMovement(true, true, true);
            player.move();
            boolean requested = !reason.equals("disabled");
            boolean inventorySafe = !reason.equals("gui");
            boolean canGlide = !reason.equals("landed");
            player.canStart = canGlide;
            cycle.afterMovement(requested, inventorySafe, canGlide);
            check(player.chest == player.wings && player.reserve == player.armor,
                    reason + " between hooks must not put chest armor on after movement");
            check(player.swapsThisTick == 0 && player.startsThisTick == 0,
                    reason + " must not produce a stale exchange or restart");
            if (!inventorySafe) {
                check(cycle.active() && player.restorations == 0,
                        "Opening a GUI defers the external restore callback even with wings already equipped");
                cycle.stop(false);
                check(player.restorations == 0, "Unsafe stop must also defer the restore callback");
                cycle.stop(true);
            }
            check(!cycle.active() && player.restorations == 1,
                    reason + " must finish equipment recovery once the inventory is safe");
            player.assertTickBounds();
        }

        Model player = new Model();
        ArmorSwapCycle cycle = begin(player);
        cycle.beforeMovement(true, true, true);
        cycle.afterMovement(true, true, true);
        player.newTick(false);
        cycle.beforeMovement(true, true, true);
        check(player.events.equals(List.of("SWAP_WINGS")),
                "A delayed clear still restores wings before the movement window");
        cycle.afterMovement(false, false, false);
        check(player.chest == player.wings && player.events.equals(List.of("SWAP_WINGS")),
                "GUI and landing after the wing swap defer both the armor return and the restore callback");
        cycle.beforeMovement(false, true, false);
        check(player.events.equals(List.of("SWAP_WINGS", "RESTORED")),
                "Safe recovery after landing restores ownership without trying to fly");
        check(player.restorations == 1 && !cycle.active(), "Delayed recovery must finish exactly once");
    }

    private static void stopDefersAllEquipmentCallbacks() {
        Model player = new Model();
        ArmorSwapCycle cycle = begin(player);
        cycle.beforeMovement(true, true, true);
        cycle.afterMovement(true, true, true);
        for (int tick = 0; tick < 8; tick++) {
            player.newTick(true);
            cycle.stop(false);
            cycle.afterMovement(false, false, false);
            check(player.chest == player.armor && player.reserve == player.wings,
                    "Stop with an unsafe inventory retains both owned items");
            check(player.events.isEmpty() && player.restorations == 0 && cycle.active(),
                    "Deferred shutdown cannot perform swaps, starts or external restoration");
            player.assertTickBounds();
        }
        player.newTick(true);
        cycle.stop(true);
        cycle.stop(true);
        cycle.afterMovement(false, true, false);
        check(player.events.equals(List.of("SWAP_WINGS", "RESTORED")),
                "The first safe stopped tick restores exactly once and never restarts flight");
        check(player.chest == player.wings && player.reserve == player.armor && !cycle.active(),
                "Stopped cycle leaves the original pair restored");
        player.assertTickBounds();
    }

    private static void failedEquipmentExchange() {
        for (boolean falseSuccess : new boolean[]{false, true}) {
            Model player = new Model();
            ArmorSwapCycle cycle = begin(player);
            cycle.beforeMovement(true, true, true);
            cycle.afterMovement(true, true, true);
            player.failSwap = true;
            player.reportSwapSuccess = falseSuccess;
            for (int tick = 0; tick < 3; tick++) {
                player.newTick(true);
                cycle.beforeMovement(true, true, true);
                cycle.afterMovement(true, true, true);
                check(player.chest == player.armor && player.reserve == player.wings,
                        "Rejected equipment exchanges must preserve the existing pair");
                check(player.startsThisTick == 0 && player.restorations == 0,
                        "An acknowledged but unapplied swap must not start glide or release ownership");
                player.assertTickBounds();
            }
            player.failSwap = false;
            player.newTick(true);
            cycle.beforeMovement(true, true, true);
            cycle.afterMovement(true, true, true);
            check(player.events.equals(List.of("SWAP_WINGS", "RESTORED")),
                    "A swap failure makes recovery sticky even if the feature is still requested");
            check(player.chest == player.wings && !cycle.active(), "Successful retry must recover the actual wings");
        }

        Model player = new Model();
        ArmorSwapCycle cycle = begin(player);
        player.failSwap = true;
        cycle.beforeMovement(true, true, true);
        cycle.afterMovement(true, true, true);
        check(player.chest == player.wings && player.startsThisTick == 0,
                "A failed post-movement armor swap cannot remove wings or repeat START");
        player.newTick(false);
        cycle.beforeMovement(true, true, true);
        cycle.afterMovement(true, true, true);
        check(player.events.equals(List.of("RESTORED")), "Already-worn wings recover without another swap attempt");
    }

    private static void rejectedGlideRestart() {
        Model player = new Model();
        ArmorSwapCycle cycle = begin(player);
        cycle.beforeMovement(true, true, true);
        cycle.afterMovement(true, true, true);
        player.newTick(true);
        player.canStart = false;
        cycle.beforeMovement(true, true, true);
        cycle.afterMovement(true, true, true);
        check(player.events.equals(List.of("SWAP_WINGS", "START", "RESTORED")),
                "A rejected restart recovers without putting armor back on");
        check(player.chest == player.wings && !player.gliding && !cycle.active(),
                "Rejected flight must leave wings equipped for ordinary fall recovery");
        player.assertTickBounds();
    }

    private static void glideLostDuringMovement() {
        Model player = new Model();
        ArmorSwapCycle cycle = begin(player);
        cycle.beforeMovement(true, true, true);
        player.gliding = false;
        cycle.afterMovement(true, true, true);
        check(player.chest == player.wings && player.events.equals(List.of("RESTORED")),
                "A correction ending glide between hooks must cancel the pending armor exchange");
        check(!cycle.active() && player.startsThisTick == 0, "A correction must not cause an immediate restart loop");
    }

    private static void manualInventoryChanges() {
        for (boolean changeChest : new boolean[]{false, true}) {
            Model player = new Model();
            ArmorSwapCycle cycle = begin(player);
            cycle.beforeMovement(true, true, true);
            Object replacement = new Object();
            if (changeChest) player.chest = replacement;
            else player.reserve = replacement;
            cycle.afterMovement(true, true, true);
            cycle.stop(true);
            check((changeChest ? player.chest : player.reserve) == replacement,
                    "Manual inventory replacements must not be swapped away");
            check(player.events.isEmpty() && player.restorations == 0 && !cycle.active(),
                    "Losing the original pair abandons ownership without a stale restore callback");
        }

        Model player = new Model();
        ArmorSwapCycle cycle = begin(player);
        player.replaceDuringSwap = true;
        cycle.beforeMovement(true, true, true);
        cycle.afterMovement(true, true, true);
        player.newTick(true);
        cycle.stop(true);
        check(player.events.isEmpty() && player.restorations == 0 && !cycle.active(),
                "An inventory change during a swap must abandon the pair before any later cleanup");
        check(player.reserve != player.wings && player.reserve != player.armor,
                "Post-swap validation must preserve the replacement item");
    }

    private static void invalidBeginAndAbandon() {
        Model player = new Model();
        ArmorSwapCycle cycle = new ArmorSwapCycle();
        player.gliding = false;
        check(!cycle.begin(player), "A player who is not gliding cannot begin the equipment cycle");
        player.gliding = true;
        player.chest = player.armor;
        player.reserve = player.wings;
        check(!cycle.begin(player), "Armor alone is not a valid initial flight state");
        player.chest = player.wings;
        player.reserve = new Object();
        check(!cycle.begin(player), "Starting requires a complete owned equipment pair");
        check(player.events.isEmpty(), "Rejected begin calls must never modify inventory or glide state");
        player.reserve = player.armor;
        check(cycle.begin(player), "An intact gliding pair can start");
        Model otherPlayer = new Model();
        check(!cycle.begin(otherPlayer), "An active pair must not be replaced by another player or world");
        cycle.beforeMovement(true, true, true);
        cycle.abandon();
        cycle.afterMovement(true, true, true);
        cycle.stop(true);
        check(player.events.isEmpty() && otherPlayer.events.isEmpty(),
                "Abandoning a world must cancel pending armor swaps without touching either player");
    }

    private static ArmorSwapCycle begin(Model player) {
        ArmorSwapCycle cycle = new ArmorSwapCycle();
        check(cycle.begin(player), "The initially gliding equipment pair must be accepted");
        return cycle;
    }

    private static void maceDescentYieldsAndReacquires() {
        Model player = new Model();
        ArmorSwapCycle cycle = begin(player);
        cycle.beforeMovement(true, true, true);
        cycle.afterMovement(true, true, true);
        player.newTick(true);
        check(!player.gliding && player.chest == player.armor, "Server observes the chestplate before the natural mace fall");
        cycle.beforeMovement(false, true, true);
        cycle.afterMovement(false, true, true);
        check(player.events.equals(List.of("SWAP_WINGS", "RESTORED")),
                "Yielding to a mace pass restores equipment without issuing START");
        check(!player.gliding && !cycle.active(), "Wearing wings alone must preserve the natural attack fall");
        for (int tick = 0; tick < 3; tick++) {
            player.newTick(true);
            cycle.beforeMovement(false, true, true);
            cycle.afterMovement(false, true, true);
            check(player.events.isEmpty() && !player.gliding, "The armor cycle must not reclaim a pending attack pass");
        }
        check(player.startGliding(), "The combat owner can restart after completing its attack pass");
        check(cycle.begin(player), "The restored original pair can be acquired for the next flight cycle");
        player.newTick(true);
        cycle.beforeMovement(true, true, true);
        cycle.afterMovement(true, true, true);
        check(player.events.equals(List.of("SWAP_ARMOR")) && player.duplicateStarts == 0,
                "The resumed cycle returns armor without sending a duplicate START");
        check(player.chest == player.armor && player.reserve == player.wings, "Resuming preserves both original items");
    }

    private static void explicitDiveOwnsEquipmentUntilRecovery() {
        Model player = new Model();
        ArmorSwapCycle cycle = begin(player);
        MaceDiveState dive = new MaceDiveState();
        cycle.beforeMovement(true, true, true);
        check(dive.begin(0), "An explicit dive can begin after the armor cycle prepares its wings");
        check(player.swap(), "The dive equips the paired chestplate");
        cycle.abandon();
        player.newTick(true);
        cycle.afterMovement(true, true, true);
        cycle.stop(true);
        dive.observe(1, player.gliding, false);
        check(dive.canAttack(player.gliding, true, true), "The dive keeps its natural smash window");
        check(player.events.isEmpty() && player.restorations == 0,
                "The old cycle must never restore or exchange the dive-owned equipment");
        dive.requestRecovery();
        check(player.swap() && player.startGliding(), "The combat owner restores wings and flight after the pass");
        dive.reset();
        check(cycle.begin(player), "The armor cycle can reacquire only after explicit dive recovery");
        player.newTick(false);
        cycle.beforeMovement(true, true, true);
        cycle.afterMovement(true, true, true);
        check(player.events.equals(List.of("SWAP_ARMOR")) && player.duplicateStarts == 0,
                "The first resumed movement uses the existing recovered glide");
    }

    // The server observes the equipment at tick boundaries and toggles glide on repeated START.
    private static final class Model implements ArmorSwapCycle.Access {
        private final Object wings = new Object();
        private final Object armor = new Object();
        private Object chest = wings;
        private Object reserve = armor;
        private boolean gliding = true;
        private boolean canStart = true;
        private boolean failSwap;
        private boolean reportSwapSuccess;
        private boolean replaceDuringSwap;
        private int restorations;
        private int duplicateStarts;
        private int swapsThisTick;
        private int startsThisTick;
        private final List<String> events = new ArrayList<>();

        private void newTick(boolean serverObservesEquipment) {
            events.clear();
            swapsThisTick = 0;
            startsThisTick = 0;
            if (serverObservesEquipment && chest != wings) gliding = false;
        }

        private void move() {
            events.add(chest == wings ? "MOVE_WINGS" : "MOVE_ARMOR");
        }

        private void assertTickBounds() {
            check(swapsThisTick <= 2, "A tick may attempt at most one exchange per movement hook");
            check(startsThisTick <= 1, "A tick may issue at most one flight restart");
            check(duplicateStarts == 0, "The simulated server must never receive START while already gliding");
        }

        @Override
        public boolean intact() {
            return chest == wings && reserve == armor || chest == armor && reserve == wings;
        }

        @Override
        public boolean wearingWings() {
            return chest == wings;
        }

        @Override
        public boolean swap() {
            swapsThisTick++;
            if (failSwap) {
                events.add("SWAP_REJECTED");
                return reportSwapSuccess;
            }
            Object oldChest = chest;
            chest = reserve;
            reserve = oldChest;
            events.add(chest == wings ? "SWAP_WINGS" : "SWAP_ARMOR");
            if (replaceDuringSwap) reserve = new Object();
            return true;
        }

        @Override
        public boolean gliding() {
            return gliding;
        }

        @Override
        public boolean startGliding() {
            startsThisTick++;
            events.add("START");
            if (gliding) {
                duplicateStarts++;
                gliding = false;
            } else if (canStart && chest == wings) {
                gliding = true;
            }
            return gliding;
        }

        @Override
        public void restored() {
            check(chest == wings && reserve == armor, "Restore callback requires the original pair in its original slots");
            restorations++;
            events.add("RESTORED");
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
