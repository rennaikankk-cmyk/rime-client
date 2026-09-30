package dev.guizhou.mixin.entity;

import dev.guizhou.GuizhouWare;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.guizhou.features.modules.extra.SafeWalkModule;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Player.class)
public class MixinPlayerSafeWalk {
    @ModifyReturnValue(method = "isStayingOnGroundSurface", at = @At("RETURN"))
    private boolean rime$safeWalk(boolean vanilla) {
        if ((Object)this != Minecraft.getInstance().player || GuizhouWare.moduleManager == null) return vanilla;
        SafeWalkModule module = GuizhouWare.moduleManager.getModuleByClass(SafeWalkModule.class);
        return vanilla || module != null && module.active();
    }
}
