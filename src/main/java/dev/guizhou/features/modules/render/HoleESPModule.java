package dev.guizhou.features.modules.render;

import dev.guizhou.event.impl.render.Render3DEvent;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.render.RenderUtil;
import dev.guizhou.util.world.HoleClassifier;
import dev.guizhou.util.world.HoleScanner;
import net.minecraft.world.phys.AABB;

import java.awt.Color;
import java.util.List;

/** Highlights blast-safe holes (bedrock, obsidian and double) around you. */
public class HoleESPModule extends Module {
    private final Setting<Integer> range = num("Range", 8, 2, 16).setPage("General");
    private final Setting<Integer> verticalRange = num("VerticalRange", 4, 1, 10).setPage("General");
    private final Setting<Boolean> doubles = bool("Double", true).setPage("General");
    private final Setting<Integer> interval = num("IntervalTicks", 5, 1, 20).setPage("General");

    private final Setting<Double> height = num("Height", 0.1, 0.0, 1.0).setPage("Render");
    private final Setting<Boolean> fill = bool("Fill", true).setPage("Render");
    private final Setting<Boolean> outline = bool("Outline", true).setPage("Render");
    private final Setting<Float> lineWidth = num("LineWidth", 1.5f, 0.5f, 4.0f).setPage("Render");
    private final Setting<Color> bedrockColor = color("BedrockColor", 155, 191, 166, 70).setPage("Render");
    private final Setting<Color> obsidianColor = color("ObsidianColor", 227, 154, 146, 70).setPage("Render");
    private final Setting<Color> doubleColor = color("DoubleColor", 224, 177, 92, 70).setPage("Render");

    private List<HoleClassifier.Hole> holes = List.of();
    private int ticks;

    public HoleESPModule() {
        super("HoleESP", "Highlights crystal-safe holes: green bedrock, red obsidian, yellow double holes.", Category.RENDER);
    }

    @Override
    public void onEnable() {
        ticks = 0;
        holes = List.of();
    }

    @Override
    public void onDisable() {
        holes = List.of();
    }

    @Override
    public void onTick() {
        if (nullCheck()) {
            holes = List.of();
            return;
        }
        if (ticks++ % interval.getValue() != 0) return;
        holes = HoleScanner.scan(mc.player.blockPosition(), range.getValue(), verticalRange.getValue(), doubles.getValue());
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (nullCheck() || holes.isEmpty()) return;
        double h = Math.max(0.002, height.getValue());
        for (HoleClassifier.Hole hole : holes) {
            Color base = switch (hole.type()) {
                case BEDROCK -> bedrockColor.getValue();
                case OBSIDIAN -> obsidianColor.getValue();
                case DOUBLE -> doubleColor.getValue();
            };
            AABB box = HoleScanner.box(hole, h);
            if (fill.getValue()) RenderUtil.drawBoxFilled(event.getMatrix(), box, base);
            if (outline.getValue()) {
                RenderUtil.drawBox(event.getMatrix(), box,
                        new Color(base.getRed(), base.getGreen(), base.getBlue(), Math.min(255, base.getAlpha() * 3)),
                        lineWidth.getValue());
            }
        }
    }

    @Override
    public String getDisplayInfo() {
        return holes.isEmpty() ? null : String.valueOf(holes.size());
    }
}
