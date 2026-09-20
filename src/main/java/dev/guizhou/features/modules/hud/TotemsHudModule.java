package dev.guizhou.features.modules.hud;

import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.features.modules.client.HudModule;
import dev.guizhou.util.render.ShadcnDraw;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class TotemsHudModule extends HudModule {
    private static final int ICON = 16;
    private static final int XP_BAR_TOP_OFFSET = 32;
    private static final int GAP_ABOVE_XP = 6;

    public TotemsHudModule() {
        super("Totems");
    }

    @Override
    public void render(Render2DEvent event) {
        int count = countTotems();
        ItemStack stack = new ItemStack(Items.TOTEM_OF_UNDYING, Math.max(count, 1));

        float pad = 4f;
        float cardW = pad + ICON + pad;
        float cardH = pad + ICON + pad;

        int defX = Math.round(screenWidth() / 2f - cardW / 2f);
        int defY = Math.round(screenHeight() - XP_BAR_TOP_OFFSET - GAP_ABOVE_XP - cardH);
        int[] o = origin(defX, defY, (int) cardW, (int) cardH);
        int ox = o[0], oy = o[1];

        ShadcnDraw.panel(event.getContext(), ox, oy, cardW, cardH, 6f);
        event.getContext().renderItem(stack, ox + (int) pad, oy + (int) pad);
        String label = String.valueOf(count);
        int color = count > 0 ? ShadcnDraw.TEXT : 0xFFFF5555;
        ShadcnDraw.text(event.getContext(), label,
                ox + cardW - ShadcnDraw.width(label, 10f) - 2f, oy + cardH - 11f, 10f, color);
        bounds(ox, oy, (int) cardW, (int) cardH);
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
