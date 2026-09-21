package dev.guizhou.util.render.font;

import dev.guizhou.GuizhouWare;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;
import java.util.function.Supplier;

/** Shared failure latch and native rendering scope for menus, HUD, ClickGUI and nametags. */
public final class FontFallback {
    private static final boolean FORCED = Boolean.getBoolean("guizhouware.vanillaFonts");
    private static final FontBackendGuard BACKEND = new FontBackendGuard(!FORCED, (phase, failure) ->
            GuizhouWare.LOGGER.warn("Custom font failed during {}; using Minecraft fonts for this session. Cause follows.", phase, failure));
    private static final ThreadLocal<Boolean> NATIVE_PASS = ThreadLocal.withInitial(() -> false);
    static {
        if (FORCED) GuizhouWare.LOGGER.info("Using Minecraft font compatibility mode (guizhouware.vanillaFonts=true)");
    }
    private FontFallback() {}
    public static boolean customEnabled() { return BACKEND.enabled(); }
    public static boolean nativePass() { return NATIVE_PASS.get(); }
    public static <T> T recover(String phase, Supplier<T> custom, Supplier<T> vanilla) {
        return BACKEND.use(phase, custom, vanilla);
    }
    public static void fail(String phase, Throwable failure) { BACKEND.fail(phase, failure); }
    private static <T> T nativeCall(Supplier<T> action) {
        boolean previous = NATIVE_PASS.get();
        NATIVE_PASS.set(true);
        try { return action.get(); }
        finally { NATIVE_PASS.set(previous); }
    }
    public static int width(String text) { return nativeCall(() -> Minecraft.getInstance().font.width(text)); }
    public static int width(FormattedCharSequence text) { return nativeCall(() -> Minecraft.getInstance().font.width(text)); }
    public static void draw(GuiGraphics graphics, String text, float x, float y, int color, boolean shadow) {
        nativeCall(() -> { graphics.drawString(Minecraft.getInstance().font, text, (int)x, (int)y, color, shadow); return null; });
    }
    public static void draw(GuiGraphics graphics, FormattedCharSequence text, float x, float y, int color, boolean shadow) {
        nativeCall(() -> { graphics.drawString(Minecraft.getInstance().font, text, (int)x, (int)y, color, shadow); return null; });
    }
    public static float scaledWidth(String text, float size) {
        return width(text) * size / Math.max(1, Minecraft.getInstance().font.lineHeight);
    }
    public static void drawScaled(GuiGraphics graphics, String text, float x, float y, float size, int color) {
        float scale = size / Math.max(1, Minecraft.getInstance().font.lineHeight);
        var pose = graphics.pose();
        pose.pushPose();
        try {
            pose.translate(x, y, 0);
            pose.scale(scale, scale, scale);
            draw(graphics, text, 0, 0, color, false);
        } finally { pose.popPose(); }
    }
}
