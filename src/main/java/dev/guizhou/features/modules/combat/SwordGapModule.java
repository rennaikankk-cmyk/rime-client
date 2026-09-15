package dev.guizhou.features.modules.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.Module;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.inventory.Result;
import dev.guizhou.util.inventory.ResultType;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;

import java.util.EnumSet;

public class SwordGapModule extends Module {

    private boolean latched;

    public SwordGapModule() {
        super("SwordGap", "Switches from sword to gap while holding right click.", Category.COMBAT);
    }

    @Override
    public void onDisable() {
        releaseLatch();
    }

    @Override
    public void onTick() {
        if (nullCheck()) {
            releaseLatch();
            return;
        }

        // A silent swap may have interrupted our latch; re-sync so we can re-latch while RMB is held.
        if (latched && !GuizhouWare.swapManager.isLatched()) latched = false;

        boolean useHeld = mc.options.keyUse.isDown();

        if (useHeld && !latched) {
            if (!mc.player.getMainHandItem().is(ItemTags.SWORDS)) return;

            Result apple = InventoryUtil.find(Items.ENCHANTED_GOLDEN_APPLE, EnumSet.of(ResultType.HOTBAR));
            if (apple.found() && GuizhouWare.swapManager.latch(apple)) latched = true;
        } else if (!useHeld && latched) {
            releaseLatch();
        }
    }

    private void releaseLatch() {
        if (!latched) return;
        GuizhouWare.swapManager.release();
        latched = false;
    }
}
