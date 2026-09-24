package dev.guizhou.mixin.network;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import dev.guizhou.event.impl.network.PacketEvent;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static dev.guizhou.util.traits.Util.EVENT_BUS;

@Mixin(Connection.class)
public class MixinClientConnection {

    @Shadow
    private Channel channel;
    @Shadow
    @Final
    private PacketFlow receiving;

    @Inject(method = "channelRead0", at = @At("HEAD"), cancellable = true)
    public void channelRead0(ChannelHandlerContext chc, Packet<?> packet, CallbackInfo ci) {
        if (this.channel.isOpen() && packet != null) {
            try {
                if (packet instanceof BundlePacket<?> bundle) {
                    // Bundled children are dispatched inside packet handling, never through
                    // channelRead0 — post them here or receive listeners never see them
                    // (insta-break on crystal spawn was dead because of this).
                    for (Packet<?> child : bundle.subPackets()) {
                        EVENT_BUS.post(new PacketEvent.Receive(child));
                    }
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
    private void sendImmediately(Packet<?> packet, ChannelFutureListener callbacks, boolean flush, CallbackInfo ci) {
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
