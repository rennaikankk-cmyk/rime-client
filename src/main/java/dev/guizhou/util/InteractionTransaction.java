package dev.guizhou.util;

import java.util.function.BooleanSupplier;

/** A locally submitted prelude must be restored even when the action fails. No server ACK implied. */
public final class InteractionTransaction {
    private InteractionTransaction() {}

    public static boolean run(BooleanSupplier enter, BooleanSupplier action, Runnable restore) {
        if (!enter.getAsBoolean()) return false;
        try {
            return action.getAsBoolean();
        } finally {
            restore.run();
        }
    }
}
