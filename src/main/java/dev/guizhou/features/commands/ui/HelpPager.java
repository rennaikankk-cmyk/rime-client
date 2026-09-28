package dev.guizhou.features.commands.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** Grouping and pagination for the help listing. */
public final class HelpPager {
    public record Page<T>(List<T> items, int page, int pages, int total) {
        public boolean hasPrevious() {
            return page > 1;
        }

        public boolean hasNext() {
            return page < pages;
        }
    }

    private HelpPager() {}

    /** Out-of-range requests clamp to the first/last page instead of failing. */
    public static <T> Page<T> page(List<T> all, int requested, int perPage) {
        int size = Math.max(1, perPage);
        int total = all.size();
        int pages = Math.max(1, (total + size - 1) / size);
        int page = Math.max(1, Math.min(pages, requested));
        int from = Math.min(total, (page - 1) * size);
        int to = Math.min(total, from + size);
        return new Page<>(List.copyOf(all.subList(from, to)), page, pages, total);
    }

    /** Stable order: category declaration order, then name. */
    public static <T> List<T> grouped(List<T> items, Function<T, CommandCategory> category, Function<T, String> name) {
        List<T> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.<T, Integer>comparing(item -> category.apply(item).ordinal())
                .thenComparing(name, String.CASE_INSENSITIVE_ORDER));
        return sorted;
    }
}
