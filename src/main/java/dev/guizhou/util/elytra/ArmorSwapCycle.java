package dev.guizhou.util.elytra;

/** Orders an owned equipment pair around every movement window; gliding restarts only after an observed end. */
public final class ArmorSwapCycle {
    public interface Access {
        boolean intact();
        boolean wearingWings();
        boolean swap();
        boolean gliding();
        boolean startGliding();
        void restored();
    }

    private Access access;
    private boolean recovering;
    private boolean returnArmor;
    private boolean restartedThisWindow;
    private boolean deferArmorReturn;
    private boolean boostHeldThisWindow;

    public boolean active() {
        return access != null;
    }

    /** A local glide flag can outlive the server's armor swap. Couple rockets to a fresh restart. */
    public boolean rocketWindowOpen() {
        return access != null && restartedThisWindow && !recovering
                && access.intact() && access.wearingWings() && access.gliding();
    }

    public boolean begin(Access candidate) {
        if (active() || !candidate.intact() || !candidate.wearingWings() || !candidate.gliding()) return false;
        access = candidate;
        recovering = false;
        returnArmor = false;
        restartedThisWindow = false;
        deferArmorReturn = false;
        boostHeldThisWindow = false;
        return true;
    }

    public void beforeMovement(boolean requested, boolean inventorySafe, boolean canGlide) {
        beforeMovement(requested, inventorySafe, canGlide, false);
    }

    /**
     * {@code boostOnWornGlide} opens the rocket window when this tick already
     * started on elytra and a glide, so the firework can launch before chestplate.
     * A wings swap performed inside this call is not that window.
     */
    public void beforeMovement(boolean requested, boolean inventorySafe, boolean canGlide, boolean boostOnWornGlide) {
        returnArmor = false;
        restartedThisWindow = false;
        deferArmorReturn = false;
        boostHeldThisWindow = false;
        if (!validate()) return;
        if (!requested || !inventorySafe || !canGlide) recovering = true;
        if (recovering) {
            recover(inventorySafe);
            return;
        }
        boolean enteredOnWings = access.wearingWings();
        if (!enteredOnWings) {
            // The movement window must observe the owned wings even while an earlier
            // glide flag is still running; a window click never toggles the glide flag.
            if (!swapTo(true)) return;
        }
        // A repeated START while already gliding makes the vanilla server stop flight.
        if (!access.gliding()) {
            if (!access.startGliding()) {
                recovering = true;
                recover(inventorySafe);
                return;
            }
            restartedThisWindow = true;
        } else if (boostOnWornGlide && enteredOnWings) {
            restartedThisWindow = true;
            deferArmorReturn = true;
        }
        returnArmor = access.gliding();
    }

    public void afterMovement(boolean requested, boolean inventorySafe, boolean canGlide) {
        afterMovement(requested, inventorySafe, canGlide, false);
    }

    /** {@code rocketSent} keeps wings on for this window so the chest swap does not eat the firework. */
    public void afterMovement(boolean requested, boolean inventorySafe, boolean canGlide, boolean rocketSent) {
        if (deferArmorReturn && rocketSent) boostHeldThisWindow = true;
        deferArmorReturn = false;
        restartedThisWindow = false;
        if (boostHeldThisWindow) {
            returnArmor = false;
            return;
        }
        if (!validate()) return;
        if (!requested || !inventorySafe || !canGlide) recovering = true;
        if (recovering) {
            returnArmor = false;
            recover(inventorySafe);
        } else if (returnArmor) {
            returnArmor = false;
            if (!access.gliding()) {
                recovering = true;
                recover(inventorySafe);
            } else {
                swapTo(false);
            }
        }
    }

    public void stop(boolean inventorySafe) {
        beforeMovement(false, inventorySafe, false);
    }

    /** Another equipment owner or a new world must never trigger a stale return swap. */
    public void abandon() {
        access = null;
        recovering = false;
        returnArmor = false;
        restartedThisWindow = false;
        deferArmorReturn = false;
        boostHeldThisWindow = false;
    }

    private boolean validate() {
        if (access != null && !access.intact()) abandon();
        return active();
    }

    private boolean swapTo(boolean wings) {
        if (!validate()) return false;
        if (access.wearingWings() == wings) return true;
        boolean swapped = access.swap();
        if (!validate()) return false;
        if (!swapped || access.wearingWings() != wings) {
            recovering = true;
            return false;
        }
        return true;
    }

    private void recover(boolean inventorySafe) {
        if (!validate() || !inventorySafe) return;
        if (!access.wearingWings() && !swapTo(true)) return;
        Access completed = access;
        abandon();
        completed.restored();
    }
}
