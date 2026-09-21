package dev.guizhou.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.guizhou.event.impl.render.Render3DEvent;
import dev.guizhou.util.render.GizmoQueue;
import dev.guizhou.util.render.MatrixCapture;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static dev.guizhou.util.traits.Util.EVENT_BUS;
import static dev.guizhou.util.traits.Util.mc;

/**
 * 1.21.1: renderLevel(DeltaTracker, boolean, Camera, GameRenderer, LightTexture,
 * Matrix4f frustumMatrix, Matrix4f projectionMatrix). The 3D overlay event, gizmo
 * queue flush and placement labels run at the tail of the pass.
 */
@Mixin(LevelRenderer.class)
public class MixinWorldRenderer {

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void guizhouware$render3d(DeltaTracker tickCounter, boolean renderBlockOutline,
                                      Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
                                      Matrix4f frustumMatrix, Matrix4f projectionMatrix,
                                      CallbackInfo ci) {
        MatrixCapture.projection = new Matrix4f(projectionMatrix);
        MatrixCapture.view = new Matrix4f(frustumMatrix);

        PoseStack stack = new PoseStack();
        stack.pushPose();
        stack.mulPose(Axis.XP.rotationDegrees(mc.gameRenderer.getMainCamera().getXRot()));
        stack.mulPose(Axis.YP.rotationDegrees(mc.gameRenderer.getMainCamera().getYRot() + 180f));

        Render3DEvent event = new Render3DEvent(stack, tickCounter.getGameTimeDeltaPartialTick(true));
        dev.guizhou.util.render.placement.PlacementLabels.beginFrame();
        EVENT_BUS.post(event);
        dev.guizhou.util.render.placement.PlacementLabels.draw();
        GizmoQueue.flush(camera.getPosition());
        stack.popPose();
    }
}
