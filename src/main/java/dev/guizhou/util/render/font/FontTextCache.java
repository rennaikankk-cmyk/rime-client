package dev.guizhou.util.render.font;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded per-font text work. Layout belongs to a language; widths belong to a font/scale. */
final class FontTextCache<T> {
    private final int capacity;
    private final int maxLength;
    private final Map<String, Float> widths = new LinkedHashMap<>(64, .75f, true);
    private final Map<String, Layout<T>> layouts = new LinkedHashMap<>(64, .75f, true);
    private Object language;

    FontTextCache(int capacity, int maxLength) {
        if (capacity < 1 || maxLength < 1) throw new IllegalArgumentException("Invalid text cache limits");
        this.capacity = capacity;
        this.maxLength = maxLength;
    }

    synchronized Float width(String text) { return widths.get(text); }

    synchronized void width(String text, float width) {
        if (text.length() > maxLength || !Float.isFinite(width) || width < 0) return;
        put(widths, text, width);
    }

    synchronized T layout(String text, int color, Object currentLanguage) {
        useLanguage(currentLanguage);
        Layout<T> entry = layouts.get(text);
        return entry != null && entry.color == color ? entry.value : null;
    }

    synchronized void layout(String text, int color, Object currentLanguage, T value) {
        useLanguage(currentLanguage);
        if (text.length() <= maxLength && value != null) put(layouts, text, new Layout<>(color, value));
    }

    private void useLanguage(Object current) {
        if (language != current) {
            layouts.clear();
            language = current;
        }
    }

    private <V> void put(Map<String, V> map, String text, V value) {
        map.put(text, value);
        if (map.size() > capacity) {
            var oldest = map.keySet().iterator();
            oldest.next(); oldest.remove();
        }
    }

    synchronized void clear() {
        widths.clear(); layouts.clear(); language = null;
    }

    private record Layout<T>(int color, T value) {}
}
