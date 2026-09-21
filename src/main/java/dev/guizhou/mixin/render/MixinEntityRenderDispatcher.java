package dev.guizhou.mixin.render;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.render.SeeThroughModule;
import dev.guizhou.util.render.SeeThroughRender;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21.1: entity rendering flows through EntityRenderDispatcher.render(entity, x, y, z,
 * yaw, partialTick, poseStack, bufferSource, light) directly into entity renderers. The
 * see-through duplication wraps the passed MultiBufferSource for the duration of the
 * dispatch when the module selects the entity; every buffer write the renderer makes is
 * duplicated into the vanilla outline pass (drawn through walls by the outline chain).
 */
@Mixin(EntityRenderDispatcher.class)
public class MixinEntityRenderDispatcher {

    @ModifyVariable(method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private MultiBufferSource guizhouware$seeThrough(MultiBufferSource original, Entity entity) {
        SeeThroughModule seeThrough = GuizhouWare.moduleManager == null
                ? null
                : GuizhouWare.moduleManager.getModuleByClass(SeeThroughModule.class);
        if (seeThrough == null || !seeThrough.isEnabled()) return original;
        if (!seeThrough.shouldSeeThrough(entity)) return original;

        SeeThroughRender.active = true;
        return SeeThroughRender.wrap(original);
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"))
    private void guizhouware$seeThroughEnd(Entity entity, double x, double y, double z,
                                           float entityYaw, float partialTick,
                                           com.mojang.blaze3d.vertex.PoseStack poseStack,
                                           MultiBufferSource bufferSource, int packedLight,
                                           CallbackInfo ci) {
        if (SeeThroughRender.active) {
            SeeThroughRender.active = false;
            SeeThroughRender.flush();
        }
    }
}
