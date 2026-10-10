package dev.guizhou.event.system;

import java.util.ArrayList;
import java.util.List;

/** A cancelled child must never be replayed, and listeners must observe each child exactly once. */
public final class BundleFilterTest {
    public static void main(String[] args) {
        List<Integer> seen = new ArrayList<>();
        List<Integer> children = List.of(1, 2, 3, 4);
        check(BundleFilter.filter(children, p -> { seen.add(p); return false; }) == null,
                "Unchanged bundle retains its identity");
        check(seen.equals(children), "Unchanged children posted once in wire order");
        seen.clear();
        List<Integer> kept = BundleFilter.filter(children, p -> { seen.add(p); return p == 1 || p == 3; });
        check(kept.equals(List.of(2, 4)), "First and middle cancellation are enforced in the delivered bundle");
        check(seen.equals(children), "Filtering neither skips events nor posts them twice");
        check(BundleFilter.filter(children, p -> true).isEmpty(), "All children cancelled yields an empty bundle");
        check(BundleFilter.filter(List.of(), p -> true) == null, "Empty input remains unchanged");
        System.out.println("BundleFilterTest passed: 6 cancellation/order checks");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
