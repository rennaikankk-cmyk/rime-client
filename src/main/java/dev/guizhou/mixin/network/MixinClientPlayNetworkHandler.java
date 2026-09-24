package dev.guizhou.mixin.network;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.TotemPopEvent;
import dev.guizhou.event.impl.network.ChatEvent;
import dev.guizhou.features.modules.movement.VelocityModule;
import dev.guizhou.features.modules.player.NoRotateModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static dev.guizhou.util.traits.Util.EVENT_BUS;

@Mixin(ClientPacketListener.class)
public class MixinClientPlayNetworkHandler {
    @Unique
    private Vec3 velocity$preExplosionVel = null;
    @Unique
    private float rotation$prevYaw;
    @Unique
    private float rotation$prevPitch;

    @Inject(method = "handleBlockUpdate", at = @At("TAIL"), require = 1)
    private void onBlockUpdateApplied(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread() || GuizhouWare.placementManager == null) return;
        GuizhouWare.placementManager.onBlockUpdateApplied(packet);
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void onHandleMovePlayerPre(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread() || mc.player == null) return;
        if (GuizhouWare.tpsCounterService != null) {
            double distanceSq = GuizhouWare.tpsCounterService.onPlayerCorrection(packet);
            if (GuizhouWare.telemetryManager != null) {
                GuizhouWare.telemetryManager.onPlayerCorrection(distanceSq);
            }
        }
        if (GuizhouWare.rotationManager == null) return;
        rotation$prevYaw = mc.player.getYRot();
        rotation$prevPitch = mc.player.getXRot();
    }

    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    private void onHandleMovePlayerPost(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread() || mc.player == null || GuizhouWare.rotationManager == null) return;
        if (GuizhouWare.rotationManager.isSilentActive() || NoRotateModule.isActive()) {
            mc.player.setYRot(rotation$prevYaw);
            mc.player.setXRot(rotation$prevPitch);
        }
    }

    // HEAD runs once on Netty before ensureRunningOnSameThread re-dispatches the packet; the
    // world may only be read on the client-thread pass.
    @Inject(method = "handleSetEntityMotion", at = @At("HEAD"), cancellable = true)
    private void cancelKnockback(ClientboundSetEntityMotionPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread() || mc.player == null) return;
        if (packet.getId() != mc.player.getId()) return;
        VelocityModule velocity = GuizhouWare.moduleManager.getModuleByClass(VelocityModule.class);
        if (velocity != null && velocity.shouldCancelKnockback()) {
            ci.cancel();
        }
    }

    /**
     * The server re-sends the held stack after a silent swap-back; replacing an identical stack
     * only replays the re-equip animation. Anything else (another slot, a count or component
     * change) must apply or the client desyncs.
     */
    @Inject(method = "handleContainerSetSlot", at = @At("HEAD"), cancellable = true)
    private void silentSwapFix(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread() || mc.player == null) return;
        if (packet.getContainerId() != 0 || packet.getSlot() - 36 != mc.player.getInventory().getSelectedSlot()) return;
        ItemStack packetStack = packet.getItem();
        ItemStack handStack = mc.player.getMainHandItem();
        if (packetStack.isEmpty() || handStack.isEmpty()) return;
        if (ItemStack.matches(packetStack, handStack)) ci.cancel();
    }

    @Inject(method = "handleExplosion", at = @At("HEAD"))
    private void saveExplosionVelocity(ClientboundExplodePacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread() || mc.player == null) return;
        VelocityModule velocity = GuizhouWare.moduleManager.getModuleByClass(VelocityModule.class);
        if (velocity != null && velocity.isEnabled() && velocity.explosions.getValue() && velocity.phaseConditionMet()) {
            velocity$preExplosionVel = mc.player.getDeltaMovement();
        }
    }

    @Inject(method = "handleExplosion", at = @At("TAIL"))
    private void restoreExplosionVelocity(ClientboundExplodePacket packet, CallbackInfo ci) {
        if (velocity$preExplosionVel == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.setDeltaMovement(velocity$preExplosionVel);
        }
        velocity$preExplosionVel = null;
    }

    @Inject(method = "sendChat", at = @At("HEAD"), cancellable = true)
    private void sendChatMessageHook(String content, CallbackInfo ci) {
        if (EVENT_BUS.post(new ChatEvent(content))) {
            ci.cancel();
        }
    }

    @Inject(method = "handleEntityEvent", at = @At("HEAD"))
    private void handleEntityEventHook(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) return;
        if (packet.getEventId() != 35) return;
        if (Minecraft.getInstance().level == null) return;
        Entity entity = packet.getEntity(Minecraft.getInstance().level);
        if (entity instanceof LivingEntity living) {
            EVENT_BUS.post(new TotemPopEvent(living));
        }
    }
}
