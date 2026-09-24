package dev.guizhou.util;

import dev.cattyn.catformat.CatFormat;
import dev.cattyn.catformat.fabric.FabricCatFormat;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.commands.ui.ChatPalette;
import dev.guizhou.util.render.ShadcnDraw;
import net.minecraft.network.chat.MutableComponent;

public final class TextUtil {
    private static CatFormat<MutableComponent> formatter;

    private TextUtil() {
        throw new AssertionError("Can't create an instance of utility class");
    }

    public static void init() {
        if (formatter != null) throw new IllegalStateException("Formatter is already initialized");
        // CatFormat resolves names first-match, so vanilla colours are left out and re-registered on the palette.
        formatter = new FabricCatFormat(false);
        initColors();
    }

    public static MutableComponent text(String content, Object... obj) {
        if (obj == null || obj.length == 0) {
            return formatter.format(content);
        }
        return formatter.format(content, obj);
    }

    public static CatFormat<MutableComponent> getFormatter() {
        return formatter;
    }

    private static void initColors() {
        ChatPalette.LEGACY.forEach((name, rgb) -> formatter.add(name, rgb.intValue()));
        formatter.add("global", () -> GuizhouWare.colorManager.getAsInt("ui"));
        formatter.add("accent", () -> ShadcnDraw.accent() & 0xFFFFFF);
    }
}
