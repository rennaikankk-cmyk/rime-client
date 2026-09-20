package dev.guizhou.features.modules.hud;

import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.features.modules.client.HudModule;
import dev.guizhou.util.render.ShadcnDraw;

public class SpeedHudModule extends HudModule {

    public SpeedHudModule() {
        super("Speed");
    }

    @Override
    public void render(Render2DEvent event) {
        double dx = mc.player.getX() - mc.player.xo;
        double dz = mc.player.getZ() - mc.player.zo;
        double bps = Math.sqrt(dx * dx + dz * dz) * 20.0;
        String value = String.format("%.1f", bps);

        float pad = 6f;
        float labelW = ShadcnDraw.width("Speed", 10f);
        float cardW = pad + labelW + 8f + ShadcnDraw.width(value + " bps", 12f) + pad;
        float cardH = 24f;

        int defX = 4;
        int defY = Math.round(bottomAnchor() - 4f - cardH);
        int[] o = origin(defX, defY, (int) cardW, (int) cardH);
        int ox = o[0], oy = o[1];

        ShadcnDraw.panel(event.getContext(), ox, oy, cardW, cardH, 6f);
        float ty = oy + (cardH - 12f) / 2f - 1f;
        ShadcnDraw.text(event.getContext(), "Speed", ox + pad, ty, 10f, ShadcnDraw.MUTED);
        float vx = ox + pad + labelW + 8f;
        ShadcnDraw.text(event.getContext(), value, vx, ty, 12f, ShadcnDraw.TEXT);
        ShadcnDraw.text(event.getContext(), " bps", vx + ShadcnDraw.width(value, 12f), ty + 2f, 10f, ShadcnDraw.MUTED);
        bounds(ox, oy, (int) cardW, (int) cardH);
    }
}
