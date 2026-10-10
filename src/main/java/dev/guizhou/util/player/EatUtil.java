package dev.guizhou.util.player;

import dev.guizhou.GuizhouWare;
import dev.guizhou.util.traits.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class EatUtil implements Util {

    private EatUtil() {
        throw new AssertionError();
    }

    /**
     * Whether an action that needs the mainhand should stand down: something is holding the hotbar
     * with a latch (SwordGap eating a gapple), or the mainhand is mid-use already.
     */
    public static boolean shouldDefer() {
        if (mc.player == null) return false;
        if (GuizhouWare.swapManager.isLatched()) return true;
        return mc.player.isUsingItem() && mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND;
    }

    public static boolean isGoldenApple(ItemStack stack) {
        return stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE);
    }

    /** Both hands qualify, including manual meals and AutoEat; spear charge does not. */
    public static boolean isEatingGoldenApple() {
        return mc.player != null && mc.player.isUsingItem() && isGoldenApple(mc.player.getUseItem())
                && mc.player.getUseItem() == mc.player.getItemInHand(mc.player.getUsedItemHand());
    }
}
