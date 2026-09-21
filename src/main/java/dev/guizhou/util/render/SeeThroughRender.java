package dev.guizhou.util.render;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.guizhou.mixin.render.CompositeStateAccessor;
import dev.guizhou.mixin.render.RenderTypeAccessor;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1.21.1 port of the see-through dual-pass. The 1.21.11 code cloned the
 * RenderSetup/pipeline of every submitted model into a GREATER-depth seed pass and a
 * LEQUAL-depth colour pass. On 1.21.1 the same trick works on RenderTypes directly:
 * clone the original {@link RenderType.CompositeState} (keeping textures, shaders,
 * transparency, lightmap/overlay, ...) via accessors and override only the depth test
 * and write mask:
 *
 * <ul>
 *   <li>seed pass — GL_GREATER depth, depth-write ON, colour-write OFF: writes depth
 *       exactly where the entity is occluded by terrain;</li>
 *   <li>colour pass — GL_LEQUAL depth, colour-write ON, depth-write OFF: visible
 *       fragments pass where the seed wrote depth, so the textured model shows
 *       through walls while the visible portion renders normally.</li>
 * </ul>
 *
 * Review item 15: seeds and colours go through SEPARATE immediate sources, each with
 * a fixed per-type builder (MultiBufferSource.immediateWithBuffers), because a single
 * shared immediate source ends the previous batch when the render type switches.
 * Frame order tracking is decoupled from the type cache: cached types are re-enqueued
 * on every frame they are used, and flush() draws all seeds before all colours.
 */
public final class SeeThroughRender {

    public static boolean active;
    public static boolean cloning;

    private static final int INITIAL_BUFFER = 262144;

    private static final Map<RenderType, RenderType> SEED_CACHE = new ConcurrentHashMap<>();
    private static final Map<RenderType, RenderType> COLOR_CACHE = new ConcurrentHashMap<>();

    /** Per-frame ordered use, independent of the caches (review item 15). */
    private static final List<RenderType> FRAME_SEEDS = new ArrayList<>();
    private static final List<RenderType> FRAME_COLORS = new ArrayList<>();

    private static MultiBufferSource.BufferSource seedBuffers;
    private static MultiBufferSource.BufferSource colorBuffers;
    private static ByteBufferBuilder seedFallback, colorFallback;
    /** Live fixed-builder registries backing the two sources above. */
    private static java.util.SequencedMap<RenderType, com.mojang.blaze3d.vertex.ByteBufferBuilder> seedFixed;
    private static java.util.SequencedMap<RenderType, com.mojang.blaze3d.vertex.ByteBufferBuilder> colorFixed;

    private static final RenderStateShard.DepthTestStateShard GREATER =
            new RenderStateShard.DepthTestStateShard("greater", GL11.GL_GREATER);
    private static final RenderStateShard.DepthTestStateShard LEQUAL =
            new RenderStateShard.DepthTestStateShard("lequal", GL11.GL_LEQUAL);
    private static final RenderStateShard.WriteMaskStateShard DEPTH_ONLY =
            new RenderStateShard.WriteMaskStateShard(false, true);
    private static final RenderStateShard.WriteMaskStateShard COLOR_ONLY =
            new RenderStateShard.WriteMaskStateShard(true, false);

    private SeeThroughRender() {}

    public static boolean isGlintType(RenderType type) {
        if (type == null) return false;
        String name = type.toString().toLowerCase(Locale.ROOT);
        return name.contains("glint") || name.contains("foil");
    }

    public static RenderType seedType(RenderType original) {
        return SEED_CACHE.computeIfAbsent(original, o -> build(o,
                "_guizhouware_seethrough_seed", GREATER, DEPTH_ONLY));
    }

    public static RenderType colorType(RenderType original) {
        return COLOR_CACHE.computeIfAbsent(original, o -> build(o,
                "_guizhouware_seethrough_color", LEQUAL, COLOR_ONLY));
    }

