package dev.guizhou.features.modules.extra;

import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.inventory.InventoryUtil;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

public final class ItemSaverModule extends UtilityModule {
    private final Setting<Integer> durability = num("Durability", 5, 1, 50);
    private final Setting<Integer> delay = num("DelayTicks", 2, 1, 40);
    private int nextTick;
    public ItemSaverModule() { super("ItemSaver", "Moves fragile held tools into inventory or switches to a healthy replacement.", Category.PLAYER); }
    @Override protected void resetSession() { nextTick = 0; }
    private boolean fragile(ItemStack s) {
        return !s.isEmpty() && s.isDamageableItem()
                && (s.getMaxDamage() - s.getDamageValue()) * 100L <= s.getMaxDamage() * (long)durability.getValue();
    }
    public boolean isProtected(ItemStack stack) { return permitted() && fragile(stack); }
    @Override protected void tick() {
        if (!inventoryReady() || mc.player.tickCount < nextTick) return;
        ItemStack held = mc.player.getMainHandItem();
        if (!fragile(held)) return;
        int selected = InventoryUtil.selected(), chosen = -1, empty = -1;
        for (int i = 0; i < 36; i++) {
            if (i == selected) continue;
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s.isEmpty() && empty < 0) empty = i;
            if (s.is(held.getItem()) && !fragile(s)) { chosen = i; break; }
        }
        if (chosen < 0) chosen = empty;
        if (chosen < 0) return;
        if (chosen < 9) InventoryUtil.swap(chosen);
        else mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, chosen,
                selected, ClickType.SWAP, mc.player);
        nextTick = mc.player.tickCount + delay.getValue();
    }
}
