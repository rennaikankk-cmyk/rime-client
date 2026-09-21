package dev.guizhou.mixin.client;

import dev.guizhou.util.elytra.SpearCapability1211;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Review item 10: a plain 1.21.1 sword has no use behaviour, so without this the
 * client would never enter the using state and the server-side spear charge could
 * not be tracked or sustained. Scoped strictly to stacks verified as translated
 * spears by {@link SpearCapability1211#detect}: use() starts holding, the duration is
 * 72000 ticks, independently of the damage window, and the pose is the closest native animation.
 */
@Mixin(Item.class)
public abstract class MixinItemSpearHeldUse {

    @Inject(method = "getUseDuration", at = @At("RETURN"), cancellable = true)
    private void guizhouware$spearUseDuration(ItemStack stack, LivingEntity entity,
                                              CallbackInfoReturnable<Integer> cir) {
        if (cir.getReturnValueI() != 0) return;
        int duration = SpearCapability1211.heldUseDuration(stack);
        if (duration > 0) cir.setReturnValue(duration);
    }

    @Inject(method = "getUseAnimation", at = @At("RETURN"), cancellable = true)
    private void guizhouware$spearUseAnimation(ItemStack stack, CallbackInfoReturnable<UseAnim> cir) {
        if (SpearCapability1211.detect(stack) != null) cir.setReturnValue(UseAnim.SPEAR);
    }

    @Inject(method = "use", at = @At("RETURN"), cancellable = true)
    private void guizhouware$spearUse(Level level, Player player, InteractionHand hand,
                                      CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        if (cir.getReturnValue() != null && cir.getReturnValue().getResult().consumesAction()) return;
        ItemStack stack = player.getItemInHand(hand);
        if (SpearCapability1211.detect(stack) != null) {
            // Follow the vanilla pattern: consume() plus explicit startUsingItem so
            // the client enters the using state the weapon controller checks.
            player.startUsingItem(hand);
            cir.setReturnValue(InteractionResultHolder.consume(stack));
        }
    }
}
