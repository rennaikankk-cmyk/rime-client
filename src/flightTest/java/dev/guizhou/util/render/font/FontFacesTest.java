package dev.guizhou.util.render.font;

import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FontFacesTest {
    public static void main(String[] args) throws Exception {
        var candidates = FontFaces.installedFamilies(new String[]{"Noto Serif SC", "Anthropic Serif Web", "Arial"}, Font.PLAIN);
        if (candidates.size() != 2 || !candidates.getFirst().getName().equals("Anthropic Serif Web"))
            throw new AssertionError("Actual Anthropic family must precede CJK fallback");
        if (!FontFaces.installedFamilies(new String[]{"Arial"}, Font.PLAIN).isEmpty())
            throw new AssertionError("Absent families must not silently create Dialog fonts");
        Path temp = Files.createTempDirectory("rime-font-test");
        Path directory = temp.resolve("guizhouware/fonts");
        Files.createDirectories(directory);
        Path file = directory.resolve("anthropic-serif-regular.ttf");
        try {
            // Fixture deliberately uses a different face to prove the file is read, not its filename.
            try (var in = FontFacesTest.class.getResourceAsStream("/assets/guizhouware/font/ib_latin.ttf")) {
                if (in == null) throw new AssertionError("Missing font fixture");
                Files.copy(in, file);
            }
            Font expected;
            try (var in = Files.newInputStream(file)) { expected = Font.createFont(Font.TRUETYPE_FONT, in); }
            Font[] chain = FontFaces.resolve(temp, 400);
            if (!chain[0].getFontName().equals(expected.getFontName())) throw new AssertionError("Local font not selected");
            chain[0] = null;
            if (FontFaces.resolve(temp, 400)[0] == null) throw new AssertionError("Mutable cached chain leaked");
            if (FontFaces.resolve(temp, 700).length < 2) throw new AssertionError("Missing fallback chain");
            Files.writeString(file, "invalid font fixture");
            if (FontFaces.resolve(temp, 600).length == 0) throw new AssertionError("Damaged optional font prevented fallback");
        } finally {
            Files.deleteIfExists(file); Files.deleteIfExists(directory);
            Files.deleteIfExists(directory.getParent()); Files.deleteIfExists(temp);
        }
        System.out.println("FontFacesTest passed: real family priority, absent font, external font, cache isolation, weights and damaged-file fallback");
    }
}
