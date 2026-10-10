package probe;

import com.google.gson.GsonBuilder;
import dev.guizhou.util.render.font.FontRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.awt.Font;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.*;

/** Separate development fixture: no world, game-server connection, credentials or production hooks. */
public final class FontProbe implements ClientModInitializer {
    static final String[] TEXT = {"Rime / FPS 144", "自动水晶 · 空战 / GzBoost", "§c红色§r / reset §aGreen",
        "مرحبا بالعالم / שלום", "Emoji \uD83D\uDE00 / A\uD834\uDD1EZ", "trailing §", "§zunknown §lstyle", "", "ＡＢＣ：１２３"};
    FontRenderer font;
    int tick, phase, wait;
    volatile boolean captured;
    volatile float sink;
    final List<Object> runs = new ArrayList<>();
    final int[] scales = {1, 2, 3, 2};
    @Override public void onInitializeClient() { ClientTickEvents.END_CLIENT_TICK.register(this::tick); }
    void tick(Minecraft mc) {
        if (phase == 99) return;
        try {
            if (++tick > 1000) throw new AssertionError("timeout");
            if (mc.getOverlay() != null || tick < 100) return;
            if (font == null) {
                mc.options.pauseOnLostFocus = false;
                mc.options.enableVsync().set(false);
                mc.options.framerateLimit().set(260);
                mc.options.guiScale().set(scales[0]); mc.resizeDisplay();
                font = new FontRenderer(new Font[]{new Font(Font.SANS_SERIF, Font.PLAIN, 12)});
                mc.setScreen(new Screen(Component.literal("font fixture")) {
                    @Override public void render(GuiGraphics g, int x, int y, float delta) {
                        g.fill(0, 0, width, height, 0xff222222);
                        for (int i = 0; i < TEXT.length; i++) {
                            FontProbe.this.font.drawString(g, TEXT[i], 12, 12 + i * 17, 0xffe8dfce, false);
                            FontProbe.this.font.drawString(g, TEXT[i], 220, 12 + i * 17, 0x8080d0ff, true);
                        }
                    }
                });
                return;
            }
            if (++wait < 30) return;
            if ((phase & 1) == 0) {
                runs.add(measure(mc));
                captured = false;
                Screenshot.grab(mc.gameDirectory, "scale-" + (phase / 2) + ".png", mc.getMainRenderTarget(), 1, message -> captured = true);
                phase++; wait = 0; return;
            }
            if (!captured) return;
            phase++; wait = 0;
            if (phase / 2 == scales.length) {
                Files.writeString(mc.gameDirectory.toPath().resolve("results.json"), new GsonBuilder().setPrettyPrinting().create().toJson(runs));
                font.close(); phase = 99; mc.stop(); return;
            }
            mc.options.guiScale().set(scales[phase / 2]); mc.resizeDisplay();
        } catch (Throwable ex) {
            ex.printStackTrace();
            try { Files.writeString(mc.gameDirectory.toPath().resolve("failure.txt"), ex.toString()); } catch (Exception ignored) {}
            phase = 99; mc.stop();
        }
    }
    Object measure(Minecraft mc) throws Exception {
        Method parse = FontRenderer.class.getDeclaredMethod("parse", String.class, int.class); parse.setAccessible(true);
        Method cached = null;
        try { cached = FontRenderer.class.getDeclaredMethod("visualText", String.class, int.class); cached.setAccessible(true); }
        catch (NoSuchMethodException baseline) {}
        List<Float> widths = new ArrayList<>(); List<String> layout = new ArrayList<>();
        for (String text : TEXT) {
            widths.add(font.getTextWidth(text));
            var expected = ((Component) parse.invoke(null, text, 0xffe8dfce)).getVisualOrderText();
            var actual = visual(parse, cached, text);
            String e = flatten(expected), a = flatten(actual);
            if (!e.equals(a)) throw new AssertionError("Visual ordering/style changed: " + text);
            layout.add(a);
        }
        List<Double> widthMs = new ArrayList<>(), layoutMs = new ArrayList<>();
        for (int round = 0; round < 6; round++) {
            float sum = 0; long start = System.nanoTime();
            for (int i = 0; i < 100000; i++) sum += font.getTextWidth(TEXT[i % TEXT.length]);
            if (round > 0) widthMs.add((System.nanoTime() - start) / 1e6); sink = sum;
            start = System.nanoTime();
            for (int i = 0; i < 30000; i++) { var s = visual(parse, cached, TEXT[i % TEXT.length]); if (s == null) throw new AssertionError(); }
            if (round > 0) layoutMs.add((System.nanoTime() - start) / 1e6);
        }
        var pages = FontRenderer.class.getDeclaredField("glyphPages"); pages.setAccessible(true);
        return Map.of("requestedScale", scales[phase / 2], "actualScale", mc.getWindow().getGuiScale(), "widths", widths,
            "visualOrder", layout, "width100kMs", widthMs, "layout30kMs", layoutMs, "retainedPageEntries", ((Map<?, ?>) pages.get(font)).size());
    }
    FormattedCharSequence visual(Method parse, Method cached, String text) throws Exception {
        return cached == null ? ((Component) parse.invoke(null, text, 0xffe8dfce)).getVisualOrderText()
            : (FormattedCharSequence) cached.invoke(font, text, 0xffe8dfce);
    }
    String flatten(FormattedCharSequence sequence) {
        StringBuilder result = new StringBuilder();
        sequence.accept((i, style, cp) -> { result.append(i).append(':').append(cp).append(':').append(style).append(';'); return true; });
        return result.toString();
    }
}
