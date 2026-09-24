package dev.guizhou.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.guizhou.features.modules.combat.GuangxiElytraChaseModule;
import dev.guizhou.features.modules.player.AutoUseModule;
import dev.guizhou.features.modules.render.FreecamModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraftSpearUse {
    /** Freecam is camera-only: swallow attack/use keybinds, leave movement packets untouched. */
    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void guizhouware$freecamBlockAttackUse(CallbackInfo ci) {
        if (!FreecamModule.active()) return;
        var options = ((Minecraft) (Object) this).options;
        if (options == null) return;
        options.keyAttack.setDown(false);
        options.keyUse.setDown(false);
        while (options.keyAttack.consumeClick()) {
        }
        while (options.keyUse.consumeClick()) {
        }
    }

    @WrapOperation(method = "handleKeybinds", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;releaseUsingItem(Lnet/minecraft/world/entity/player/Player;)V"),
            require = 1)
    private void guizhouware$keepOwnedSpearUse(MultiPlayerGameMode gameMode, Player player,
                                            Operation<Void> original) {
        if (!GuangxiElytraChaseModule.shouldKeepUsingSpear() && !AutoUseModule.shouldKeepUsing()
                && !dev.guizhou.features.modules.player.AutoEatModule.shouldKeepEating()) {
            original.call(gameMode, player);
        }
    }
}
