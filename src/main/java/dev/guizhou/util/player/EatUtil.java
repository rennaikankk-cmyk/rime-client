package dev.guizhou.util.player;

import dev.guizhou.GuizhouWare;
import dev.guizhou.util.traits.Util;
import net.minecraft.world.InteractionHand;

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
}
