package dev.guizhou.features.modules.player;

import dev.guizhou.event.impl.entity.player.TickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Bind;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.EnchantmentUtil;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.inventory.Result;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

import static dev.guizhou.util.inventory.InventoryUtil.FULL_SCOPE;
public class AutoXPModule extends Module {

    private final Setting<Bind> throwBind = key("Throw", Bind.none());
    private final Setting<Boolean> pauseInAir = bool("PauseInAir", true);
    private final Setting<Boolean> autoRepair = bool("AutoRepair", false);
    private final Setting<Boolean> nearCeiling = bool("NearCeiling", false)
            .described("空中贴近顶板时也允许修甲；GZBoost、空战与甲飞换装期间仍让行。");
    private final Setting<Boolean> steepAscent = bool("SteepAscent", false)
            .described("近乎垂直上升时也允许修甲，默认关闭。");
    private final Setting<Boolean> avoidEnemies = bool("AvoidEnemies", false)
            .described("附近两格有非好友玩家时暂停投瓶。");

    private static final int THROWS_PER_BATCH = 6;
    private static final long BATCH_INTERVAL_MS = 300;

    private boolean throwing;
    private boolean keyWasDown;
    private long lastThrowMs;

    public AutoXPModule() {
        super("AutoXP", "Throws XP bottles to mend your armor and tools.", Category.PLAYER);
    }

    @Override
    public void onDisable() {
        dev.guizhou.util.inventory.ItemUseTracker.cancel("AutoXP");
        throwing = false;
        keyWasDown = false;
        lastThrowMs = 0;
    }

    @Override
    public String getDisplayInfo() {
        if (!throwing && !autoRepair.getValue()) return null;
        String pause = dev.guizhou.util.combat.UtilityActionGuard.pauseReason();
        if (pause != null) return pause;
        if (dev.guizhou.util.inventory.ItemUseTracker.waiting("AutoXP")) return dev.guizhou.util.lang.ClientLanguage.text("等待动作确认");
        if (!nullCheck() && isPaused()) return dev.guizhou.util.lang.ClientLanguage.text("等待投瓶条件");
        return xpNeeded() + " XP";
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (nullCheck()) return;
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) {
            dev.guizhou.manager.HeartbeatManager.notifyDeniedOnce();
            throwing = false;
            return;
        }

        handleThrowBind();

        if (mc.screen != null) return;

        if (throwing) {
            if (!isPaused()) tickThrow();
            return;
        }

        if (!autoRepair.getValue()) return;
        if (xpNeeded() <= 0) return;
        if (isPaused()) return;
        if (!shouldThrowNow()) return;
        throwBottles(THROWS_PER_BATCH);
    }

    private boolean isPaused() {
        if (dev.guizhou.util.combat.UtilityActionGuard.pauseReason() != null
                || dev.guizhou.util.inventory.ItemUseTracker.waiting("AutoXP")) return true;
        if (avoidEnemies.getValue() && dev.guizhou.util.combat.ThrowEnvironment.nearEnemy(2)) return true;
        return pauseInAir.getValue() && !dev.guizhou.util.combat.ThrowEnvironment.accepts(
                true, nearCeiling.getValue(), steepAscent.getValue(), false, .5);
    }

    private void handleThrowBind() {
        if (mc.screen != null) {
            keyWasDown = false;
            return;
        }
        boolean down = !throwBind.getValue().isEmpty() && throwBind.getValue().isDown();
        if (down && !keyWasDown) {
            throwing = !throwing;
            if (throwing) {
                lastThrowMs = 0;
                Command.sendMessage("{green} AutoXP mending, " + xpNeeded() + " XP required.");
            } else {
                Command.sendMessage("{red} AutoXP throw disabled.");
            }
        }
        keyWasDown = down;
    }

    private void tickThrow() {
        if (xpNeeded() <= 0) {
            throwing = false;
            Command.sendMessage("{green} AutoXP mending complete.");
            return;
        }
        if (!InventoryUtil.find(Items.EXPERIENCE_BOTTLE, FULL_SCOPE).found()) {
            throwing = false;
            Command.sendMessage("{red} AutoXP out of XP bottles.");
            return;
        }
        if (!shouldThrowNow()) return;
        throwBottles(THROWS_PER_BATCH);
    }

    private void throwBottles(int amount) {
        Result xp = InventoryUtil.find(Items.EXPERIENCE_BOTTLE, FULL_SCOPE);
        if (!xp.found()) return;

        float yaw = mc.player.getYRot();
        float pitch = 90f;
        if (dev.guizhou.util.inventory.ItemUseTracker.useBatch("AutoXP", xp, yaw, pitch, null, amount) > 0)
            lastThrowMs = System.currentTimeMillis();
    }

    private boolean shouldThrowNow() {
        long now = System.currentTimeMillis();
        if (now - lastThrowMs < BATCH_INTERVAL_MS) return false;
        return true;
    }

    private int xpNeeded() {
        int total = 0;
        for (ItemStack stack : gear()) total += xpForItem(stack);
        return total;
    }

    private int xpForItem(ItemStack stack) {
        if (!isMendable(stack)) return 0;
        int damage = stack.getDamageValue();
        return damage <= 0 ? 0 : (int) Math.ceil(damage / 2.0);
    }

    private ItemStack[] gear() {
        return new ItemStack[]{
                mc.player.getItemBySlot(EquipmentSlot.HEAD),
                mc.player.getItemBySlot(EquipmentSlot.CHEST),
                mc.player.getItemBySlot(EquipmentSlot.LEGS),
                mc.player.getItemBySlot(EquipmentSlot.FEET),
                mc.player.getMainHandItem(),
                mc.player.getOffhandItem()
        };
    }

    private boolean isMendable(ItemStack stack) {
        return !stack.isEmpty() && stack.getMaxDamage() > 0 && EnchantmentUtil.has(Enchantments.MENDING, stack);
    }
}
