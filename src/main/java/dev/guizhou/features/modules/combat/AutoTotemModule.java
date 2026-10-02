package dev.guizhou.features.modules.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.combat.LethalPredictor;
import dev.guizhou.util.combat.TotemSafety;
import dev.guizhou.util.elytra.SpearCombat;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.player.ItemUseOwnership;
import dev.guizhou.util.player.GappleSelection;
import dev.guizhou.util.player.ScreenGate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
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
    private final Setting<Boolean> gappleFallback = bool("GappleFallback", true);
    private final Setting<Boolean> gapPickaxe = bool("GapPickaxe", false);
    private final Setting<Boolean> combatSync = bool("CombatSync", true);

    private final Setting<Boolean> lethal = bool("Lethal", true).setPage("Lethal");
    private final Setting<Double> lethalMargin = num("LethalMargin", 2.0, 0.0, 10.0).setPage("Lethal");
    private final Setting<Boolean> lethalCrystals = bool("Crystals", true).setPage("Lethal");
    private final Setting<Boolean> lethalPlaceable = bool("CrystalSpots", true).setPage("Lethal");
    private final Setting<Boolean> lethalBlocks = bool("AnchorsBeds", true).setPage("Lethal");
    private final Setting<Boolean> lethalFall = bool("Fall", true).setPage("Lethal");
    private final Setting<Boolean> lethalElytra = bool("Elytra", true).setPage("Lethal");
    private final Setting<Boolean> lethalMelee = bool("Melee", true).setPage("Lethal");
    private final Setting<Double> enemyRange = num("EnemyRange", 10.0, 3.0, 20.0).setPage("Lethal");

    private int lastSwapTick = Integer.MIN_VALUE;
    private Item pinned;
    private String lastThreat;
    private int lethalHoldUntil;
    private static final int LETHAL_HOLD_TICKS = 10;
    private final ItemUseOwnership gappleUse = new ItemUseOwnership();

    public AutoTotemModule() {
        super("AutoTotem", "Keeps the offhand on a totem, crystal, gapple, bed or anchor. Lethal forces a totem "
                + "whenever a nearby crystal, crystal spot, anchor, bed, fall, elytra impact or armed enemy could kill you.",
                Category.COMBAT);
        safeHealth.setVisibility(v -> gappleOnUse.getValue());
        crapple.setVisibility(v -> item.getValue() == ItemMode.GAPPLE || gappleOnUse.getValue());
        gappleFallback.setVisibility(v -> item.getValue() == ItemMode.GAPPLE || gappleOnUse.getValue());
        gapPickaxe.setVisibility(v -> gappleOnUse.getValue());
        for (Setting<?> setting : java.util.List.of(lethalMargin, lethalCrystals, lethalPlaceable, lethalBlocks,
                lethalFall, lethalElytra, lethalMelee, enemyRange)) {
            setting.setVisibility(v -> lethal.getValue());
        }
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
        useGappleOnUse(sync(false));
    }

    /** Route a manual right click to the safe offhand meal before vanilla tries the main hand. */
    public static boolean tryUseOffhandGapple() {
        if (!dev.guizhou.manager.HeartbeatManager.authorized() || GuizhouWare.moduleManager == null) return false;
        AutoTotemModule module = GuizhouWare.moduleManager.getModuleByClass(AutoTotemModule.class);
        return module != null && module.isEnabled() && !nullCheck() && module.gappleRequested()
                && module.useGappleOnUse(module.sync(false));
    }

    @Override
    public void onDisable() {
        stopGappleUse();
    }

    @Subscribe
    private void onPacketReceive(PacketEvent.Receive event) {
        if (nullCheck()) return;
        if (!(event.getPacket() instanceof ClientboundEntityEventPacket packet)) return;
        if (packet.getEventId() != 35 || packet.getEntity(mc.level) != mc.player) return;
        var poppedPlayer = mc.player;
        var poppedLevel = mc.level;
        ItemStack observedOffhand = mc.player.getOffhandItem();
        // Vanilla consumes a main-hand totem first; status 35 carries no hand field.
        boolean offhandPop = observedOffhand.is(Items.TOTEM_OF_UNDYING)
                && !mc.player.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
        mc.execute(() -> {
            if (!isEnabled() || nullCheck() || mc.player != poppedPlayer || mc.level != poppedLevel) return;
            if (mc.player.containerMenu.containerId != 0) return;
            // The server clears the popped totem itself; dropping it client-side now stops us from
            // seeing a ghost totem and skipping the refill.
            if (offhandPop && mc.player.getOffhandItem() == observedOffhand) {
                mc.player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            }
            if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
            lastSwapTick = Integer.MIN_VALUE;
            sync(true);
        });
    }

    private Item sync(boolean ignoreDelay) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()
                || dev.guizhou.util.compat.ClientCoexistence.blocks(dev.guizhou.util.compat.ForeignClientProbe.Domain.OFFHAND)
                || nullCheck() || mc.gameMode == null || mc.player.isDeadOrDying() || !ScreenGate.allowsInventoryUpkeep(true)) {
            stopGappleUse();
            return null;
        }

        Item wanted = choose();
        if (wanted != gappleItem() || !gappleRequested()) stopGappleUse();
        if (wanted == null || holding(wanted)) return wanted;
        long elapsed = (long) mc.player.tickCount - lastSwapTick;
        int lethalLeft = lethalHoldUntil - mc.player.tickCount;
        boolean emergencyTotem = wanted == Items.TOTEM_OF_UNDYING
                && (mc.player.getHealth() + mc.player.getAbsorptionAmount() <= health.getValue()
                || lethal.getValue() && lethalLeft > 0 && lethalLeft <= LETHAL_HOLD_TICKS);
        if (!ignoreDelay && !emergencyTotem && lastSwapTick != Integer.MIN_VALUE
                && elapsed >= 0 && elapsed < delay.getValue()) return wanted;
        int slot = find(wanted);
        if (slot < 0) return wanted;
        InventoryUtil.swapToOffhand(slot);
        lastSwapTick = mc.player.tickCount;
        return wanted;
    }

    /** A manual food request owns the one real item-use channel, including during spear charge. */
    private boolean useGappleOnUse(Item wanted) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized() || nullCheck() || mc.gameMode == null
                || mc.getConnection() == null || mc.screen != null || mc.player.isDeadOrDying() || mc.player.isSpectator()
                || !ScreenGate.allowsWorldUpkeep() || !gappleRequested() || wanted != gappleItem() || !holding(wanted)
                || dev.guizhou.util.compat.ClientCoexistence.blocks(dev.guizhou.util.compat.ForeignClientProbe.Domain.OFFHAND)
                || dev.guizhou.util.compat.ClientCoexistence.blocks(dev.guizhou.util.compat.ForeignClientProbe.Domain.FOOD)
                || GuizhouWare.swapManager != null && GuizhouWare.swapManager.isLatched()) {
            stopGappleUse();
            return false;
        }
        ItemStack apple = mc.player.getOffhandItem();
        var food = apple.get(DataComponents.FOOD);
        if (food == null || !mc.player.canEat(food.canAlwaysEat()) || mc.player.getCooldowns().isOnCooldown(apple)) {
            stopGappleUse();
            return false;
        }
        if (mc.player.isUsingItem() && mc.player.getUsedItemHand() == InteractionHand.OFF_HAND
                && mc.player.getUseItem() == apple) return true;
        if (gappleUse.owned()) gappleUse.forget();
        // The same failed attempt can be visited by the tick and right-click hook. Reserve it once.
        if (!gappleUse.canAttempt(mc.player.tickCount)) return true;
        gappleUse.recordAttempt(mc.player.tickCount);
        if (mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player);
        if (mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND).consumesAction()
                && mc.player.isUsingItem() && mc.player.getUsedItemHand() == InteractionHand.OFF_HAND
                && mc.player.getUseItem() == apple) {
            gappleUse.start(mc.player, mc.level, apple, ItemUseOwnership.Hand.OFF);
        }
        return true;
    }

    private boolean gappleRequested() {
        return gappleOnUse.getValue() && mc.options.keyUse.isDown()
                && mc.player.getHealth() + mc.player.getAbsorptionAmount() >= safeHealth.getValue()
                && mainHandAllowsGapple();
    }

    /** Revocation, risk or key release stops only the meal this module actually started. */
    private void stopGappleUse() {
        boolean ours = !nullCheck() && mc.player.isUsingItem()
                && mc.player.getUsedItemHand() == InteractionHand.OFF_HAND
                && gappleUse.ownsActiveUse(mc.player, mc.level, mc.player.getUseItem(), false)
                && mc.player.getOffhandItem() == gappleUse.stack();
        gappleUse.forget();
        if (ours && mc.gameMode != null && mc.getConnection() != null
                && !dev.guizhou.util.compat.ClientCoexistence.blocks(dev.guizhou.util.compat.ForeignClientProbe.Domain.OFFHAND)
                && !dev.guizhou.util.compat.ClientCoexistence.blocks(dev.guizhou.util.compat.ForeignClientProbe.Domain.FOOD)) {
            mc.gameMode.releaseUsingItem(mc.player);
        }
    }

    private Item choose() {
        float hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        if (hp <= health.getValue()) return Items.TOTEM_OF_UNDYING;
        if (lethal.getValue()) {
            LethalPredictor.Threat threat = LethalPredictor.predict(new LethalPredictor.Options(
                    lethalCrystals.getValue(), lethalPlaceable.getValue(), lethalBlocks.getValue(),
                    lethalFall.getValue(), lethalElytra.getValue(), lethalMelee.getValue(), enemyRange.getValue()));
            if (TotemSafety.lethal(mc.player.getHealth(), mc.player.getAbsorptionAmount(), threat.damage(),
                    lethalMargin.getValue())) {
                lastThreat = threat.source();
                lethalHoldUntil = mc.player.tickCount + LETHAL_HOLD_TICKS;
                return Items.TOTEM_OF_UNDYING;
            }
            // Hysteresis: a threat hovering at the line must not flip the offhand every tick.
            int left = lethalHoldUntil - mc.player.tickCount;
            if (left > 0 && left <= LETHAL_HOLD_TICKS) return Items.TOTEM_OF_UNDYING;
        }
        lastThreat = null;
        if (gappleRequested()) {
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
        GappleSelection.Apple eating = null;
        if (mc.player.isUsingItem() && mc.player.getUsedItemHand() == InteractionHand.OFF_HAND
                && mc.player.getUseItem() == mc.player.getOffhandItem()) {
            if (mc.player.getUseItem().is(Items.GOLDEN_APPLE)) eating = GappleSelection.Apple.NORMAL;
            else if (mc.player.getUseItem().is(Items.ENCHANTED_GOLDEN_APPLE)) eating = GappleSelection.Apple.ENCHANTED;
        }
        return GappleSelection.choose(crapple.getValue(), gappleFallback.getValue(),
                available(Items.GOLDEN_APPLE), available(Items.ENCHANTED_GOLDEN_APPLE), eating)
                == GappleSelection.Apple.NORMAL ? Items.GOLDEN_APPLE : Items.ENCHANTED_GOLDEN_APPLE;
    }

    private boolean available(Item apple) {
        return mc.player.getOffhandItem().is(apple) || find(apple) >= 0;
    }

    private boolean mainHandAllowsGapple() {
        Item held = mc.player.getMainHandItem().getItem();
        return held == Items.END_CRYSTAL || held == Items.TOTEM_OF_UNDYING
                || held == Items.MACE || SpearCombat.isSpear(mc.player.getMainHandItem())
                || mc.player.getMainHandItem().is(ItemTags.SWORDS)
                || mc.player.getMainHandItem().is(ItemTags.AXES)
                || gapPickaxe.getValue() && mc.player.getMainHandItem().is(ItemTags.PICKAXES);
    }

    private boolean holding(Item wanted) {
        ItemStack stack = mc.player.getOffhandItem();
        if (wanted == BED) return stack.is(ItemTags.BEDS);
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
        String label = lastThreat != null && wanted == Items.TOTEM_OF_UNDYING ? "LETHAL " + lastThreat : item.getValue().name();
        return label + " " + count;
    }
}
