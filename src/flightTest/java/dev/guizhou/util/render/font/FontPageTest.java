package dev.guizhou.util.render.font;

import java.awt.Font;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class FontPageTest {
    private static final Font[] FONTS = {new Font(Font.SANS_SERIF, Font.PLAIN, 12)};
    private static int checks;

    private FontPageTest() {
    }

    public static void main(String[] args) {
        checkLastPageReusesGlyphMap();
        checkBmpCoverage(256);
        checkBmpCoverage(257);
        checkBmpCoverage(Integer.MAX_VALUE);
        checkInvalidBounds();
        System.out.println("Font page checks passed: " + checks + " checks without a graphics context.");
    }

    private static void checkLastPageReusesGlyphMap() {
        GlyphMap.PageCache cache = new GlyphMap.PageCache(256);
        GlyphMap lastPage = cache.getOrCreate(FONTS, '\uff01', 4);
        for (int i = 0; i < 10000; i++) {
            check(cache.getOrCreate(FONTS, '\uff01', 4) == lastPage, "Fullwidth punctuation must reuse its page");
            check(cache.getOrCreate(FONTS, '\ufffd', 4) == lastPage, "Replacement character must reuse the last page");
            check(cache.getOrCreate(FONTS, '\uffff', 4) == lastPage, "The final BMP code unit must reuse the last page");
        }
        check(lastPage.contains('\uff00') && lastPage.contains('\uffff'), "Last page must include both boundary characters");
        check(!lastPage.contains('\ufeff'), "Last page must not include the previous page");
        cache.clear();
        GlyphMap replacement = cache.getOrCreate(FONTS, '\uff01', 4);
        check(replacement != lastPage && replacement.contains('\uffff'), "Cache clear must allow a fresh complete page");
        cache.clear();
    }

    private static void checkBmpCoverage(int pageSize) {
        GlyphMap.PageCache cache = new GlyphMap.PageCache(pageSize);
        Set<GlyphMap> allocated = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int codeUnit = 0; codeUnit <= Character.MAX_VALUE; codeUnit++) {
            char character = (char) codeUnit;
            GlyphMap page = cache.getOrCreate(FONTS, character, 4);
            check(page.contains(character), "Missing BMP code unit " + codeUnit);
            check(cache.getOrCreate(FONTS, character, 4) == page, "Repeated lookup created another page");
            allocated.add(page);
        }
        int expected = (int) Math.ceil((Character.MAX_VALUE + 1.0) / pageSize);
        check(allocated.size() == expected, "Cache must allocate exactly one page per covered range");
        cache.clear();
    }

    private static void checkInvalidBounds() {
        expectInvalid(() -> new GlyphMap(FONTS, 0xff00, 0, 4));
        expectInvalid(() -> new GlyphMap(FONTS, -1, 256, 4));
        expectInvalid(() -> new GlyphMap(FONTS, 0, 0x10001, 4));
        expectInvalid(() -> new GlyphMap.PageCache(0));
    }

    private static void expectInvalid(Runnable construction) {
        try {
            construction.run();
            throw new AssertionError("Invalid glyph page bounds were accepted");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
