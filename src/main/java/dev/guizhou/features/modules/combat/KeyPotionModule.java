package dev.guizhou.features.modules.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.input.KeyInputEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.RotationRequest;
import dev.guizhou.util.inventory.SwapMode;
import dev.guizhou.util.inventory.SwapPriority;
import dev.guizhou.mixin.client.ClientLevelAccessor;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.inventory.Result;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.item.Items;

import static dev.guizhou.util.inventory.InventoryUtil.FULL_SCOPE;

public class KeyPotionModule extends Module {

    private final Setting<Boolean> onGround = bool("OnGround", false);

    public KeyPotionModule() {
        super("KeyPotion", "Throws a splash potion on keybind press.", Category.PLAYER);
    }

    @Subscribe
    public void onEnable() {
        if (onGround.getValue() && !mc.player.onGround()) return;

        Result potion = InventoryUtil.find(Items.SPLASH_POTION, FULL_SCOPE);
        if (potion.found()) {
            float yaw = mc.player.getYRot();
            float pitch = 90f;
            GuizhouWare.rotationManager.submit(new RotationRequest(
                    "KeyPotion", 20, yaw, pitch, RotationRequest.Mode.SILENT
            ));
            mc.gameMode.ensureHasSentCarriedItem();
            GuizhouWare.swapManager.withSwap(potion, SwapMode.ALTSILENT, SwapPriority.USER_ACTION, () -> {
                try (var handler = ((ClientLevelAccessor) mc.level).guizhouware$getBlockStatePredictionHandler().startPredicting()) {
                    mc.getConnection().send(new ServerboundUseItemPacket(potion.hand(), handler.currentSequence(), yaw, pitch));
                }
            });
        }
        disable();
    }
}
