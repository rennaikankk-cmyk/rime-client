package dev.guizhou.features.modules.hud;

import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.features.modules.client.HudModule;
import dev.guizhou.util.render.Anim;
import dev.guizhou.util.render.HudDraw;
import dev.guizhou.util.render.ShadcnDraw;
import dev.guizhou.util.render.font.HudFonts;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Totem count above the hotbar: item, number, semantic colour, flash on every pop. */
public class TotemsHudModule extends HudModule {
    private static final int ICON = 16;
    private static final int XP_BAR_TOP_OFFSET = 32;
    private static final int GAP_ABOVE_XP = 6;
    private static final float H = 22f, COUNT = 11f;
    private static final ItemStack TOTEM = new ItemStack(Items.TOTEM_OF_UNDYING);

    private final Anim anim = new Anim();
    private int lastCount = -1;

    public TotemsHudModule() {
        super("Totems");
    }

    @Override
    public void render(Render2DEvent event) {
        GuiGraphics g = event.getContext();
        int count = countTotems();
        if (lastCount >= 0 && count < lastCount) anim.set("flash", 1f);
        lastCount = count;
        float flash = anim.to("flash", 0f, 5f);

        String label = String.valueOf(count);
        float textW = Math.max(ShadcnDraw.width(label, COUNT, HudFonts.BOLD), ShadcnDraw.width("00", COUNT, HudFonts.BOLD));
        float cardW = 5f + ICON + 4f + textW + 7f;

        int defX = Math.round(screenWidth() / 2f - cardW / 2f);
        int defY = Math.round(screenHeight() - XP_BAR_TOP_OFFSET - GAP_ABOVE_XP - H);
        int[] o = origin(defX, defY, (int) cardW, (int) H);
        int ox = o[0], oy = o[1];

        int tone = count == 0 ? ShadcnDraw.DANGER : count <= 2 ? ShadcnDraw.WARNING : ShadcnDraw.TEXT;
        HudDraw.card(g, ox, oy, cardW, H, HudDraw.RADIUS);
        if (count <= 2) HudDraw.outline(g, ox, oy, cardW, H, HudDraw.RADIUS, 1f, HudDraw.fade(tone, .35f));
        if (flash > .01f) HudDraw.outline(g, ox, oy, cardW, H, HudDraw.RADIUS, 1f, HudDraw.fade(ShadcnDraw.DANGER, flash));
        g.renderItem(TOTEM, ox + 5, oy + 3);
        HudDraw.textMidRight(g, label, ox + cardW - 7f, oy + H / 2f, COUNT, HudFonts.BOLD, tone);
        bounds(ox, oy, (int) cardW, (int) H);
    }

    private int countTotems() {
        int total = 0;
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(Items.TOTEM_OF_UNDYING)) total += s.getCount();
        }
        return total;
    }
}
