package dev.guizhou.mixin.network;

import dev.guizhou.features.modules.movement.ArmorFlyModule;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;

/** Runs at vanilla application on the game thread, including children of bundles.
 * Does not change network Receive dispatch, crystal spawn handling or Rusher rotation semantics. */
@Mixin(ClientPacketListener.class)
public class MixinArmorMetadata {
    @Redirect(method = "handleSetEntityData", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/syncher/SynchedEntityData;assignValues(Ljava/util/List;)V"), require = 1)
    private void applyMetadata(SynchedEntityData data, List<SynchedEntityData.DataValue<?>> values,
                               ClientboundSetEntityDataPacket packet) {
        data.assignValues(ArmorFlyModule.filterMetadata(packet.id(), values));
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void corrected(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        ArmorFlyModule.discardDelayedMetadata();
    }
    @Inject(method = "handleRespawn", at = @At("HEAD"))
    private void respawned(ClientboundRespawnPacket packet, CallbackInfo ci) {
        ArmorFlyModule.discardDelayedMetadata();
    }
}
