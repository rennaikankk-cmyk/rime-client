package dev.guizhou.features.modules.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.inventory.InventoryUtil;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Offhand manager. Item priority follows the usual combat offhand loop
 * (totem under the health line, gapple while using a weapon, otherwise the
 * selected item or the active crystal / piston / bed / anchor module).
 * The swap itself is written against this client's inventory API.
 */
public class AutoTotemModule extends Module {

    /** Any bed stack. Callers pass this sentinel; the offhand may hold any color. */
    public static final Item BED = Items.RED_BED;

    private enum ItemMode { TOTEM, CRYSTAL, GAPPLE, BED, ANCHOR }

    private final Setting<ItemMode> item = mode("Item", ItemMode.TOTEM);
    private final Setting<Double> health = num("Health", 16.0, 1.0, 36.0);
    private final Setting<Double> safeHealth = num("SafeHealth", 14.0, 1.0, 36.0);
    private final Setting<Integer> delay = num("Delay", 0, 0, 10);
    private final Setting<Boolean> gappleOnUse = bool("GappleOnUse", false);
    private final Setting<Boolean> crapple = bool("Crapple", false);
    private final Setting<Boolean> combatSync = bool("CombatSync", true);

    private int lastSwapTick = Integer.MIN_VALUE;
    private Item pinned;

    public AutoTotemModule() {
        super("AutoTotem", "Keeps the offhand on a totem, crystal, gapple, bed or anchor.", Category.COMBAT);
        safeHealth.setVisibility(v -> gappleOnUse.getValue());
        crapple.setVisibility(v -> item.getValue() == ItemMode.GAPPLE || gappleOnUse.getValue());
    }

    /** Combat actions ask for an item immediately before they use it. */
    public static void holdForCombat(Item item) {
        AutoTotemModule module = GuizhouWare.moduleManager.getModuleByClass(AutoTotemModule.class);
        if (module == null || !module.isEnabled() || item == null) return;
        module.pinned = item;
        try {
            module.sync(true);
        } finally {
            module.pinned = null;
        }
    }

    @Subscribe(priority = 500)
    private void onPreTick(PreTickEvent event) {
        sync(false);
    }

    @Override
    public void onTick() {
        sync(false);
    }

    @Subscribe
    private void onPacketReceive(PacketEvent.Receive event) {
        if (nullCheck()) return;
        if (!(event.getPacket() instanceof ClientboundEntityEventPacket packet)) return;
        if (packet.getEventId() != 35 || packet.getEntity(mc.level) != mc.player) return;

        mc.execute(() -> {
            if (!isEnabled() || nullCheck()) return;
            if (mc.player.containerMenu.containerId != 0) return;
            // The server clears the popped totem itself; dropping it client-side now stops us from
            // seeing a ghost totem and skipping the refill.
            mc.player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
            lastSwapTick = Integer.MIN_VALUE;
            sync(true);
        });
    }

    private void sync(boolean ignoreDelay) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
        if (nullCheck() || mc.player.isDeadOrDying()) return;
        if (mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.inventoryMenu.getCarried().isEmpty()) return;
        if (mc.screen != null && !(mc.screen instanceof InventoryScreen)) return;

        Item wanted = choose();
        if (wanted == null || holding(wanted)) return;
        if (!ignoreDelay && mc.player.tickCount - lastSwapTick < delay.getValue()) return;
        int slot = find(wanted);
        if (slot < 0) return;
        InventoryUtil.swapToOffhand(slot);
        lastSwapTick = mc.player.tickCount;
    }

    private Item choose() {
        float hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        if (hp <= health.getValue()) return Items.TOTEM_OF_UNDYING;
        if (gappleOnUse.getValue() && mc.options.keyUse.isDown() && hp >= safeHealth.getValue() && mainHandAllowsGapple()) {
            return gappleItem();
        }
        if (pinned != null) return pinned;
        if (combatSync.getValue()) {
            if (enabled(GuangxiCrystalModule.class) || enabled(GuangxiTPModule.class) || enabled(GuangxiPistonModule.class)) {
                return Items.END_CRYSTAL;
            }
            if (enabled(GuangxiBedModule.class)) return BED;
            if (enabled(AutoAnchorModule.class)) return Items.RESPAWN_ANCHOR;
        }
        return switch (item.getValue()) {
            case CRYSTAL -> Items.END_CRYSTAL;
            case GAPPLE -> gappleItem();
            case BED -> BED;
            case ANCHOR -> Items.RESPAWN_ANCHOR;
            case TOTEM -> Items.TOTEM_OF_UNDYING;
        };
    }

    private Item gappleItem() {
        return crapple.getValue() ? Items.GOLDEN_APPLE : Items.ENCHANTED_GOLDEN_APPLE;
    }

    private boolean mainHandAllowsGapple() {
        Item held = mc.player.getMainHandItem().getItem();
        return held == Items.END_CRYSTAL || held == Items.TOTEM_OF_UNDYING
                || mc.player.getMainHandItem().is(ItemTags.SWORDS)
                || mc.player.getMainHandItem().is(ItemTags.AXES);
    }

    private boolean holding(Item wanted) {
        ItemStack stack = mc.player.getOffhandItem();
        if (wanted == BED) return stack.is(ItemTags.BEDS);
        if (wanted == Items.ENCHANTED_GOLDEN_APPLE) {
            return stack.is(Items.ENCHANTED_GOLDEN_APPLE) || (crapple.getValue() && stack.is(Items.GOLDEN_APPLE));
        }
        return stack.is(wanted);
    }

    private int find(Item wanted) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (wanted == BED) {
                if (stack.is(ItemTags.BEDS)) return i;
            } else if (stack.is(wanted)) {
                return i;
            }
        }
        return -1;
    }

    private boolean enabled(Class<? extends Module> type) {
        Module module = GuizhouWare.moduleManager.getModuleByClass(type);
        return module != null && module.isEnabled();
    }

    @Override
    public String getDisplayInfo() {
        if (nullCheck()) return null;
        Item wanted = choose();
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (wanted == BED ? stack.is(ItemTags.BEDS) : stack.is(wanted)) count += stack.getCount();
        }
        if (wanted == BED ? mc.player.getOffhandItem().is(ItemTags.BEDS) : mc.player.getOffhandItem().is(wanted)) {
            count += mc.player.getOffhandItem().getCount();
        }
        return item.getValue().name() + " " + count;
    }
}
