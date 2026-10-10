package dev.guizhou.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.guizhou.util.elytra.LegacySpearCompat;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LocalPlayer.class)
public class MixinLegacySpearUseEffects {
    @WrapOperation(method = {"isSlowDueToUsingItem", "itemUseSpeedMultiplier"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;getOrDefault(Lnet/minecraft/core/component/DataComponentType;Ljava/lang/Object;)Ljava/lang/Object;"))
    private <T> T guizhouware$legacySpearEffects(ItemStack stack, DataComponentType<T> type, T fallback,
                                               Operation<T> original) {
        var legacy = type == DataComponents.USE_EFFECTS ? LegacySpearCompat.profile(stack) : null;
        return legacy == null ? original.call(stack, type, fallback) : (T) legacy.effects();
    }
}
