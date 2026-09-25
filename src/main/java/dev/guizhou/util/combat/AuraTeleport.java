package dev.guizhou.util.combat;

import java.util.*;
import java.util.function.*;

/** One bounded synchronous round trip. Even partial entry or exceptions attempt restoration. */
public final class AuraTeleport {
    private AuraTeleport() {}
    public record Result(boolean attacked, boolean restored) {}

    public static <T> Result roundTrip(T origin, List<T> path, Predicate<T> enter,
                                       BooleanSupplier attack, Predicate<T> restore) {
        boolean attacked = false, restored = false;
        int entered = 0;
        try {
            boolean reached = !path.isEmpty();
            for (T point : path) {
                if (!enter.test(point)) { reached = false; break; }
                entered++;
            }
            if (reached) attacked = attack.getAsBoolean();
        } finally {
            // Returning to the known origin remains possible if an intermediate restore is refused.
            try {
                for (int i = entered - 2; i >= 0; i--) {
                    if (!restore.test(path.get(i))) break;
                }
            } finally {
                restored = restore.test(origin);
            }
        }
        return new Result(attacked, restored);
    }
}
