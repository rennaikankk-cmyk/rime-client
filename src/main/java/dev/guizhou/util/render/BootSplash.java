package dev.guizhou.util.render;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.client.InterfaceFeedbackModule;
import dev.guizhou.util.render.font.HudFonts;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Branded artwork for the resource-loading overlay. {@code MixinLoadingOverlay} keeps
 * the vanilla timing and completion logic and only swaps what is drawn.
 *
 * <p>Typography and shapes reuse {@link HudFonts} through {@link ShadcnDraw} /
 * {@link HudDraw}: graphite field, iris {@code #8B7CFF} and cyan {@code #5CC8FF}.
 * No badge, no halo. A failed frame disables the skin for the rest of the session
 * so vanilla can take over.
 */
public final class BootSplash {
    private static final int BG = ShadcnDraw.BG;
    private static final String WORDMARK = "Rime";

    private static volatile boolean failed;
    private static boolean startupCuePlayed;

    private BootSplash() {}

    public static boolean active() {
        if (failed) return false;
        InterfaceFeedbackModule settings = InterfaceFeedbackModule.get();
        return settings == null || settings.isEnabled() && settings.bootAnimation.getValue();
    }

    /** Replacement for the vanilla brand color, keeping the vanilla fade alpha. */
    public static int backdrop(int alpha) {
        return (alpha & 0xFF) << 24 | BG & 0xFFFFFF;
    }

    /** Startup overlay fully faded out: the title screen is now showing. */
    public static void startupFinished() {
        if (startupCuePlayed) return;
        startupCuePlayed = true;
        UiSounds.startup();
    }

    /**
     * Draws the lockup and progress bar. Alphas follow vanilla: during fade-in the overlay
     * ramps up, for the first second of fade-out only the bar fades, then everything fades.
     */
    public static void render(GuiGraphics g, float progress, float fadeOut, float fadeInT, boolean fadeIn, long shownMs) {
        try {
            float bgAlpha, logoAlpha;
            if (fadeOut >= 1f) {
                logoAlpha = bgAlpha = 1f - Math.clamp(fadeOut - 1f, 0f, 1f);
            } else if (fadeIn) {
                bgAlpha = Math.clamp(fadeInT, 0.15f, 1f);
                logoAlpha = Math.clamp(fadeInT, 0f, 1f);
            } else {
                bgAlpha = logoAlpha = 1f;
            }
            float barAlpha = fadeOut < 1f ? 1f - Math.clamp(fadeOut, 0f, 1f) : 0f;
            draw(g, progress, bgAlpha, logoAlpha, barAlpha, fadeOut, shownMs);
        } catch (RuntimeException | LinkageError failure) {
            failed = true;
            GuizhouWare.LOGGER.warn("Loading screen artwork failed; using the vanilla loading screen", failure);
        }
    }

    private static void draw(GuiGraphics g, float progress, float bgAlpha, float logoAlpha, float barAlpha,
                             float fadeOut, long shownMs) {
        int w = g.guiWidth(), h = g.guiHeight();
        long time = System.currentTimeMillis();

        g.fillGradient(0, 0, w, (int) (h * 0.55f), fade(ShadcnDraw.ACCENT, 0.045f * bgAlpha), fade(ShadcnDraw.ACCENT, 0f));
        if (logoAlpha <= 0.004f) return;

        float title = Math.clamp(Math.min(w, h) * 0.036f, 13f, 20f);
        float wordW = ShadcnDraw.width(WORDMARK, title, HudFonts.SEMI);
        float cx = w / 2f, cy = h * 0.46f;

        float tWord = ease(Math.clamp(shownMs / 460f, 0f, 1f));
        float tRule = ease(Math.clamp((shownMs - 70) / 400f, 0f, 1f));
        float tBar = ease(Math.clamp((shownMs - 140) / 440f, 0f, 1f));
        float exit = fadeOut >= 1f ? Math.clamp(fadeOut - 1f, 0f, 1f) : 0f;
        float lift = 1f + 0.018f * exit * exit;

        var pose = g.pose();
        pose.pushMatrix();
        try {
            pose.translate(cx, cy);
            pose.scale(lift, lift);
            pose.translate(-cx, -cy);

            float a = logoAlpha * tWord;
            float wordMid = cy - title * 0.35f;
            float rise = (1f - tWord) * 5f;
            HudDraw.textMidCentered(g, WORDMARK, cx, wordMid - rise, title, HudFonts.SEMI, fade(ShadcnDraw.TEXT, a));

            float ra = logoAlpha * tRule;
            if (ra > 0.004f) {
                float rw = wordW;
                float rx = cx - rw / 2f;
                float ry = wordMid + title * 0.72f;
                ShadcnDraw.roundGradient(g, rx, ry, rw, 1f, 0.5f,
                        fade(ShadcnDraw.ACCENT, 0.9f * ra), fade(ShadcnDraw.ACCENT_2, 0.9f * ra));
            }

            float ba = barAlpha * tBar * logoAlpha;
            if (ba > 0.004f) {
                float bw = Math.min(Math.max(wordW, 128f), w * 0.4f);
                float bh = 2f;
                float bx = cx - bw / 2f;
                float by = wordMid + title * 0.72f + 14f;
                ShadcnDraw.round(g, bx, by, bw, bh, bh / 2f, fade(ShadcnDraw.RAISED, ba));
                float fw = Math.max(bh, bw * Math.clamp(progress, 0f, 1f));
                ShadcnDraw.roundGradient(g, bx, by, fw, bh, bh / 2f,
                        fade(ShadcnDraw.ACCENT, ba), fade(ShadcnDraw.ACCENT_2, ba));
                float sweepW = Math.min(22f, fw);
                float sx = bx - sweepW + (time % 1800L) / 1800f * (fw + sweepW);
                float x0 = Math.max(bx, sx), x1 = Math.min(bx + fw, sx + sweepW);
                if (x1 - x0 > 0.5f) {
                    ShadcnDraw.round(g, x0, by, x1 - x0, bh, bh / 2f, fade(ShadcnDraw.TEXT, 0.16f * ba));
                }
            }
        } finally {
            pose.popMatrix();
        }
    }

    private static float ease(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private static int fade(int color, float factor) {
        return ShadcnDraw.alpha(color, factor);
    }
}