    private static RenderType build(RenderType original, String suffix,
                                    RenderStateShard.DepthTestStateShard depth,
                                    RenderStateShard.WriteMaskStateShard writeMask) {
        if (!(original instanceof RenderType.CompositeRenderType composite)) return original;
        try {
            RenderType.CompositeState state = ((RenderTypeAccessor) (Object) composite).guizhouware$getState();
            CompositeStateAccessor src = (CompositeStateAccessor) (Object) state;
            RenderType.CompositeState cloned = RenderType.CompositeState.builder()
                    .setTextureState(src.guizhouware$getTextureState())
                    .setShaderState(src.guizhouware$getShaderState())
                    .setTransparencyState(src.guizhouware$getTransparencyState())
                    .setDepthTestState(depth)
                    .setCullState(src.guizhouware$getCullState())
                    .setLightmapState(src.guizhouware$getLightmapState())
                    .setOverlayState(src.guizhouware$getOverlayState())
                    .setLayeringState(src.guizhouware$getLayeringState())
                    .setOutputState(src.guizhouware$getOutputState())
                    .setTexturingState(src.guizhouware$getTexturingState())
                    .setWriteMaskState(writeMask)
                    .setLineState(src.guizhouware$getLineState())
                    .setColorLogicState(src.guizhouware$getColorLogicState())
                    .createCompositeState(src.guizhouware$getOutlineProperty());
            return RenderType.create(original.toString() + suffix,
                    original.format(), original.mode(), 256, false, false, cloned);
        } catch (Throwable t) {
            PostPipelineSupport.warnThrottled("seethrough-clone", "SeeThrough clone failed for " + original, t);
            return original;
        }
    }

    /** Wraps {@code original} so every buffer write is duplicated into the seed and colour passes. */
    public static MultiBufferSource wrap(MultiBufferSource original) {
        return renderType -> {
            VertexConsumer real = original.getBuffer(renderType);
            if (!active || cloning || isGlintType(renderType)) return real;
            if (!(renderType instanceof RenderType.CompositeRenderType)) return real;
            RenderType seed = seedType(renderType);
            RenderType color = colorType(renderType);
            ensureBuilders();
            registerFixed(seedFixed, seed);
            registerFixed(colorFixed, color);
            cloning = true;
            try {
                VertexConsumer seedConsumer = seedBuffers.getBuffer(seed);
                VertexConsumer colorConsumer = colorBuffers.getBuffer(color);
                enqueueFrame(seed, color);
                return new DualVertexConsumer(new DualVertexConsumer(real, seedConsumer), colorConsumer);
            } catch (Throwable t) {
                PostPipelineSupport.warnThrottled("seethrough-buffer", "SeeThrough buffer failed", t);
                return real;
            } finally {
                cloning = false;
            }
        };
    }

    private static void ensureBuilders() {
        if (seedBuffers != null) return;
        // Review item 15: a shared fallback builder auto-ends the previous batch when
        // the render type switches, so every cloned type gets its own fixed builder
        // inside immediateWithBuffers — seed and colour consumers stay independent.
        seedFixed = new java.util.LinkedHashMap<>();
        colorFixed = new java.util.LinkedHashMap<>();
        seedFallback = new ByteBufferBuilder(8192);
        colorFallback = new ByteBufferBuilder(8192);
        seedBuffers = MultiBufferSource.immediateWithBuffers(seedFixed, seedFallback);
        colorBuffers = MultiBufferSource.immediateWithBuffers(colorFixed, colorFallback);
    }

    private static void registerFixed(java.util.SequencedMap<RenderType, com.mojang.blaze3d.vertex.ByteBufferBuilder> fixed,
                                      RenderType type) {
        // Native memory must only be allocated on a cache miss, not for every model submission.
        fixed.computeIfAbsent(type, ignored -> new ByteBufferBuilder(8192));
    }

    public static void invalidate() {
        FRAME_SEEDS.clear(); FRAME_COLORS.clear();
        if (seedFixed != null) seedFixed.values().forEach(ByteBufferBuilder::close);
        if (colorFixed != null) colorFixed.values().forEach(ByteBufferBuilder::close);
        if (seedFallback != null) seedFallback.close();
        if (colorFallback != null) colorFallback.close();
        seedBuffers = colorBuffers = null;
        seedFallback = colorFallback = null;
        seedFixed = colorFixed = null;
        SEED_CACHE.clear(); COLOR_CACHE.clear();
    }

    private static void enqueueFrame(RenderType seed, RenderType color) {
        if (!FRAME_SEEDS.contains(seed)) FRAME_SEEDS.add(seed);
        if (!FRAME_COLORS.contains(color)) FRAME_COLORS.add(color);
    }

    /** Draws pending seed passes first, then colour passes. Call on the render thread. */
    public static void flush() {
        if (seedBuffers == null || colorBuffers == null) return;
        for (RenderType type : new ArrayList<>(FRAME_SEEDS)) seedBuffers.endBatch(type);
        for (RenderType type : new ArrayList<>(FRAME_COLORS)) colorBuffers.endBatch(type);
        FRAME_SEEDS.clear();
        FRAME_COLORS.clear();
    }
}
