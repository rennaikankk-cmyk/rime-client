package dev.guizhou.features.modules.hud;

import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.features.modules.client.HudModule;
import dev.guizhou.util.render.Anim;
import dev.guizhou.util.render.HudDraw;
import dev.guizhou.util.render.ShadcnDraw;
import dev.guizhou.util.render.font.HudFonts;
import net.minecraft.client.gui.GuiGraphics;

import java.util.Locale;

/** Horizontal speed: eased big number plus a two-second sparkline sampled at 12 Hz. */
public class SpeedHudModule extends HudModule {
    private static final float PAD = 6f, H = 27f, VALUE = 11f, LABEL = 6f, UNIT = 6.5f;
    private static final int SAMPLES = 24;
    private static final float SPARK_W = 34f, SPARK_H = 11f;

    private final Anim anim = new Anim();
    private final float[] history = new float[SAMPLES];
    private int head;
    private long lastSample;
    private int shownTenths = Integer.MIN_VALUE;
    private String shown = "0.0";

    public SpeedHudModule() {
        super("Speed");
    }

    @Override
    public void render(Render2DEvent event) {
        GuiGraphics g = event.getContext();
        double dx = mc.player.getX() - mc.player.xo;
        double dz = mc.player.getZ() - mc.player.zo;
        float bps = (float) (Math.sqrt(dx * dx + dz * dz) * 20.0);
        long now = System.currentTimeMillis();
        if (now - lastSample >= 83) {
            lastSample = now;
            history[head] = bps;
            head = (head + 1) % SAMPLES;
        }
        float eased = anim.to("bps", bps, 10f);
        int tenths = Math.round(eased * 10f);
        if (tenths != shownTenths) {
            shownTenths = tenths;
            shown = String.format(Locale.ROOT, "%.1f", tenths / 10f);
        }

        float textW = Math.max(ShadcnDraw.width(shown, VALUE, HudFonts.BOLD), ShadcnDraw.width("00.0", VALUE, HudFonts.BOLD));
        float cardW = PAD + textW + 3f + ShadcnDraw.width("b/s", UNIT) + 8f + SPARK_W + PAD;

        int defX = 4;
        int defY = Math.round(bottomAnchor() - 4f - H);
        int[] o = origin(defX, defY, (int) cardW, (int) H);
        int ox = o[0], oy = o[1];

        HudDraw.card(g, ox, oy, cardW, H, HudDraw.RADIUS);
        HudDraw.textMid(g, dev.guizhou.util.lang.ClientLanguage.text("速度"), ox + PAD, oy + 8f, LABEL, HudFonts.SEMI, ShadcnDraw.MUTED);
        HudDraw.textMid(g, shown, ox + PAD, oy + 18.5f, VALUE, HudFonts.BOLD, ShadcnDraw.TEXT);
        HudDraw.textMid(g, "b/s", ox + PAD + textW + 3f, oy + 19.5f, UNIT, HudFonts.REGULAR, ShadcnDraw.SUBTLE);

        float max = 10f;
        for (float v : history) max = Math.max(max, v);
        float sx = ox + cardW - PAD - SPARK_W, base = oy + (H + SPARK_H) / 2f;
        float step = SPARK_W / SAMPLES;
        int accent = HudDraw.accent();
        for (int i = 0; i < SAMPLES; i++) {
            float v = history[(head + i) % SAMPLES];
            float h = Math.max(.75f, v / max * SPARK_H);
            float t = i / (float) (SAMPLES - 1);
            ShadcnDraw.round(g, sx + i * step, base - h, step * .55f, h, 0f,
                    HudDraw.fade(accent, .3f + .55f * t));
        }
        bounds(ox, oy, (int) cardW, (int) H);
    }
}
