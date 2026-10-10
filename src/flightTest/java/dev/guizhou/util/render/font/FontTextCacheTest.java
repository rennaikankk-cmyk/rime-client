package dev.guizhou.util.render.font;

public final class FontTextCacheTest {
    private static int checks;
    public static void main(String[] args) {
        FontTextCache<String> cache = new FontTextCache<>(2, 8);
        Object english = new Object(), arabic = new Object();
        cache.width("hud", 10); cache.width("fps", 20);
        check(cache.width("hud") == 10f, "Repeated widths");
        cache.width("new", 30);
        check(cache.width("fps") == null && cache.width("hud") == 10f, "Evict least recently read width");
        cache.layout("hud", 1, english, "LTR");
        check("LTR".equals(cache.layout("hud", 1, english)), "Reuse same language and color");
        check(cache.layout("hud", 2, english) == null, "Changed default color must reparse");
        check(cache.layout("hud", 1, arabic) == null, "Language reload must reorder");
        check(cache.width("hud") == 10f, "Language reload preserves raw font metrics");
        cache.layout("hud", 1, arabic, "RTL"); cache.layout("fps", 1, arabic, "F");
        cache.layout("hud", 1, arabic); cache.layout("new", 1, arabic, "N");
        check(cache.layout("fps", 1, arabic) == null, "Bound visual layouts independently");
        cache.width("oversized", 8); cache.layout("oversized", 1, arabic, "too long");
        check(cache.width("oversized") == null && cache.layout("oversized", 1, arabic) == null, "Do not retain arbitrary long text");
        cache.width("bad", Float.NaN); cache.width("negative", -1);
        check(cache.width("bad") == null && cache.width("negative") == null, "Do not retain invalid metrics");
        cache.width("", 0); check(cache.width("") == 0f, "Zero width is a cache hit");
        cache.clear();
        check(cache.width("") == null && cache.layout("hud", 1, arabic) == null, "Font/scale reset clears both caches");
        for (int i = 0; i < 10000; i++) { cache.width("v" + i, i); cache.layout("v" + i, i, english, "x"); }
        check(cache.width("v9997") == null && cache.width("v9999") == 9999f, "Changing HUD values stay bounded");
        check(cache.layout("v9997", 9997, english) == null && cache.layout("v9999", 9999, english) != null, "Changing visual values stay bounded");
        System.out.println("Font text cache passed: " + checks + " checks");
    }
    private static void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
}
