package dev.guizhou.features.modules.hud;

import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.features.modules.client.HudModule;
import dev.guizhou.util.render.HudDraw;
import dev.guizhou.util.render.ShadcnDraw;
import dev.guizhou.util.render.font.HudFonts;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Consumable column beside the hotbar: icon, right-aligned count, danger when empty. */
public class CountsHudModule extends HudModule {
    private static final float ICON = 12f;
    private static final int HOTBAR_HALF = 91;
    private static final int HUNGER_TOP_OFFSET = 39;
    private static final int BUBBLE_HEIGHT = 10;
    private static final float PAD = 5f, ROW = 15f, COUNT = 9.5f;

    private static final Item[] ITEMS = {
            Items.END_CRYSTAL,
            Items.OBSIDIAN,
            Items.ENDER_PEARL,
            Items.EXPERIENCE_BOTTLE,
    };
    private static final ItemStack[] ICONS = new ItemStack[ITEMS.length];
    static {
        for (int i = 0; i < ITEMS.length; i++) ICONS[i] = new ItemStack(ITEMS[i]);
    }

    private final int[] counts = new int[ITEMS.length], shown = new int[ITEMS.length];
    private final String[] labels = new String[ITEMS.length];

    public CountsHudModule() {
        super("Counts");
    }

    @Override
    public void render(Render2DEvent event) {
        GuiGraphics g = event.getContext();
        countAll();
        float textW = ShadcnDraw.width("999", COUNT, HudFonts.BOLD);
        for (int i = 0; i < ITEMS.length; i++) {
            if (labels[i] == null || shown[i] != counts[i]) {
                shown[i] = counts[i];
                labels[i] = Integer.toString(counts[i]);
            }
            textW = Math.max(textW, ShadcnDraw.width(labels[i], COUNT, HudFonts.BOLD));
        }
        float cardW = PAD * 2 + ICON + 6f + textW;
        float cardH = PAD * 2 + ITEMS.length * ROW - 3f;

        int defX = screenWidth() / 2 + HOTBAR_HALF + 4;
        int defY = screenHeight() - HUNGER_TOP_OFFSET - 1 - (int) cardH;
        if (showingBubbles()) defY -= BUBBLE_HEIGHT;
        int[] o = origin(defX, defY, (int) cardW, (int) cardH);
        int ox = o[0], oy = o[1];

        HudDraw.card(g, ox, oy, cardW, cardH, HudDraw.RADIUS);
        float scale = ICON / 16f;
        for (int i = 0; i < ITEMS.length; i++) {
            float ry = oy + PAD + i * ROW - 1f;
            if (i > 0) HudDraw.rule(g, ox + PAD, ry - 1.5f, cardW - PAD * 2);
            var pose = g.pose();
            pose.pushMatrix();
            pose.translate(ox + PAD, ry + (ROW - 3f - ICON) / 2f + 1f);
            pose.scale(scale, scale);
            g.renderItem(ICONS[i], 0, 0);
            pose.popMatrix();
            int tone = counts[i] > 0 ? ShadcnDraw.TEXT : ShadcnDraw.DANGER;
            HudDraw.textMidRight(g, labels[i], ox + cardW - PAD, ry + (ROW - 3f) / 2f + 1f, COUNT, HudFonts.BOLD, tone);
        }
        bounds(ox, oy, (int) cardW, (int) cardH);
    }

    private void countAll() {
        java.util.Arrays.fill(counts, 0);
        Inventory inv = mc.player.getInventory();
        for (int s = 0; s < inv.getContainerSize(); s++) {
            ItemStack stack = inv.getItem(s);
            if (stack.isEmpty()) continue;
            for (int i = 0; i < ITEMS.length; i++) {
                if (stack.is(ITEMS[i])) { counts[i] += stack.getCount(); break; }
            }
        }
    }

    private boolean showingBubbles() {
        return mc.player.isEyeInFluid(FluidTags.WATER)
                || mc.player.getAirSupply() < mc.player.getMaxAirSupply();
    }
}
