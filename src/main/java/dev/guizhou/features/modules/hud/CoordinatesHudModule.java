package dev.guizhou.features.modules.hud;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.features.modules.client.HudClientModule;
import dev.guizhou.features.modules.client.HudModule;
import dev.guizhou.features.modules.client.HudPosition;
import dev.guizhou.util.render.HudDraw;
import dev.guizhou.util.render.ShadcnDraw;
import dev.guizhou.util.render.font.HudFonts;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.Level;

/** Position card: axis-labelled XYZ on top, the other dimension's X/Z underneath. */
public class CoordinatesHudModule extends HudModule {
    private static final float PAD = 6f, VALUE = 9.5f, AXIS = 6.5f, SUB = 7.5f, GROUP_GAP = 7f;
    private static final String[] AXES = {"X", "Y", "Z"};
    private static final int OVERWORLD_TONE = ShadcnDraw.SUCCESS, NETHER_TONE = 0xFFFB7A5A;

    private final String[] values = new String[3];
    private String subX = "", subZ = "";
    private int cx = Integer.MIN_VALUE, cy, cz;
    private boolean cNether, cEnd;

    public CoordinatesHudModule() {
        super("Coordinates");
    }

    @Override
    public void render(Render2DEvent event) {
        GuiGraphics g = event.getContext();
        int x = (int) Math.floor(mc.player.getX());
        int y = (int) Math.floor(mc.player.getY());
        int z = (int) Math.floor(mc.player.getZ());
        boolean nether = mc.level.dimension().equals(Level.NETHER);
        boolean end = mc.level.dimension().equals(Level.END);
        if (x != cx || y != cy || z != cz || nether != cNether || end != cEnd) {
            cx = x; cy = y; cz = z; cNether = nether; cEnd = end;
            values[0] = Integer.toString(x);
            values[1] = Integer.toString(y);
            values[2] = Integer.toString(z);
            subX = end ? "" : Integer.toString(nether ? x * 8 : Math.floorDiv(x, 8));
            subZ = end ? "" : Integer.toString(nether ? z * 8 : Math.floorDiv(z, 8));
        }

        float row1 = 0f;
        for (int i = 0; i < 3; i++) {
            row1 += ShadcnDraw.width(AXES[i], AXIS, HudFonts.SEMI) + 3f + ShadcnDraw.width(values[i], VALUE, HudFonts.SEMI);
            if (i < 2) row1 += GROUP_GAP;
        }
        String dim = nether ? dev.guizhou.util.lang.ClientLanguage.text("主世界") : dev.guizhou.util.lang.ClientLanguage.text("下界");
        float row2 = end ? 0f : 9f + ShadcnDraw.width(dim, AXIS, HudFonts.SEMI) + 6f
                + ShadcnDraw.width("X", AXIS, HudFonts.SEMI) + 3f + ShadcnDraw.width(subX, SUB, HudFonts.REGULAR) + GROUP_GAP
                + ShadcnDraw.width("Z", AXIS, HudFonts.SEMI) + 3f + ShadcnDraw.width(subZ, SUB, HudFonts.REGULAR);
        float cardW = PAD * 2 + Math.max(row1, row2);
        float cardH = end ? 22f : 33f;

        HudClientModule hudClient = GuizhouWare.moduleManager.getModuleByClass(HudClientModule.class);
        HudPosition pos = hudClient != null ? hudClient.positionOf(this) : HudPosition.BOTTOM_RIGHT;
        int below = hudClient != null ? hudClient.stackBelowHeight(this) : 0;
        int defX = pos.isLeft() ? 4 : Math.round(screenWidth() - cardW - 4f);
        int defY = pos.isBottom()
                ? Math.round(bottomAnchor() - 4f - below - cardH)
                : Math.round(screenHeight() / 2f - cardH / 2f);
        int[] o = origin(defX, defY, (int) cardW, (int) cardH);
        int ox = o[0], oy = o[1];

        HudDraw.card(g, ox, oy, cardW, cardH, HudDraw.RADIUS);
        float mid = oy + 11f;
        float tx = ox + PAD;
        for (int i = 0; i < 3; i++) {
            HudDraw.textMid(g, AXES[i], tx, mid, AXIS, HudFonts.SEMI, ShadcnDraw.SUBTLE);
            tx += ShadcnDraw.width(AXES[i], AXIS, HudFonts.SEMI) + 3f;
            HudDraw.textMid(g, values[i], tx, mid, VALUE, HudFonts.SEMI, ShadcnDraw.TEXT);
            tx += ShadcnDraw.width(values[i], VALUE, HudFonts.SEMI) + GROUP_GAP;
        }
        if (!end) {
            HudDraw.rule(g, ox + PAD, oy + 20f, cardW - PAD * 2);
            float mid2 = oy + 26.5f;
            float sx = ox + PAD;
            HudDraw.dot(g, sx + 2.5f, mid2, 4f, nether ? OVERWORLD_TONE : NETHER_TONE);
            sx += 9f;
            HudDraw.textMid(g, dim, sx, mid2, AXIS, HudFonts.SEMI, ShadcnDraw.MUTED);
            sx += ShadcnDraw.width(dim, AXIS, HudFonts.SEMI) + 6f;
            HudDraw.textMid(g, "X", sx, mid2, AXIS, HudFonts.SEMI, ShadcnDraw.SUBTLE);
            sx += ShadcnDraw.width("X", AXIS, HudFonts.SEMI) + 3f;
            HudDraw.textMid(g, subX, sx, mid2, SUB, HudFonts.REGULAR, ShadcnDraw.MUTED);
            sx += ShadcnDraw.width(subX, SUB, HudFonts.REGULAR) + GROUP_GAP;
            HudDraw.textMid(g, "Z", sx, mid2, AXIS, HudFonts.SEMI, ShadcnDraw.SUBTLE);
            sx += ShadcnDraw.width("Z", AXIS, HudFonts.SEMI) + 3f;
            HudDraw.textMid(g, subZ, sx, mid2, SUB, HudFonts.REGULAR, ShadcnDraw.MUTED);
        }
        bounds(ox, oy, (int) cardW, (int) cardH);
    }
}
