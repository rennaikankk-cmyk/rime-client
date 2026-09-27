package dev.guizhou.features.modules.combat;

import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.inventory.Result;
import net.minecraft.world.item.Items;

import static dev.guizhou.util.inventory.InventoryUtil.FULL_SCOPE;

public class KeyPotionModule extends Module {

    private final Setting<Boolean> onGround = bool("OnGround", false);

    public KeyPotionModule() {
        super("KeyPotion", "Throws a splash potion on keybind press.", Category.PLAYER);
    }

    private int waited;
    @Override public void onDisable() { dev.guizhou.util.inventory.ItemUseTracker.cancel("KeyPotion"); }
    @Override public void onEnable() { waited = 0; if (nullCheck()) disable(); }
    @Subscribe private void onTick(dev.guizhou.event.impl.entity.player.TickEvent event) {
        // Enabled from the main menu (GUI or config) there is no player; stay a one-shot toggle.
        if (nullCheck() || mc.gameMode == null || mc.getConnection() == null) {
            disable();
            return;
        }
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) {
            dev.guizhou.manager.HeartbeatManager.notifyDeniedOnce(); disable(); return;
        }
        String pause = dev.guizhou.util.combat.UtilityActionGuard.pauseReason();
        if (pause != null || onGround.getValue() && !mc.player.onGround()) {
            dev.guizhou.features.commands.Command.sendMessage("投药暂缓：" + (pause != null ? pause : "需要站在地面"));
            disable(); return;
        }

        Result potion = InventoryUtil.find(Items.SPLASH_POTION, FULL_SCOPE);
        if (potion.found()) {
            float yaw = mc.player.getYRot();
            float pitch = 90f;
            if (!dev.guizhou.util.inventory.ItemUseTracker.use("KeyPotion", potion, yaw, pitch)) {
                if (++waited < 40) return;
                dev.guizhou.features.commands.Command.sendMessage("投药未完成：视角或物品持续被占用，请重试。");
            }
        }
        disable();
    }
}
