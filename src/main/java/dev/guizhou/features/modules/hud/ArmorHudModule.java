package dev.guizhou.features.modules.hud;

import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.features.modules.client.HudModule;
import dev.guizhou.util.EnchantmentUtil;
import dev.guizhou.util.render.ShadcnDraw;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

public class ArmorHudModule extends HudModule {
    private static final int ICON = 16;
    private static final int STRIDE = 18;
    private static final int HOTBAR_HALF = 91;
    private static final int HUNGER_TOP_OFFSET = 39;
    private static final int BUBBLE_HEIGHT = 10;
    private static final float PAD = 4f;

    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private record ProtTag(ResourceKey<Enchantment> key, String label) {}

    private static final ProtTag[] PROT_TAGS = {
            new ProtTag(Enchantments.PROTECTION, "P"),
            new ProtTag(Enchantments.BLAST_PROTECTION, "BP"),
            new ProtTag(Enchantments.FIRE_PROTECTION, "FP"),
            new ProtTag(Enchantments.PROJECTILE_PROTECTION, "PP"),
    };

    public ArmorHudModule() {
        super("Armor");
    }

    @Override
    public void render(Render2DEvent event) {
        int count = 0;
        for (EquipmentSlot slot : SLOTS) {
            if (!mc.player.getItemBySlot(slot).isEmpty()) count++;
        }
        if (count == 0) {
            bounds(0, 0, 0, 0);
            return;
        }
        float cardW = PAD * 2 + count * STRIDE - (STRIDE - ICON);
        float cardH = PAD * 2 + ICON + 8f;

        int totalWidthAll = (int) (PAD * 2 + SLOTS.length * STRIDE - (STRIDE - ICON));
        int defX = screenWidth() / 2 + HOTBAR_HALF - totalWidthAll;
        int defY = screenHeight() - HUNGER_TOP_OFFSET - 1 - (int) cardH;
        if (showingBubbles()) defY -= BUBBLE_HEIGHT;
        int[] o = origin(defX, defY, (int) cardW, (int) cardH);
        int ox = o[0], oy = o[1];

        ShadcnDraw.panel(event.getContext(), ox, oy, cardW, cardH, 6f);
        int drawn = 0;
        for (EquipmentSlot slot : SLOTS) {
            ItemStack stack = mc.player.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            int sx = ox + (int) PAD + drawn * STRIDE;
            int sy = oy + (int) PAD;
            event.getContext().renderItem(stack, sx, sy);
            String tag = protectionTag(stack);
            if (tag != null) {
                ShadcnDraw.text(event.getContext(), tag, sx, sy + ICON, 8f, ShadcnDraw.ACCENT);
            }
            drawn++;
        }
        bounds(ox, oy, (int) cardW, (int) cardH);
    }

    private String protectionTag(ItemStack stack) {
        for (ProtTag t : PROT_TAGS) {
            if (EnchantmentUtil.has(t.key(), stack)) return t.label();
        }
        return null;
    }

    private boolean showingBubbles() {
        return mc.player.isEyeInFluid(FluidTags.WATER)
                || mc.player.getAirSupply() < mc.player.getMaxAirSupply();
    }
}
