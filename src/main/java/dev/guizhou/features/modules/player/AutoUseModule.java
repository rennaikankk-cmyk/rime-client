package dev.guizhou.features.modules.player;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.client.TargetsModule;
import dev.guizhou.features.modules.combat.GuangxiElytraChaseModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.HeartbeatManager;
import dev.guizhou.util.elytra.SpearCombat;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.player.AutoUseWhitelist;
import dev.guizhou.util.player.ItemUseOwnership;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Automatically uses the held whitelisted item (spear charge, shield block).
 * Port of the legacy interaction-tweaks AutoUse, without touching the use
 * keybind: the use is started directly through gameMode.useItem and kept only
 * while the started ItemStack identity, hand and player/world session still
 * match. Spears release and re-charge once their damage window expires.
 */
public class AutoUseModule extends Module {
    private static AutoUseModule INSTANCE;

    public final Setting<String> whitelist = str("Items", "^(.*spear|.*sword|shield)$").setPage("General");
    public final Setting<Boolean> onlyWhenEnemyNear = bool("OnlyWhenEnemyNear", false).setPage("General");
    public final Setting<Double> enemyRange = num("EnemyRange", 12.0, 1.0, 64.0).setPage("General");
    public final Setting<Boolean> useFood = bool("UseFood", false).setPage("General");

    private final ItemUseOwnership ownership = new ItemUseOwnership();
    private TargetsModule targetRules;
    private long clock;
    private int selectedSlot = -1;

    public AutoUseModule() {
        super("AutoUse", "Automatically uses a whitelisted held item (spear charge / shield block)", Category.PLAYER);
        INSTANCE = this;
    }

    /** Joint guard for the vanilla releaseUsingItem hook (see MixinMinecraftSpearUse). */
    public static boolean shouldKeepUsing() {
        AutoUseModule module = INSTANCE;
        return module != null && module.isEnabled() && HeartbeatManager.authorized()
                && !nullCheck() && !mc.options.keyUse.isDown() && !module.blocked()
                && module.ownsActiveUse();
    }

    private boolean ownsActiveUse() {
        if (nullCheck() || !ownership.owned() || !mc.player.isUsingItem()) return false;
        return ownership.ownsHeldUse(mc.player, mc.level, mc.player.getUseItem(),
                mc.player.getItemInHand(mc.player.getUsedItemHand()),
                mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND, InventoryUtil.selected(), selectedSlot);
    }

    @Override
    public void onDisable() {
        stopOwnedUse();
    }

    @Subscribe(priority = 125)
    private void onPreTick(PreTickEvent event) {
        if (!HeartbeatManager.authorized()) { HeartbeatManager.notifyDeniedOnce(); return; }
        clock++;
        if (nullCheck()) {
            ownership.forget();
            return;
        }
        Player player = mc.player;
        if (ownership.sessionChanged(player, mc.level)) {
            ownership.forget();
        }
        if (mc.options.keyUse.isDown()) {
            // A manual press outranks the module and adopts the running charge.
            if (ownership.shouldYieldToManual(true)) ownership.forget();
            return;
        }
        if (blocked()) {
            stopOwnedUse();
            return;
        }
        AutoUseWhitelist rules = AutoUseWhitelist.of(whitelist.getValue());
        if (!rules.valid() || onlyWhenEnemyNear.getValue() && !enemyNear()) {
            stopOwnedUse();
            return;
        }
        if (ownership.owned()) {
            if (!ownsActiveUse()) {
                ownership.forget();      // item, slot or hand changed, or the use ended
                ownership.recordAttempt(clock);
                return;                 // never restart into a manual equipment change
            } else if (!usable(rules, player.getUseItem()) || SpearCombat.isSpear(player.getUseItem())
                    && SpearCombat.hasDamageWindowExpired(player)) {
                stopOwnedUse();          // damage window over: release now, re-charge after the interval
            } else {
                return;                  // keep holding the owned use
            }
        }
        // A running use without our identity belongs to the player or another module.
        if (player.isUsingItem() || !ownership.canAttempt(clock)) return;
        InteractionHand hand = pickHand(rules);
        if (hand == null || mc.gameMode == null || mc.getConnection() == null) return;
        ItemStack held = hand == InteractionHand.MAIN_HAND ? player.getMainHandItem() : player.getOffhandItem();
        ownership.recordAttempt(clock);
        if (mc.gameMode.useItem(player, hand).consumesAction()
                && player.isUsingItem() && player.getUsedItemHand() == hand && player.getUseItem() == held) {
            ownership.start(player, mc.level, held,
                    hand == InteractionHand.MAIN_HAND ? ItemUseOwnership.Hand.MAIN : ItemUseOwnership.Hand.OFF);
            selectedSlot = InventoryUtil.selected();
        }
    }

    private boolean blocked() {
        return mc.screen != null
                || mc.player.isDeadOrDying() || mc.player.isSpectator()
                || mc.player.containerMenu != mc.player.inventoryMenu
                || !mc.player.containerMenu.getCarried().isEmpty()
                || (GuizhouWare.swapManager != null && GuizhouWare.swapManager.isLatched())
                || GuangxiElytraChaseModule.isWeaponControlRequested()
                || GuangxiElytraChaseModule.isMaceDiveActive();
    }

    private InteractionHand pickHand(AutoUseWhitelist rules) {
        if (usable(rules, mc.player.getMainHandItem())) return InteractionHand.MAIN_HAND;
        if (usable(rules, mc.player.getOffhandItem())) return InteractionHand.OFF_HAND;
        return null;
    }

    private boolean usable(AutoUseWhitelist rules, ItemStack stack) {
        if (stack == null || stack.isEmpty() || mc.player.getCooldowns().isOnCooldown(stack.getItem())) return false;
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) return false;
        if (!rules.matches(id.getPath()) && !rules.matches(id.toString())) return false;
        var food = stack.get(DataComponents.FOOD);
        if (food != null && (!useFood.getValue() || !mc.player.canEat(food.canAlwaysEat()))) return false;
        return stack.getUseDuration(mc.player) > 0;
    }

    /** Nearby valid enemy per TargetsModule (friends and target rules respected). */
    private boolean enemyNear() {
        if (targetRules == null) targetRules = GuizhouWare.moduleManager.getModuleByClass(TargetsModule.class);
        if (targetRules == null) return false;
        double squared = enemyRange.getValue() * enemyRange.getValue();
        for (Player other : mc.level.players()) {
            if (other.isRemoved() || other.isSpectator() || !other.isAlive() || !targetRules.isValidTarget(other)) continue;
            if (mc.player.distanceToSqr(other) <= squared) return true;
        }
        return false;
    }

    /** Releases the running use only when it is still the one this module started. */
    private void stopOwnedUse() {
        if (!ownership.owned()) return;
        if (nullCheck() || mc.gameMode == null || mc.getConnection() == null) {
            ownership.forget();
            return;
        }
        boolean ours = ownsActiveUse() && !mc.options.keyUse.isDown();
        ownership.forget();
        if (ours) {
            mc.gameMode.releaseUsingItem(mc.player);
            ownership.recordAttempt(clock);   // a release ends a cycle; keep retries spaced
        }
    }

    @Override
    public String getDisplayInfo() {
        if (!AutoUseWhitelist.of(whitelist.getValue()).valid()) return "Invalid regex";
        return ownership.owned() ? ownership.hand().name() : null;
    }
}
