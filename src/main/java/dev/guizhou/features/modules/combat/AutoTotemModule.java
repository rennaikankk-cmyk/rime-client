package dev.guizhou.features.modules.combat;

import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.util.inventory.InventoryUtil;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class AutoTotemModule extends Module {

    public AutoTotemModule() {
        super("AutoTotem", "Ensures a totem is in your offhand.", Category.COMBAT);
    }

    @Subscribe
    private void onPacketReceive(PacketEvent.Receive event) {
        if (nullCheck()) return;
        if (!(event.getPacket() instanceof ClientboundEntityEventPacket packet)) return;
        if (packet.getEventId() != 35 || packet.getEntity(mc.level) != mc.player) return;

        mc.execute(() -> {
            if (nullCheck()) return;
            if (mc.player.containerMenu.containerId != 0) return;
            // The server clears the popped totem itself; dropping it client-side now stops us from
            // seeing a ghost totem and skipping the refill.
            mc.player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            refillOffhandTotem();
        });
    }

    @Override
    public void onTick() {
        if (nullCheck()) return;
        if (mc.player.containerMenu.containerId != 0) return;
        if (mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return;
        refillOffhandTotem();
    }

    private void refillOffhandTotem() {
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
