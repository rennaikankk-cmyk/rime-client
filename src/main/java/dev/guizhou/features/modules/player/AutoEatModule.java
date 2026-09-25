package dev.guizhou.features.modules.player;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.HeartbeatManager;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.inventory.Result;
import dev.guizhou.util.inventory.ResultType;
import dev.guizhou.util.player.ItemUseOwnership;
import dev.guizhou.util.player.ScreenGate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Set;

/**
 * Eats the best safe food from the hotbar or offhand when hunger (or optionally health) is low.
 * The hotbar slot is held with a SwapManager latch for the duration of the meal, like SwordGap,
 * and the meal is released as soon as you press use yourself.
 */
public class AutoEatModule extends Module {
    private static AutoEatModule INSTANCE;
    private static final Set<net.minecraft.world.item.Item> NEVER = Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE,
            Items.POISONOUS_POTATO, Items.PUFFERFISH, Items.CHORUS_FRUIT, Items.SUSPICIOUS_STEW);

    private final Setting<Integer> hunger = num("Hunger", 14, 1, 19);
    private final Setting<Integer> health = num("Health", 0, 0, 20);
    private final Setting<Boolean> gapples = bool("Gapples", false);
    private final Setting<Boolean> pauseInCombat = bool("PauseInCombat", true);
    private final Setting<Double> combatRange = num("CombatRange", 8.0, 2.0, 24.0);

    private final ItemUseOwnership ownership = new ItemUseOwnership();
    private long clock;

    public AutoEatModule() {
        super("AutoEat", "Eats the best safe food when hunger drops below the threshold (optionally on low health). "
                + "Skips rotten flesh, chorus fruit and similar; pauses near enemies.", Category.PLAYER);
        INSTANCE = this;
    }

    /** Keeps vanilla from releasing our meal while the use key is up (see MixinMinecraftSpearUse). */
    public static boolean shouldKeepEating() {
        AutoEatModule module = INSTANCE;
        return module != null && module.isEnabled() && HeartbeatManager.authorized() && !nullCheck()
                && !mc.options.keyUse.isDown() && module.ownsMeal();
    }

    private boolean ownsMeal() {
        if (!ownership.owned() || !mc.player.isUsingItem()) return false;
        InteractionHand hand = mc.player.getUsedItemHand();
        return ownership.ownsActiveUse(mc.player, mc.level, mc.player.getUseItem(), hand == InteractionHand.MAIN_HAND);
    }

    @Override
    public void onDisable() {
        resetState();
    }

    @Subscribe(priority = 120)
    private void onPreTick(PreTickEvent event) {
        if (!HeartbeatManager.authorized()) { HeartbeatManager.notifyDeniedOnce(); resetState(); return; }
        clock++;
        if (nullCheck() || mc.gameMode == null) {
            ownership.forget();
            return;
        }
        if (ownership.owned()) {
            if (mc.options.keyUse.isDown()) {
                // The player took over: stop owning the use, keep the slot until they let go.
                ownership.forget();
                ownership.recordAttempt(clock);
            } else if (!ownsMeal()) {
                finishMeal();
            }
            return;
        }
        if (GuizhouWare.swapManager.isLatched(this)) {
            if (!mc.player.isUsingItem()) GuizhouWare.swapManager.release(this);
            return;
        }
        if (!hungry() || !ownership.canAttempt(clock)) return;
        if (!ScreenGate.allowsWorldUpkeep() || mc.player.isUsingItem() || GuizhouWare.swapManager.isLatched()) return;
        if (pauseInCombat.getValue() && enemyNear()) return;
        startMeal();
    }

    private boolean hungry() {
        int food = mc.player.getFoodData().getFoodLevel();
        if (food <= hunger.getValue()) return true;
        return health.getValue() > 0 && mc.player.getHealth() <= health.getValue() && food < 20;
    }

    private void startMeal() {
        if (!HeartbeatManager.authorized()) return;
        ownership.recordAttempt(clock);
        InteractionHand hand;
        if (edible(mc.player.getOffhandItem())) {
            hand = InteractionHand.OFF_HAND;
        } else {
            Result food = best();
            if (food == null || !GuizhouWare.swapManager.latch(this, food)) return;
            hand = InteractionHand.MAIN_HAND;
        }
        ItemStack held = mc.player.getItemInHand(hand);
        if (mc.gameMode.useItem(mc.player, hand).consumesAction() && mc.player.isUsingItem()
                && mc.player.getUseItem() == held) {
            ownership.start(mc.player, mc.level, held,
                    hand == InteractionHand.MAIN_HAND ? ItemUseOwnership.Hand.MAIN : ItemUseOwnership.Hand.OFF);
        } else {
            GuizhouWare.swapManager.release(this);
        }
    }

    /** Ends our meal and hands the hotbar back. */
    private void finishMeal() {
        ownership.forget();
        ownership.recordAttempt(clock);
        GuizhouWare.swapManager.release(this);
    }

    private Result best() {
        Result best = null;
        float bestValue = -1f;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!edible(stack)) continue;
            FoodProperties food = stack.get(DataComponents.FOOD);
            float value = food.nutrition() + food.saturation();
            if (value > bestValue) {
                bestValue = value;
                best = new Result(i, stack, ResultType.HOTBAR);
            }
        }
        return best;
    }

    private boolean edible(ItemStack stack) {
        if (stack.isEmpty() || NEVER.contains(stack.getItem())) return false;
        if (!gapples.getValue() && (stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE))) return false;
        FoodProperties food = stack.get(DataComponents.FOOD);
        return food != null && mc.player.canEat(food.canAlwaysEat()) && !mc.player.getCooldowns().isOnCooldown(stack.getItem());
    }

    private boolean enemyNear() {
        double rangeSq = combatRange.getValue() * combatRange.getValue();
        for (Player other : mc.level.players()) {
            if (other == mc.player || !other.isAlive() || other.isSpectator()) continue;
            if (GuizhouWare.friendManager.isFriend(other)) continue;
            if (other.distanceToSqr(mc.player) <= rangeSq) return true;
        }
        return false;
    }

    private void resetState() {
        boolean ours = !nullCheck() && ownsMeal() && !mc.options.keyUse.isDown();
        ownership.forget();
        if (ours && mc.gameMode != null) mc.gameMode.releaseUsingItem(mc.player);
        if (GuizhouWare.swapManager != null) GuizhouWare.swapManager.release(this);
    }

    @Override
    public String getDisplayInfo() {
        return ownership.owned() ? "Eating" : null;
    }
}
