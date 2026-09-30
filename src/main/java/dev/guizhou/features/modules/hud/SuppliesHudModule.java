package dev.guizhou.features.modules.hud;

import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.features.modules.client.HudModule;
import dev.guizhou.util.render.HudDraw;
import dev.guizhou.util.render.ShadcnDraw;
import dev.guizhou.util.render.font.HudFonts;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Combat supply counter above the hotbar: the anarchy kit line
 * (obsidian, crystals, totems, pearls, gapples, lava, pistons, anchors).
 * Only non-zero entries draw, so the bar collapses when you are broke.
 * Mirrors Obelisk's resource HUD concept against this client's HUD kit.
 */
public class SuppliesHudModule extends HudModule {

    private static final int ICON = 16;
    private static final int GAP = 6;
    private static final float H = 22f;
    private static final float COUNT = 11f;
    private static final int XP_BAR_TOP_OFFSET = 32;
    private static final int GAP_ABOVE_XP = 6;

    private record Supply(Item item, int warnAt) {}
    private static final Supply[] SUPPLIES = {
            new Supply(Items.OBSIDIAN, 32),
            new Supply(Items.END_CRYSTAL, 8),
            new Supply(Items.TOTEM_OF_UNDYING, 2),
            new Supply(Items.ENDER_PEARL, 8),
            new Supply(Items.GOLDEN_APPLE, 3),
            new Supply(Items.LAVA_BUCKET, 1),
            new Supply(Items.PISTON, 4),
            new Supply(Items.RESPAWN_ANCHOR, 1),
    };

    private final ItemStack[] stacks = new ItemStack[SUPPLIES.length];

    public SuppliesHudModule() {
        super("Supplies");
        for (int i = 0; i < SUPPLIES.length; i++) {
            stacks[i] = new ItemStack(SUPPLIES[i].item());
        }
    }

    @Override
    public void render(Render2DEvent event) {
        GuiGraphics g = event.getContext();
        Inventory inv = mc.player.getInventory();

        int visible = 0;
        int[] counts = new int[SUPPLIES.length];
        for (int i = 0; i < SUPPLIES.length; i++) {
            counts[i] = count(inv, SUPPLIES[i].item());
            if (counts[i] > 0) visible++;
        }
        if (visible == 0) return;

        float cardW = 5f + visible * (ICON + 4f + 18f + GAP) - GAP + 5f;
        int defX = Math.round(screenWidth() / 2f - cardW / 2f);
        int defY = Math.round(screenHeight() - XP_BAR_TOP_OFFSET - GAP_ABOVE_XP - H - 24f);
        int[] o = origin(defX, defY, (int) cardW, (int) H);
        int ox = o[0], oy = o[1];

        HudDraw.card(g, ox, oy, cardW, H, HudDraw.RADIUS);

        float x = ox + 5f;
        for (int i = 0; i < SUPPLIES.length; i++) {
            if (counts[i] <= 0) continue;
            g.renderItem(stacks[i], Math.round(x), oy + 3);
            String label = String.valueOf(counts[i]);
            int tone = counts[i] <= SUPPLIES[i].warnAt() ? ShadcnDraw.DANGER : ShadcnDraw.TEXT;
            HudDraw.textMid(g, label, x + ICON + 4f, oy + H / 2f, COUNT, HudFonts.BOLD, tone);
            x += ICON + 4f + Math.max(18f, ShadcnDraw.width(label, COUNT, HudFonts.BOLD)) + GAP;
        }
        bounds(ox, oy, (int) cardW, (int) H);
    }

    private static int count(Inventory inv, Item item) {
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) total += s.getCount();
        }
        return total;
    }
}
