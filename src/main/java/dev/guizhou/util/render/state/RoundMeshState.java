package dev.guizhou.util.render.state;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2f;

/** One GUI element: groups of four vertices, drawn with the colour-quad pipeline. */
public final class RoundMeshState implements GuiElementRenderState {
    private final RenderPipeline pipeline;
    private final TextureSetup textureSetup;
    private final Matrix3x2f pose;
    private final float[] xy;
    private final int[] color;
    @Nullable private final ScreenRectangle scissorArea;
    @Nullable private final ScreenRectangle bounds;

    public RoundMeshState(RenderPipeline pipeline, Matrix3x2f pose, float[] xy, int[] color,
                          float minX, float minY, float maxX, float maxY, @Nullable ScreenRectangle scissor) {
        this.pipeline = pipeline;
        this.textureSetup = TextureSetup.noTexture();
        this.pose = pose;
        this.xy = xy;
        this.color = color;
        this.scissorArea = scissor;
        int x0 = (int) Math.floor(minX), y0 = (int) Math.floor(minY);
        int x1 = (int) Math.ceil(maxX), y1 = (int) Math.ceil(maxY);
        ScreenRectangle local = new ScreenRectangle(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0)).transformMaxBounds(pose);
        this.bounds = scissor != null ? scissor.intersection(local) : local;
    }

    @Override
    public void buildVertices(VertexConsumer vertexConsumer) {
        for (int i = 0; i < color.length; i++) {
            vertexConsumer.addVertexWith2DPose(pose, xy[i * 2], xy[i * 2 + 1]).setColor(color[i]);
        }
    }

    @Override public RenderPipeline pipeline() { return pipeline; }
    @Override public TextureSetup textureSetup() { return textureSetup; }
    @Override public @Nullable ScreenRectangle scissorArea() { return scissorArea; }
    @Override public @Nullable ScreenRectangle bounds() { return bounds; }
}
