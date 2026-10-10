package dev.guizhou.event.system;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Posts each child once and preserves its order. Null means the original bundle can be retained. */
public final class BundleFilter {
    private BundleFilter() {}

    public static <T> List<T> filter(Iterable<T> children, Predicate<T> cancelled) {
        List<T> kept = new ArrayList<>();
        boolean changed = false;
        for (T child : children) {
            if (cancelled.test(child)) changed = true;
            else kept.add(child);
        }
        return changed ? kept : null;
    }
}
