package dev.guizhou.mixin.render.gui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.guizhou.features.gui.MenuStorm;
import dev.guizhou.features.modules.client.ClickGuiModule;
import dev.guizhou.util.BuildConfig;
import dev.guizhou.util.render.font.HudFonts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.guizhou.util.render.ShadcnDraw.*;

/**
 * Minimal title screen: an optional custom background, one small centered card
 * with the three primary actions, date and version in the corners. Reuses the
 * vanilla widget instances, so actions, enabled gates and keyboard/narration stay.
 */
@Mixin(TitleScreen.class)
public abstract class MixinTitleScreen extends Screen {
    @Shadow private Component getMultiplayerDisabledReason() { throw new AssertionError(); }

    @Unique private static final Identifier GUIZHOUWARE$BACKGROUND = Identifier.fromNamespaceAndPath("guizhouware", "dynamic/menu_background");
    @Unique private static final DateTimeFormatter GUIZHOUWARE$CLOCK = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.ROOT);
    @Unique private static long guizhouware$backgroundStamp = Long.MIN_VALUE;
    @Unique private static int guizhouware$backgroundW, guizhouware$backgroundH;

    @Unique private float guizhouware$cardX, guizhouware$cardY, guizhouware$cardW, guizhouware$cardH, guizhouware$scale = 1;
    @Unique private final List<AbstractWidget> guizhouware$corner = new ArrayList<>();

    protected MixinTitleScreen(Component title) { super(title); }

    @Unique
    private static String guizhouware$key(AbstractWidget b) {
        return b.getMessage().getContents() instanceof TranslatableContents tc ? tc.getKey() : "";
    }

    @Unique
    private static Icon guizhouware$icon(String key) {
        key = key.toLowerCase(Locale.ROOT);
        if (key.equals("menu.online")) return Icon.USERS;
        if (key.contains("language")) return Icon.WORLD;
        if (key.contains("accessibility")) return Icon.INFO;
        return Icon.GRID;
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void guizhouware$layout(CallbackInfo ci) {
        guizhouware$loadBackground();
        guizhouware$corner.clear();
        var gui = ClickGuiModule.getInstance();
        float requested = gui == null ? 1 : gui.menuScale.getValue().floatValue() / 100f;
        requested = Float.isFinite(requested) ? Math.clamp(requested, .6f, 1.5f) : 1;
        // The card stays a small centred element, roughly 30% of the screen width.
        float s = Math.clamp(Math.min(requested * width / 780f, height / 190f), .5f, 1.3f);
        guizhouware$scale = s;
        int bw = Math.round(72 * s), bh = Math.round(16 * s), gap = Math.round(5 * s), pad = Math.round(9 * s);
        guizhouware$cardW = bw * 3 + gap * 2 + pad * 2;
        guizhouware$cardH = Math.round(66 * s);
        guizhouware$cardX = Math.round((width - guizhouware$cardW) / 2f);
        guizhouware$cardY = Math.round((height - guizhouware$cardH) / 2f);
        int rowY = Math.round(guizhouware$cardY + 26 * s), rowX = Math.round(guizhouware$cardX + pad);
        int cornerX = width - 6;
        for (var child : children()) {
            if (!(child instanceof AbstractWidget b)) continue;
            if (b instanceof PlainTextButton) {
                // Credits stay clickable (and keyboard reachable) in the quiet top-left corner.
                b.setPosition(6, 6);
                continue;
            }
            String key = guizhouware$key(b);
            int order = switch (key) {
                case "menu.singleplayer" -> 0;
                case "menu.multiplayer" -> 1;
                case "menu.options" -> 2;
                default -> -1;
            };
            if (order >= 0) {
                b.setRectangle(bw, bh, rowX + order * (bw + gap), rowY);
            } else if (key.equals("menu.quit")) {
                b.setRectangle(Math.round(58 * s), bh, width - Math.round(58 * s) - 6, height - bh - 6);
            } else {
                // Realms, language, accessibility and mod-menu entries: small corner icons.
                int size = Math.round(14 * s);
                cornerX -= size;
                b.setRectangle(size, size, cornerX, 6);
                cornerX -= 4;
                guizhouware$corner.add(b);
            }
        }
    }

    @Unique
    private void guizhouware$loadBackground() {
        Minecraft mc = Minecraft.getInstance();
        Path file = mc.gameDirectory.toPath().resolve("guizhouware").resolve("background.png");
        long stamp;
        try {
            stamp = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
        } catch (Exception e) {
            stamp = 0;
        }
        if (stamp == guizhouware$backgroundStamp) return;
        guizhouware$backgroundStamp = stamp;
        mc.getTextureManager().release(GUIZHOUWARE$BACKGROUND);
        guizhouware$backgroundW = guizhouware$backgroundH = 0;
        if (stamp == 0) return;
        try (InputStream in = Files.newInputStream(file)) {
            NativeImage image = NativeImage.read(in);
            guizhouware$backgroundW = image.getWidth();
            guizhouware$backgroundH = image.getHeight();
            mc.getTextureManager().register(GUIZHOUWARE$BACKGROUND, new DynamicTexture(() -> "guizhouware menu background", image));
        } catch (Exception e) {
            guizhouware$backgroundW = guizhouware$backgroundH = 0;
        }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void guizhouware$render(GuiGraphics g, int mx, int my, float delta, CallbackInfo ci) {
        float s = guizhouware$scale;
        g.fill(0, 0, width, height, 0xFF08080A);
        if (guizhouware$backgroundW > 0) {
            guizhouware$drawCover(g);
            g.fill(0, 0, width, height, 0x66000000);
        } else {
            MenuStorm.draw(g, width, height, delta);
        }
        var gui = ClickGuiModule.getInstance();
        String watermark = gui == null ? "" : gui.menuWatermark.getValue().trim();
        if (!watermark.isEmpty()) {
            float size = Math.min(height * .2f, width * .9f / Math.max(1, watermark.length()) * 1.6f);
            textCentered(g, watermark.toUpperCase(Locale.ROOT), width / 2f, height / 2f - size * .55f, size, HudFonts.BOLD, 0x16FFFFFF);
        }
        textCentered(g, LocalDateTime.now().format(GUIZHOUWARE$CLOCK), width / 2f, 6, 6.5f, HudFonts.SEMI, 0xC8FFFFFF);

        float cx = guizhouware$cardX, cy = guizhouware$cardY, cw = guizhouware$cardW, ch = guizhouware$cardH;
        round(g, cx, cy, cw, ch, 3 * s, 0xB00E0E11);
        outline(g, cx, cy, cw, ch, 3 * s, 1, 0x26FFFFFF);
        float brand = 13 * s, gw = width("Guizhou", brand, HudFonts.BOLD), ww = width("Ware", brand, HudFonts.BOLD);
        float bx = cx + (cw - gw - ww) / 2, by = cy + 8 * s;
        text(g, "Guizhou", bx, by, brand, HudFonts.BOLD, 0xFFFFFFFF);
        text(g, "Ware", bx + gw, by, brand, HudFonts.BOLD, 0xFFE5484D);
        String notice = gui == null ? "" : gui.menuNotice.getValue().trim();
        if (notice.isEmpty()) notice = "欢迎回来，" + Minecraft.getInstance().getUser().getName();
        textCentered(g, fit(notice, cw - 16 * s, 7.5f * s), cx + cw / 2, cy + ch - 15 * s, 7.5f * s, HudFonts.REGULAR, 0xB4FFFFFF);

        for (var child : children()) {
            if (!(child instanceof AbstractWidget b) || !b.visible) continue;
            if (b instanceof PlainTextButton) {
                String label = b.getMessage().getString();
                text(g, fit(label, width * .35f, 5.5f), b.getX(), b.getY() + 2, 5.5f, HudFonts.REGULAR,
                        b.isMouseOver(mx, my) ? 0x90FFFFFF : 0x40FFFFFF);
                continue;
            }
            guizhouware$button(g, b, mx, my, s);
        }
        text(g, "Rime " + BuildConfig.VERSION + " · mc1.21.11", 6, height - 10, 6f, 0x80FFFFFF);
        ci.cancel();
    }

    @Unique
    private void guizhouware$button(GuiGraphics g, AbstractWidget b, int mx, int my, float s) {
        boolean hover = b.isMouseOver(mx, my), focus = b.isFocused();
        float x = b.getX(), y = b.getY(), w = b.getWidth(), h = b.getHeight();
        boolean corner = guizhouware$corner.contains(b);
        int fill = !b.active ? 0x20000000 : hover ? 0x2CFFFFFF : 0x38000000;
        int border = !b.active ? 0x30FFFFFF : hover || focus ? 0xE6FFFFFF : corner ? 0x40FFFFFF : 0x8CFFFFFF;
        g.fill(Math.round(x), Math.round(y), Math.round(x + w), Math.round(y + h), fill);
        outline(g, x, y, w, h, 0, 1, border);
        int fg = !b.active ? 0x60FFFFFF : hover || focus ? 0xFFFFFFFF : 0xDCFFFFFF;
        if (corner) {
            float is = Math.min(w, h) * .62f;
            icon(g, guizhouware$icon(guizhouware$key(b)), x + (w - is) / 2, y + (h - is) / 2, is, fg);
            if (hover || focus) g.setTooltipForNextFrame(b.getMessage(), mx, my);
        } else {
            float size = 7.5f * s;
            textCentered(g, fit(b.getMessage().getString(), w - 6, size), x + w / 2, y + (h - size) / 2 + .5f, size, HudFonts.REGULAR, fg);
        }
        if (!b.active && (hover || focus)) {
            Component reason = getMultiplayerDisabledReason();
            if (reason != null) g.setTooltipForNextFrame(reason, mx, my);
        }
    }

    /** Scales the custom background to cover the screen and crops the overflow evenly. */
    @Unique
    private void guizhouware$drawCover(GuiGraphics g) {
        int tw = guizhouware$backgroundW, th = guizhouware$backgroundH;
        float cover = Math.max(width / (float) tw, height / (float) th);
        int regionW = Math.round(width / cover), regionH = Math.round(height / cover);
        int u = (tw - regionW) / 2, v = (th - regionH) / 2;
        var pose = g.pose();
        pose.pushMatrix();
        pose.scale(cover, cover);
        g.blit(RenderPipelines.GUI_TEXTURED, GUIZHOUWARE$BACKGROUND, 0, 0, u, v, regionW, regionH, tw, th);
        pose.popMatrix();
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void guizhouware$click(MouseButtonEvent event, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        // Only visible native controls receive input; the old Realms overlay used old coordinates.
        cir.setReturnValue(super.mouseClicked(event, doubled));
    }
}
