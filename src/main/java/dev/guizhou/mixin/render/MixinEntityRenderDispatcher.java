package dev.guizhou.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.render.SeeThroughModule;
import dev.guizhou.util.render.SeeThroughRender;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(EntityRenderDispatcher.class)
public class MixinEntityRenderDispatcher {

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V"
        )
    )
    private void guizhouware$seeThroughSubmit(EntityRenderer renderer, EntityRenderState state, PoseStack poseStack,
                                           SubmitNodeCollector collector, CameraRenderState camera) {
        boolean apply = false;
        SeeThroughModule seeThrough = GuizhouWare.moduleManager == null
                ? null
                : GuizhouWare.moduleManager.getModuleByClass(SeeThroughModule.class);
        if (seeThrough != null && seeThrough.isEnabled() && seeThrough.shouldSeeThrough(state)) {
            apply = true;
            SeeThroughRender.begin();
        }
        try {
            renderer.submit(state, poseStack, collector, camera);
        } finally {
            if (apply) SeeThroughRender.end();
        }
    }
}
