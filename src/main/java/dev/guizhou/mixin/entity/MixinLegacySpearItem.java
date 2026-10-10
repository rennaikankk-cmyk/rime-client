package dev.guizhou.mixin.entity;

import dev.guizhou.util.elytra.LegacySpearCompat;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Item.class)
public class MixinLegacySpearItem {
    @Inject(method = "getUseDuration", at = @At("RETURN"), cancellable = true)
    private void guizhouware$legacySpearDuration(ItemStack stack, LivingEntity entity, CallbackInfoReturnable<Integer> cir) {
        if (entity.level().isClientSide() && cir.getReturnValueI() == 0 && LegacySpearCompat.profile(stack) != null)
            cir.setReturnValue(72000);
    }

    @Inject(method = "getUseAnimation", at = @At("RETURN"), cancellable = true)
    private void guizhouware$legacySpearAnimation(ItemStack stack, CallbackInfoReturnable<ItemUseAnimation> cir) {
        if (cir.getReturnValue() == ItemUseAnimation.NONE && LegacySpearCompat.profile(stack) != null)
            cir.setReturnValue(ItemUseAnimation.SPEAR);
    }

    @Inject(method = "use", at = @At("RETURN"), cancellable = true)
    private void guizhouware$legacySpearUse(Level level, Player player, InteractionHand hand,
                                          CallbackInfoReturnable<InteractionResult> cir) {
        if (!level.isClientSide() || cir.getReturnValue() != InteractionResult.PASS) return;
        var legacy = LegacySpearCompat.profile(player.getItemInHand(hand));
        if (legacy == null) return;
        player.startUsingItem(hand);
        legacy.kinetic().makeSound(player);
        cir.setReturnValue(InteractionResult.CONSUME);
    }
}
