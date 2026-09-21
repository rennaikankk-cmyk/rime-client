package dev.guizhou.mixin.render;

import dev.guizhou.util.render.SeeThroughParticle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Review 17b: the 1.21.1 ParticleEngine iterates its fixed RENDER_ORDER list, so a
 * custom ParticleRenderType returned by TotemParticle would never render. Append the
 * see-through sheet once to the render order when the engine is constructed.
 */
@Mixin(ParticleEngine.class)
public abstract class MixinParticleEngineRenderOrder {

    @Shadow
    @Final
    @org.spongepowered.asm.mixin.Mutable
    private static List<ParticleRenderType> RENDER_ORDER;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void guizhouware$registerCustomRenderOrder(CallbackInfo ci) {
        if (!RENDER_ORDER.contains(SeeThroughParticle.SEE_THROUGH_PARTICLE_SHEET)) {
            // Vanilla creates an ImmutableList; never mutate that shared instance.
            var order = new java.util.ArrayList<>(RENDER_ORDER);
            order.add(SeeThroughParticle.SEE_THROUGH_PARTICLE_SHEET);
            RENDER_ORDER = List.copyOf(order);
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void guizhouware$restoreParticleDepth(CallbackInfo ci) {
        // Vanilla restores depth writes and blend, but not the depth-test enable bit.
        SeeThroughParticle.finishPass();
    }
}
