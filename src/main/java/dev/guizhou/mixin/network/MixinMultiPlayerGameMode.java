package dev.guizhou.mixin.network;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.network.AttackBlockEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static dev.guizhou.util.traits.Util.EVENT_BUS;

@Mixin(MultiPlayerGameMode.class)
public class MixinMultiPlayerGameMode {

    @Unique private float guizhouware$origUseYaw, guizhouware$origUsePitch;
    @Unique private boolean guizhouware$useRotationApplied;

    @Inject(method = "useItem", at = @At("HEAD"))
    private void guizhouware$useItemRotateHead(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        guizhouware$useRotationApplied = false;
        if (player != Minecraft.getInstance().player || GuizhouWare.rotationManager == null) return;
        var rotations = GuizhouWare.rotationManager;
        if (!rotations.isRotating() || rotations.isBypassUseSpoof()) return;
        guizhouware$origUseYaw = player.getYRot();
        guizhouware$origUsePitch = player.getXRot();
        guizhouware$useRotationApplied = true;
        player.setYRot(rotations.getRotationYaw());
        player.setXRot(rotations.getRotationPitch());
    }

    @Inject(method = "useItem", at = @At("RETURN"))
    private void guizhouware$useItemRotateReturn(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (player != Minecraft.getInstance().player || !guizhouware$useRotationApplied) return;
        guizhouware$useRotationApplied = false;
        player.setYRot(guizhouware$origUseYaw);
        player.setXRot(guizhouware$origUsePitch);
    }

    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void guizhouware$onStartDestroyBlock(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        AttackBlockEvent event = new AttackBlockEvent(pos, mc.level.getBlockState(pos), direction);
        if (EVENT_BUS.post(event)) {
            cir.setReturnValue(false);
        }
    }

}
