package dev.guizhou.util.render.font;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One face chain for menus, HUD, nametags and world overlays. Fonts stay local. */
public final class FontFaces {
    private static final String[] FAMILIES = {"Anthropic Serif", "Anthropic Serif Web", "Source Serif 4",
            "Noto Serif SC", "Palatino Linotype", "Songti SC", "宋体"};
    private static final Map<String, Font[]> CACHE = new HashMap<>();
    private FontFaces() {}

    public static synchronized Font[] resolve(Path gameDirectory, int weight) {
        Path directory = gameDirectory.toAbsolutePath().normalize().resolve("guizhouware/fonts");
        String key = directory + ":" + weight;
        Font[] cached = CACHE.get(key);
        if (cached != null) return cached.clone();
        int style = weight >= 600 ? Font.BOLD : Font.PLAIN;
        List<Font> chain = new ArrayList<>();
        String suffix = weight >= 700 ? "bold" : weight >= 600 ? "semibold" : "regular";
        Font local = localFace(directory, "anthropic-serif-" + suffix);
        if (local == null) {
            local = localFace(directory, "anthropic-serif-regular");
            if (local != null) local = local.deriveFont(style, 1f);
        }
        if (local != null) chain.add(local);
        chain.addAll(installedFamilies(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(), style));
        Font chinese = localFace(directory, "ib_zh");
        if (chinese == null) {
            try (InputStream in = FontFaces.class.getResourceAsStream("/assets/guizhouware/font/ib_zh.ttf")) {
                if (in != null) chinese = Font.createFont(Font.TRUETYPE_FONT, in);
            } catch (Exception ignored) { /* Optional external CJK face. */ }
        }
        if (chinese != null) chain.add(chinese.deriveFont(style, 1f));
        chain.add(new Font(Font.SERIF, style, 1));
        Font[] fonts = chain.toArray(Font[]::new);
        CACHE.put(key, fonts);
        System.getLogger(FontFaces.class.getName()).log(System.Logger.Level.INFO,
                "Rime font weight " + weight + ": " + fonts[0].getFontName(java.util.Locale.ROOT));
        return fonts.clone();
    }

    static List<Font> installedFamilies(String[] installed, int style) {
        List<Font> chain = new ArrayList<>();
        for (String family : FAMILIES) for (String name : installed) {
            if (name.equalsIgnoreCase(family)) { chain.add(new Font(name, style, 1)); break; }
        }
        return chain;
    }

    private static Font localFace(Path directory, String basename) {
        for (String extension : new String[]{".ttf", ".otf"}) {
            Path file = directory.resolve(basename + extension);
            if (!Files.isRegularFile(file)) continue;
            try (InputStream in = Files.newInputStream(file)) { return Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(1f); }
            catch (Exception ignored) { /* A damaged optional font must not disable all custom text. */ }
        }
        return null;
    }
}
