package dev.guizhou.mixin.network;

import dev.guizhou.util.inventory.ItemUseTracker;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class MixinItemUseAck {
    @Inject(method = "handleBlockChangedAck", at = @At("TAIL"), require = 1)
    private void acknowledged(ClientboundBlockChangedAckPacket packet, CallbackInfo ci) {
        ItemUseTracker.acknowledge(packet.sequence());
    }
    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void corrected(ClientboundPlayerPositionPacket packet, CallbackInfo ci) { ItemUseTracker.reset(); }
}
