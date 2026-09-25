package dev.guizhou.mixin.render.gui;

import dev.guizhou.features.gui.MenuBackground;
import dev.guizhou.features.gui.MenuBackgroundScreen;
import dev.guizhou.features.gui.MenuLoop;
import dev.guizhou.features.gui.MenuStorm;
import dev.guizhou.features.modules.client.ClickGuiModule;
import dev.guizhou.util.BuildConfig;
import dev.guizhou.util.render.font.HudFonts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.guizhou.util.render.ShadcnDraw.*;

/**
 * Minimal title screen: an optional custom background and one centered card.
 * Every labelled button, including ones added by other mods, goes in a wrapping
 * grid. Language and accessibility stay as corner icons. Reuses the vanilla
 * widget instances, so actions, enabled gates and keyboard/narration stay.
 */
@Mixin(TitleScreen.class)
public abstract class MixinTitleScreen extends Screen {
    @Shadow private Component getMultiplayerDisabledReason() { throw new AssertionError(); }

    @Unique private static final DateTimeFormatter GUIZHOUWARE$CLOCK = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.ROOT);

    @Unique private float guizhouware$cardX, guizhouware$cardY, guizhouware$cardW, guizhouware$cardH, guizhouware$scale = 1;
    @Unique private Button guizhouware$backgroundButton;
    /** Chosen once per title-screen open: coffee loop or the storm clip. */
    @Unique private Boolean guizhouware$coffee;
    @Unique private final List<AbstractWidget> guizhouware$corner = new ArrayList<>();
    @Unique private final List<AbstractWidget> guizhouware$seen = new ArrayList<>();

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

    @Unique
    private static int guizhouware$rank(String key) {
        return switch (key) {
            case "menu.singleplayer" -> 0;
            case "menu.multiplayer" -> 1;
            case "menu.online" -> 2;
            case "menu.options" -> 3;
            case "menu.quit" -> 1_000;
            default -> 100;
        };
    }

    /** Language and accessibility stay icons. Labelled buttons from other mods stay in the card. */
    @Unique
    private static boolean guizhouware$iconButton(AbstractWidget b, String key) {
        String type = b.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (type.contains("sprite") || type.contains("image")) return true;
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("language") || k.contains("accessibility");
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void guizhouware$onInit(CallbackInfo ci) {
        if (guizhouware$coffee == null) guizhouware$coffee = java.util.concurrent.ThreadLocalRandom.current().nextBoolean();
        guizhouware$backgroundButton = addRenderableWidget(Button.builder(Component.literal("背景"), b -> {
            if (minecraft != null) minecraft.setScreen(new MenuBackgroundScreen((Screen) (Object) this));
        }).bounds(0, 0, 32, 16).build());
        guizhouware$layout();
    }

    /**
     * Other mods append title-screen buttons after this mixin, often with literal
     * labels. Rebuilding the grid every frame keeps those buttons inside the card
     * instead of on the vanilla column underneath it.
     */
    @Unique
    private void guizhouware$layout() {
        guizhouware$corner.clear();
        List<AbstractWidget> live = new ArrayList<>();
        List<AbstractWidget> primary = new ArrayList<>();
        for (var child : children()) {
            if (child instanceof AbstractWidget b) live.add(b);
        }
        guizhouware$seen.retainAll(live);
        for (AbstractWidget b : live) {
            if (!guizhouware$seen.contains(b)) guizhouware$seen.add(b);
        }
        for (AbstractWidget b : live) {
            if (!b.visible) continue;
            if (b instanceof PlainTextButton) {
                b.setPosition(6, 6);
                continue;
            }
            String key = guizhouware$key(b);
            if (b == guizhouware$backgroundButton || guizhouware$iconButton(b, key)) {
                guizhouware$corner.add(b);
            } else {
                primary.add(b);
            }
        }
        primary.sort((a, c) -> {
            int rank = Integer.compare(guizhouware$rank(guizhouware$key(a)), guizhouware$rank(guizhouware$key(c)));
            return rank != 0 ? rank : Integer.compare(guizhouware$seen.indexOf(a), guizhouware$seen.indexOf(c));
        });

        var gui = ClickGuiModule.getInstance();
        float requested = gui == null ? 1 : gui.menuScale.getValue().floatValue() / 100f;
        requested = Float.isFinite(requested) ? Math.clamp(requested, .6f, 1.5f) : 1;
        int count = Math.max(1, primary.size());
        int cols = count <= 3 ? count : count <= 6 ? 2 : 3;
        int rows = (count + cols - 1) / cols;
        float s = Math.clamp(Math.min(requested * width / 780f, height / (120f + rows * 24f)), .5f, 1.3f);
        guizhouware$scale = s;
        int gap = Math.max(4, Math.round(5 * s));
        int pad = Math.max(8, Math.round(9 * s));
        int header = Math.max(24, Math.round(28 * s));
        int footer = Math.max(16, Math.round(18 * s));
        int maxW = Math.max(96, width - 24);
        int inner = Math.max(48, maxW - pad * 2 - gap * Math.max(0, cols - 1));
        int bw = Math.min(168, inner / cols);
        int bh = Math.max(14, Math.round(18 * s));
        int maxH = Math.max(bh + header + footer, height - 36);
        int rowGap = Math.max(0, rows - 1) * gap;
        if (header + rows * bh + rowGap + footer > maxH) {
            bh = Math.max(14, (maxH - header - footer - rowGap) / rows);
        }
        guizhouware$cardW = Math.min(maxW, cols * bw + gap * Math.max(0, cols - 1) + pad * 2);
        guizhouware$cardH = header + rows * bh + rowGap + footer;
        guizhouware$cardX = Math.round((width - guizhouware$cardW) / 2f);
        guizhouware$cardY = Math.round(Math.clamp((height - guizhouware$cardH) / 2f, 22, Math.max(22, height - guizhouware$cardH - 16)));
        int rowX = Math.round(guizhouware$cardX + pad);
        int rowY = Math.round(guizhouware$cardY + header);
        for (int i = 0; i < primary.size(); i++) {
            int col = i % cols, row = i / cols;
            primary.get(i).setRectangle(bw, bh, rowX + col * (bw + gap), rowY + row * (bh + gap));
        }
        int cornerX = width - 6;
        int size = Math.max(14, Math.round(16 * s));
        for (int i = guizhouware$corner.size() - 1; i >= 0; i--) {
            AbstractWidget corner = guizhouware$corner.get(i);
            int w = corner == guizhouware$backgroundButton ? Math.max(size, 32) : size;
            cornerX -= w;
            corner.setRectangle(w, size, cornerX, 6);
            cornerX -= 4;
        }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void guizhouware$render(GuiGraphics g, int mx, int my, float delta, CallbackInfo ci) {
        guizhouware$layout();
        float s = guizhouware$scale;
        g.fill(0, 0, width, height, 0xFF08080A);
        if (MenuBackground.draw(g, width, height)) {
            g.fill(0, 0, width, height, 0x66000000);
        } else {
            if (!Boolean.TRUE.equals(guizhouware$coffee) || !MenuLoop.draw(g, width, height)) {
                MenuStorm.draw(g, width, height, delta);
            }
        }
        String banner = MenuBackground.banner();
        if (banner != null) textCentered(g, banner, width / 2f, 16, 7f, HudFonts.REGULAR, 0xFFF2C14C);
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
        float brand = 13 * s, rw = width("Rime", brand, HudFonts.BOLD);
        text(g, "Rime", cx + (cw - rw) / 2, cy + 8 * s, brand, HudFonts.BOLD, 0xFFFFFFFF);
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
        text(g, "Rime " + BuildConfig.VERSION + " · mc1.21.1", 6, height - 10, 6f, 0x80FFFFFF);
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
            if (b == guizhouware$backgroundButton) {
                textCentered(g, "背景", x + w / 2, y + (h - 7f) / 2f, 7f, HudFonts.REGULAR, fg);
            } else {
                float is = Math.min(w, h) * .62f;
                icon(g, guizhouware$icon(guizhouware$key(b)), x + (w - is) / 2, y + (h - is) / 2, is, fg);
            }
            if (hover || focus) g.renderComponentTooltip(Minecraft.getInstance().font, List.of(b.getMessage()), mx, my);
        } else {
            float size = 7.5f * s;
            textCentered(g, fit(b.getMessage().getString(), w - 6, size), x + w / 2, y + (h - size) / 2 + .5f, size, HudFonts.REGULAR, fg);
        }
        if (!b.active && (hover || focus)) {
            Component reason = getMultiplayerDisabledReason();
            if (reason != null) g.renderComponentTooltip(Minecraft.getInstance().font, List.of(reason), mx, my);
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void guizhouware$click(double mx, double my, int button, CallbackInfoReturnable<Boolean> cir) {
        // Only visible native controls receive input; the old Realms overlay used old coordinates.
        cir.setReturnValue(super.mouseClicked(mx, my, button));
    }
}
