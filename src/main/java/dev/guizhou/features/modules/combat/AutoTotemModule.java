package dev.guizhou.features.modules.combat;

import dev.guizhou.features.modules.Module;
import dev.guizhou.util.inventory.InventoryUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class AutoTotemModule extends Module {

    public AutoTotemModule() {
        super("AutoTotem", "Keeps a totem in the offhand after confirmed inventory updates.", Category.COMBAT);
    }

    @Override
    public void onTick() {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
        if (nullCheck()) return;
        if (mc.player.containerMenu.containerId != 0) return;
        if (mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return;
        refillOffhandTotem();
    }

    private void refillOffhandTotem() {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
        int slot = findTotem();
        if (slot != -1) InventoryUtil.swapToOffhand(slot);
    }

    private int findTotem() {
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
    }

    @Override
    public String getDisplayInfo() {
        if (nullCheck()) return null;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(Items.TOTEM_OF_UNDYING)) count += stack.getCount();
        }
        return String.valueOf(count);
    }
}
