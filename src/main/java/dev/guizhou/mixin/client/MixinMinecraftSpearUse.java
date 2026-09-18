package dev.guizhou.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.guizhou.features.modules.combat.GuangxiElytraChaseModule;
import dev.guizhou.features.modules.player.AutoUseModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Minecraft.class)
public class MixinMinecraftSpearUse {
    @WrapOperation(method = "handleKeybinds", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;releaseUsingItem(Lnet/minecraft/world/entity/player/Player;)V"),
            require = 1)
    private void guizhouware$keepOwnedSpearUse(MultiPlayerGameMode gameMode, Player player,
                                            Operation<Void> original) {
        if (!GuangxiElytraChaseModule.shouldKeepUsingSpear() && !AutoUseModule.shouldKeepUsing()) {
            original.call(gameMode, player);
        }
    }
}
