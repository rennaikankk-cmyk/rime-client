package dev.guizhou.features.modules.extra;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.network.AttackBlockEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.inventory.InventoryUtil;
import net.minecraft.world.item.ItemStack;

public final class AutoToolModule extends UtilityModule {
    private final Setting<Boolean> restore = bool("Restore", true);
    private final Setting<Boolean> saveTools = bool("SaveTools", true);
    private int previous = -1, selected = -1;
    private Object owner;
    public AutoToolModule() { super("AutoTool", "Selects the fastest hotbar tool for manual mining and restores the owned slot.", Category.PLAYER); }
    @Subscribe private void onBlock(AttackBlockEvent event) {
        if (!inventoryReady()) return;
        int current = InventoryUtil.selected(), best = current;
        float speed = mc.player.getMainHandItem().getDestroySpeed(event.getState());
        ItemSaverModule saver = GuizhouWare.moduleManager.getModuleByClass(ItemSaverModule.class);
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (saveTools.getValue() && s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() <= 5) continue;
            if (saver != null && saver.isProtected(s)) continue;
            float candidate = s.getDestroySpeed(event.getState());
            if (candidate > speed) { best = i; speed = candidate; }
        }
        if (best == current) return;
        if (previous < 0 || current != selected) previous = current;
        owner = mc.player; selected = best; InventoryUtil.swap(best);
    }
    @Override protected void tick() { if (!mc.options.keyAttack.isDown() || mc.screen != null) resetSession(); }
    @Override protected void resetSession() {
        if (restore.getValue() && mc.player != null && owner == mc.player && InventoryUtil.selected() == selected
                && !GuizhouWare.swapManager.isLatched()) InventoryUtil.swap(previous);
        previous = selected = -1; owner = null;
    }
}
