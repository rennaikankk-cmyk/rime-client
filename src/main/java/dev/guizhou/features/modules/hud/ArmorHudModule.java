package dev.guizhou.features.modules.hud;

import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.features.modules.client.HudModule;
import dev.guizhou.util.EnchantmentUtil;
import dev.guizhou.util.render.HudDraw;
import dev.guizhou.util.render.ShadcnDraw;
import dev.guizhou.util.render.font.HudFonts;
import net.minecraft.client.gui.GuiGraphics;
import dev.guizhou.util.render.GuiTextureCompat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * Four fixed armour columns: item, durability bar and percentage graded
 * success / warning / danger, plus a protection letter. Empty slots keep
 * their place with the vanilla silhouette so a missing piece is obvious.
 */
public class ArmorHudModule extends HudModule {
    private static final int ICON = 16;
    private static final float COL = 20f, COL_GAP = 2f, PAD = 5f;
    private static final int HOTBAR_HALF = 91;
    private static final int HUNGER_TOP_OFFSET = 39;
    private static final int BUBBLE_HEIGHT = 10;
    private static final float PERCENT = 6.5f;

    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private static final ResourceLocation[] EMPTY = {
            ResourceLocation.withDefaultNamespace("textures/item/empty_armor_slot_helmet.png"),
            ResourceLocation.withDefaultNamespace("textures/item/empty_armor_slot_chestplate.png"),
            ResourceLocation.withDefaultNamespace("textures/item/empty_armor_slot_leggings.png"),
            ResourceLocation.withDefaultNamespace("textures/item/empty_armor_slot_boots.png"),
    };

    private record ProtTag(ResourceKey<Enchantment> key, String label) {}

    private static final ProtTag[] PROT_TAGS = {
            new ProtTag(Enchantments.PROTECTION, "P"),
            new ProtTag(Enchantments.BLAST_PROTECTION, "BP"),
            new ProtTag(Enchantments.FIRE_PROTECTION, "FP"),
            new ProtTag(Enchantments.PROJECTILE_PROTECTION, "PP"),
    };

    private static final String[] PERCENT_TEXT = new String[101];
    static {
        for (int i = 0; i <= 100; i++) PERCENT_TEXT[i] = i + "%";
    }

    public ArmorHudModule() {
        super("Armor");
    }

    @Override
    public void render(Render2DEvent event) {
        boolean any = false;
        for (EquipmentSlot slot : SLOTS) {
            if (!mc.player.getItemBySlot(slot).isEmpty()) { any = true; break; }
        }
        if (!any) {
            bounds(0, 0, 0, 0);
            return;
        }
        GuiGraphics g = event.getContext();
        float cardW = PAD * 2 + SLOTS.length * COL + (SLOTS.length - 1) * COL_GAP;
        float cardH = PAD + ICON + 3f + 2f + 3f + PERCENT + PAD - 1f;

        int defX = Math.round(screenWidth() / 2f + HOTBAR_HALF - cardW);
        int defY = Math.round(screenHeight() - HUNGER_TOP_OFFSET - 1 - cardH);
        if (showingBubbles()) defY -= BUBBLE_HEIGHT;
        int[] o = origin(defX, defY, (int) cardW, (int) cardH);
        int ox = o[0], oy = o[1];

        HudDraw.card(g, ox, oy, cardW, cardH, HudDraw.RADIUS);
        float pulse = .6f + .4f * (float) Math.sin(System.currentTimeMillis() / 180.0);
        for (int i = 0; i < SLOTS.length; i++) {
            ItemStack stack = mc.player.getItemBySlot(SLOTS[i]);
            float cx = ox + PAD + i * (COL + COL_GAP);
            int ix = Math.round(cx + (COL - ICON) / 2f), iy = Math.round(oy + PAD);
            float barY = oy + PAD + ICON + 3f;
            float barX = cx + 1.5f, barW = COL - 3f;
            float midY = barY + 2f + 3f + PERCENT / 2f;
            if (stack.isEmpty()) {
                GuiTextureCompat.blit(g, EMPTY[i], ix, iy, 0, 0, ICON, ICON, ICON, ICON, 0x40FFFFFF);
                ShadcnDraw.round(g, barX, barY, barW, 2f, 1f, HudDraw.TRACK);
                HudDraw.textMidCentered(g, "—", cx + COL / 2f, midY, PERCENT, HudFonts.SEMI, ShadcnDraw.SUBTLE);
                continue;
            }
            g.renderItem(stack, ix, iy);
            String tag = protectionTag(stack);
            if (tag != null) {
                HudDraw.textMid(g, tag, cx + .5f, oy + PAD + 1.5f, 5.5f, HudFonts.BOLD, ShadcnDraw.ACCENT);
            }
            if (stack.isDamageableItem() && stack.getMaxDamage() > 0) {
                float frac = 1f - stack.getDamageValue() / (float) stack.getMaxDamage();
                int tone = HudDraw.grade(frac);
                if (frac <= .2f) tone = HudDraw.fade(tone, pulse);
                HudDraw.bar(g, barX, barY, barW, 2f, frac, tone);
                int pct = Math.clamp(Math.round(frac * 100f), 0, 100);
                HudDraw.textMidCentered(g, PERCENT_TEXT[pct], cx + COL / 2f, midY, PERCENT, HudFonts.SEMI,
                        HudDraw.grade(frac));
            } else {
                ShadcnDraw.round(g, barX, barY, barW, 2f, 1f, HudDraw.TRACK);
                HudDraw.textMidCentered(g, "∞", cx + COL / 2f, midY, PERCENT, HudFonts.SEMI, ShadcnDraw.MUTED);
            }
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
