package dev.guizhou.util.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1.21.1 adapter for the 1.21.5+ Pipelines/Layers pair. The 1.21.11 code built
 * {@code RenderPipeline}s and wrapped them in RenderTypes via RenderSetup; on 1.21.1
 * the same states are expressed directly with {@link RenderType#create} composite
 * states. Callers keep the {@code Layers.quads().draw(meshData)} shape because
 * 1.21.1 RenderType already exposes {@code draw(MeshData)}.
 */
public final class Layers {

    /** No-depth translucent colour quads (through-walls overlays and GUI gradients). */
    private static final RenderType GLOBAL_QUADS = create(false, "guizhouware_global_fill");

    /** Depth-tested translucent colour quads (placement fills that respect terrain). */
    private static final RenderType PLACEMENT_DEPTH = create(true, "guizhouware_placement_depth");

    private static final Map<Long, RenderType> LINES_BY_KEY = new ConcurrentHashMap<>();

    private Layers() {}

    private static RenderType create(boolean depthTest, String name) {
        return RenderType.create(name,
                DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS,
                1536,
                false,
                true,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(depthTest
                                ? new RenderStateShard.DepthTestStateShard("lequal", org.lwjgl.opengl.GL11.GL_LEQUAL)
                                : new RenderStateShard.DepthTestStateShard("always", org.lwjgl.opengl.GL11.GL_ALWAYS))
                        // Review item 3: the 1.21.11 placement pipelines kept depth writes off
                        // for both variants; translucent overlays must not occlude each other.
                        .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, false))
                        .setCullState(RenderStateShard.NO_CULL)
                        .createCompositeState(false));
    }

    public static RenderType quads() {
        return GLOBAL_QUADS;
    }

    public static RenderType placementQuads(boolean through) {
        return through ? GLOBAL_QUADS : PLACEMENT_DEPTH;
    }

    /**
     * Line layer mirroring the 1.21.11 line pipelines. 1.21.1 line rendering uses
     * POSITION_COLOR_NORMAL with the rendertype_lines shader; the line width is baked
     * into the layer state because this version has no per-vertex setLineWidth.
     * Review item 4: {@code through} lines test no depth (through walls), visible-only
     * lines test LEQUAL; neither writes depth.
     */
    public static RenderType lines(float width, boolean through) {
        long key = (((long) Float.floatToRawIntBits(width)) << 1) | (through ? 1L : 0L);
        return LINES_BY_KEY.computeIfAbsent(key, k -> RenderType.create(
                "guizhouware_lines_" + width + (through ? "_through" : ""),
                DefaultVertexFormat.POSITION_COLOR_NORMAL,
                VertexFormat.Mode.LINES,
                1536,
                false,
                true,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                        .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(width)))
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(through
                                ? new RenderStateShard.DepthTestStateShard("always", org.lwjgl.opengl.GL11.GL_ALWAYS)
                                : new RenderStateShard.DepthTestStateShard("lequal", org.lwjgl.opengl.GL11.GL_LEQUAL))
                        .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, false))
                        .setCullState(RenderStateShard.NO_CULL)
                        .createCompositeState(false)));
    }

    public static RenderType lines() {
        return lines(2.5f, true);
    }
}
