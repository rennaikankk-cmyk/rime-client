package dev.guizhou.mixin.network;

import io.netty.channel.Channel;
import net.minecraft.network.PacketSendListener;
import io.netty.channel.ChannelHandlerContext;
import dev.guizhou.event.impl.network.PacketEvent;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import dev.guizhou.event.system.BundleFilter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static dev.guizhou.util.traits.Util.EVENT_BUS;

@Mixin(Connection.class)
public class MixinClientConnection {

    @Shadow
    private Channel channel;
    @Shadow
    @Final
    private PacketFlow receiving;

    /** Bundled children bypass channelRead0 individually; cancellation must filter the actual bundle. */
    @ModifyVariable(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Packet<?> rime$filterBundle(Packet<?> packet) {
        if (!this.channel.isOpen() || !(packet instanceof ClientboundBundlePacket bundle)) return packet;
        var kept = BundleFilter.filter(bundle.subPackets(), child -> EVENT_BUS.post(new PacketEvent.Receive(child)));
        return kept == null ? packet : new ClientboundBundlePacket(kept);
    }

    @Inject(method = "channelRead0", at = @At("HEAD"), cancellable = true)
    public void channelRead0(ChannelHandlerContext chc, Packet<?> packet, CallbackInfo ci) {
        if (this.channel.isOpen() && packet != null) {
            try {
                if (packet instanceof BundlePacket<?> bundle) {
                    // Children have already been posted and filtered by rime$filterBundle.
                    return;
                }
                if (EVENT_BUS.post(new PacketEvent.Receive(packet))) {
                    ci.cancel();
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Inject(method = "sendPacket", at = @At("HEAD"), cancellable = true)
    private void sendImmediately(Packet<?> packet, PacketSendListener callbacks, boolean flush, CallbackInfo ci) {
        if (this.receiving != PacketFlow.CLIENTBOUND) return;
        try {
            if (EVENT_BUS.post(new PacketEvent.Send(packet))) {
                ci.cancel();
                dev.guizhou.util.PacketSendUtil.onCancelled(packet);
            } else {
                dev.guizhou.util.PacketSendUtil.onSubmitted(packet);
            }
        } catch (Exception ignored) {
        }
    }

}
