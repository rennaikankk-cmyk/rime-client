package dev.guizhou.features.modules.player;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.input.MouseInputEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.RotationRequest;
import dev.guizhou.util.inventory.SwapMode;
import dev.guizhou.util.inventory.SwapPriority;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.inventory.Result;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import static dev.guizhou.util.inventory.InventoryUtil.FULL_SCOPE;

public class MiddleClickExtraModule extends Module {

    private static final int ROTATION_PRIORITY = 1000;

    private final Setting<Boolean> fireworkInAir = bool("FireworkInAir", true);

    public MiddleClickExtraModule() {
        super("MiddleClick", "Throws a pearl or firework on middle click.", Category.PLAYER);
    }

    @Subscribe
    private void onMouse(MouseInputEvent event) {
        if (nullCheck() || mc.screen != null) return;
        if (MiddleClickFriendModule.consumesClick()) return;
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_MIDDLE) return;
        if (event.getAction() != GLFW.GLFW_PRESS) return;
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) { dev.guizhou.manager.HeartbeatManager.notifyDeniedOnce(); return; }

        if (fireworkInAir.getValue() && mc.player.isFallFlying()) {
            Result firework = InventoryUtil.find(Items.FIREWORK_ROCKET, FULL_SCOPE);
            if (firework.found()) {
                GuizhouWare.swapManager.withSwap(firework, SwapMode.ALTSILENT, SwapPriority.USER_ACTION,
                        () -> mc.gameMode.useItem(mc.player, firework.hand()));
            }
            return;
        }

        Result pearl = InventoryUtil.find(Items.ENDER_PEARL, FULL_SCOPE);
        if (pearl.found()) {

            if (!GuizhouWare.rotationManager.trySubmit(new RotationRequest("MiddleClick.pearl", ROTATION_PRIORITY,
                    GuizhouWare.rotationManager.getRealYaw(), GuizhouWare.rotationManager.getRealPitch(),
                    RotationRequest.Mode.SILENT, false, true))) return;
            GuizhouWare.swapManager.withSwap(pearl, SwapMode.ALTSILENT, SwapPriority.USER_ACTION,
                    () -> {

                        GuizhouWare.rotationManager.setBypassUseSpoof(true);
                        try {
                            mc.gameMode.useItem(mc.player, pearl.hand());
                        } finally {
                            GuizhouWare.rotationManager.setBypassUseSpoof(false);
                        }
                    });
        }
    }
}
