package dev.guizhou.util.elytra;

/** Open owned wings on the descending half of a held jump, before a short hop lands. */
public final class ArmorTakeoff {
    private ArmorTakeoff() {}
    public static boolean ready(boolean requested, boolean jumpHeld, boolean grounded, double verticalSpeed) {
        return requested && jumpHeld && !grounded && Double.isFinite(verticalSpeed) && verticalSpeed < 0;
    }
}
