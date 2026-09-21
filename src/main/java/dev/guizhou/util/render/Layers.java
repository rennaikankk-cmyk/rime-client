package dev.guizhou.util.render;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

import static dev.guizhou.util.render.Pipelines.GLOBAL_LINES_PIPELINE;
import static dev.guizhou.util.render.Pipelines.GLOBAL_QUADS_PIPELINE;

public class Layers {
    private static final RenderType PLACEMENT_DEPTH = RenderType.create("placement_depth",
            RenderSetup.builder(Pipelines.PLACEMENT_DEPTH_QUADS).createRenderSetup());
    public static RenderType placementQuads(boolean through) { return through ? GLOBAL_QUADS : PLACEMENT_DEPTH; }
    private static final RenderType GLOBAL_QUADS = RenderType.create("global_fill",
            RenderSetup.builder(GLOBAL_QUADS_PIPELINE).createRenderSetup());
    private static final RenderType GLOBAL_LINES = RenderType.create("global_lines",
            RenderSetup.builder(GLOBAL_LINES_PIPELINE).createRenderSetup());

    public static RenderType quads() {
        return GLOBAL_QUADS;
    }

    public static RenderType lines() {
        return GLOBAL_LINES;
    }
}
