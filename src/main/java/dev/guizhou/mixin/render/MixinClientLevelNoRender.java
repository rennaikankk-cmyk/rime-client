package dev.guizhou.mixin.render;

import dev.guizhou.features.modules.render.NoRenderModule;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21.1: ClientLevel only exposes addDestroyBlockEffect; the 1.21.11
 * breaking-block/explosion-tracker notifications do not exist here (explosion
 * particles are filtered in MixinParticleEngine instead).
 */
@Mixin(ClientLevel.class)
public class MixinClientLevelNoRender {

    @Inject(method = "addDestroyBlockEffect", at = @At("HEAD"), cancellable = true)
    private void guizhouware$noDestroyBlockEffect(BlockPos pos, BlockState state, CallbackInfo ci) {
        if (NoRenderModule.isActive(m -> m.noBlockBreak.getValue())) {
            ci.cancel();
        }
    }
}
