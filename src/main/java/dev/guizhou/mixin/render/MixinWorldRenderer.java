package dev.guizhou.mixin.render;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.guizhou.event.impl.render.Render3DEvent;
import dev.guizhou.util.render.MatrixCapture;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static dev.guizhou.util.traits.Util.EVENT_BUS;
import static dev.guizhou.util.traits.Util.mc;

@Mixin(LevelRenderer.class)
public class MixinWorldRenderer {
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void render(GraphicsResourceAllocator allocator, DeltaTracker tickCounter, boolean renderBlockOutline,
                        Camera camera, Matrix4f positionMatrix, Matrix4f matrix4f, Matrix4f projectionMatrix,
                        GpuBufferSlice fogBuffer, Vector4f fogColor, boolean renderSky, CallbackInfo ci, @Local ProfilerFiller profiler) {

        MatrixCapture.projection = new Matrix4f(projectionMatrix);
        MatrixCapture.view = new Matrix4f(positionMatrix);

        PoseStack stack = new PoseStack();
        stack.pushPose();
        stack.mulPose(Axis.XP.rotationDegrees(mc.gameRenderer.getMainCamera().xRot()));
        stack.mulPose(Axis.YP.rotationDegrees(mc.gameRenderer.getMainCamera().yRot() + 180f));

        profiler.push("guizhouware-render-3d");

        Render3DEvent event = new Render3DEvent(stack, tickCounter.getGameTimeDeltaPartialTick(true));
        dev.guizhou.util.render.placement.PlacementLabels.beginFrame();
        EVENT_BUS.post(event);
        dev.guizhou.util.render.placement.PlacementLabels.draw();
        stack.popPose();
        profiler.pop();
    }
}
