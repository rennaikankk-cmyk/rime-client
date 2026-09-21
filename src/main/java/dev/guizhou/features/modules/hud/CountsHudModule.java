package dev.guizhou.features.modules.hud;

import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.features.modules.client.HudModule;
import dev.guizhou.util.render.ShadcnDraw;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class CountsHudModule extends HudModule {
    private static final float ICON = 14f;
    private static final int HOTBAR_HALF = 91;
    private static final int HUNGER_TOP_OFFSET = 39;
    private static final int BUBBLE_HEIGHT = 10;
    private static final float PAD = 4f;
    private static final float ROW = 16f;

    private static final Item[] ITEMS = {
            Items.END_CRYSTAL,
            Items.OBSIDIAN,
            Items.ENDER_PEARL,
            Items.EXPERIENCE_BOTTLE,
    };

    public CountsHudModule() {
        super("Counts");
    }

    @Override
    public void render(Render2DEvent event) {
        float textW = 0f;
        for (Item item : ITEMS) {
            textW = Math.max(textW, ShadcnDraw.width("999", 11f));
        }
        float cardW = PAD * 2 + ICON + 5f + textW;
        float cardH = PAD * 2 + ITEMS.length * ROW;

        int defX = screenWidth() / 2 + HOTBAR_HALF + 4;
        int defY = screenHeight() - HUNGER_TOP_OFFSET - 1 - (int) cardH;
        if (showingBubbles()) defY -= BUBBLE_HEIGHT;
        int[] o = origin(defX, defY, (int) cardW, (int) cardH);
        int ox = o[0], oy = o[1];

        ShadcnDraw.panel(event.getContext(), ox, oy, cardW, cardH, 6f);
        float scale = ICON / 16f;
        for (int i = 0; i < ITEMS.length; i++) {
            int count = countItem(ITEMS[i]);
            float ry = oy + PAD + i * ROW;
            ItemStack stack = new ItemStack(ITEMS[i], Math.max(count, 1));
            var pose = event.getContext().pose();
            pose.pushPose();
            pose.scale(scale, scale, scale);
            event.getContext().renderItem(stack,
                    Math.round((ox + PAD) / scale), Math.round(ry / scale));
            pose.popPose();
            ShadcnDraw.text(event.getContext(), String.valueOf(count),
                    ox + PAD + ICON + 5f, ry + 2f, 11f, count > 0 ? ShadcnDraw.TEXT : 0xFFFF5555);
        }
        bounds(ox, oy, (int) cardW, (int) cardH);
    }

    private int countItem(Item item) {
        int total = 0;
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) total += s.getCount();
        }
        return total;
    }

    private boolean showingBubbles() {
        return mc.player.isEyeInFluid(FluidTags.WATER)
                || mc.player.getAirSupply() < mc.player.getMaxAirSupply();
    }
}
