package dev.guizhou.features.modules.hud;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.features.modules.client.HudClientModule;
import dev.guizhou.features.modules.client.HudModule;
import dev.guizhou.features.modules.client.HudPosition;
import dev.guizhou.util.render.ShadcnDraw;
import net.minecraft.world.level.Level;

public class CoordinatesHudModule extends HudModule {

    public CoordinatesHudModule() {
        super("Coordinates");
    }

    @Override
    public void render(Render2DEvent event) {
        int x = (int) Math.floor(mc.player.getX());
        int y = (int) Math.floor(mc.player.getY());
        int z = (int) Math.floor(mc.player.getZ());

        boolean nether = mc.level.dimension().equals(Level.NETHER);
        boolean end = mc.level.dimension().equals(Level.END);

        String value = x + ", " + y + ", " + z;
        String other;
        if (end) {
            other = "";
        } else if (nether) {
            other = "[" + (x * 8) + ", " + (z * 8) + "]";
        } else {
            other = "[" + Math.floorDiv(x, 8) + ", " + Math.floorDiv(z, 8) + "]";
        }

        float pad = 6f;
        float labelW = ShadcnDraw.width("XYZ", 10f);
        float valueW = ShadcnDraw.width(value, 12f) + (other.isEmpty() ? 0f : ShadcnDraw.width(other, 10f) + 6f);
        float cardW = pad + labelW + 8f + valueW + pad;
        float cardH = 24f;

        HudClientModule hudClient = GuizhouWare.moduleManager.getModuleByClass(HudClientModule.class);
        HudPosition pos = hudClient != null ? hudClient.positionOf(this) : HudPosition.BOTTOM_RIGHT;
        int below = hudClient != null ? hudClient.stackBelowHeight(this) : 0;
        int defX = pos.isLeft() ? 4 : Math.round(screenWidth() - cardW - 4f);
        int defY = pos.isBottom()
                ? Math.round(bottomAnchor() - 4f - below - cardH)
                : Math.round(screenHeight() / 2f - cardH / 2f);
        int[] o = origin(defX, defY, (int) cardW, (int) cardH);
        int ox = o[0], oy = o[1];

        ShadcnDraw.panel(event.getContext(), ox, oy, cardW, cardH, 6f);
        float ty = oy + (cardH - 12f) / 2f - 1f;
        ShadcnDraw.text(event.getContext(), "XYZ", ox + pad, ty, 10f, ShadcnDraw.MUTED);
        float vx = ox + pad + labelW + 8f;
        ShadcnDraw.text(event.getContext(), value, vx, ty, 12f, ShadcnDraw.TEXT);
        if (!other.isEmpty()) {
            ShadcnDraw.text(event.getContext(), other, vx + ShadcnDraw.width(value, 12f) + 6f, ty + 2f, 10f, ShadcnDraw.MUTED);
        }
        bounds(ox, oy, (int) cardW, (int) cardH);
    }
}
