package dev.guizhou.util.render.placement;

import dev.guizhou.features.Feature;
import dev.guizhou.features.settings.Setting;
import java.awt.Color;

public final class PlacementVisualOptions {
    public enum Style { BOX, FILL, OUTLINE, GRADIENT, PULSE }
    public enum Occlusion { THROUGH, VISIBLE_ONLY, FADED }
    public final Setting<Style> style;
    public final Setting<Occlusion> occlusion;
    public final Setting<Boolean> info, damage, selfDamage, stage, distance, background, smooth, parts;
    public final Setting<Float> textScale, maxDistance, hold, smoothness, hiddenAlpha;
    public final Setting<Color> textColor;

    public PlacementVisualOptions(Feature owner, boolean crystal) {
        this(owner, crystal, true, false);
    }
    public PlacementVisualOptions(Feature owner, boolean crystal, boolean damageInfo, boolean partInfo) {
        style = crystal ? null : owner.mode("RenderStyle", Style.BOX).setPage("Render");
        occlusion = owner.mode("Occlusion", Occlusion.FADED).setPage("Render");
        hiddenAlpha = owner.num("OccludedAlpha", .25f, .05f, .8f).setPage("Render")
                .setVisibility(v -> occlusion.getValue() == Occlusion.FADED);
        hold = owner.num("HoldTime", .1f, 0f, 2f).setPage("Render");
        smooth = crystal ? null : owner.bool("Smooth", true).setPage("Render");
        smoothness = crystal ? null : owner.num("Smoothness", 10f, 1f, 50f).setPage("Render")
                .setVisibility(v -> smooth.getValue());
        info = owner.bool("Info", true).setPage("Render");
        damage = owner.bool("DamageText", damageInfo).setPage("Render").setVisibility(v -> damageInfo && info.getValue());
        selfDamage = owner.bool("SelfDamageText", false).setPage("Render").setVisibility(v -> damageInfo && info.getValue());
        stage = owner.bool("StageText", false).setPage("Render").setVisibility(v -> info.getValue());
        distance = owner.bool("DistanceText", false).setPage("Render").setVisibility(v -> info.getValue());
        parts = owner.bool("PartLabels", true).setPage("Render").setVisibility(v -> partInfo && info.getValue());
        textScale = owner.num("TextScale", .8f, .5f, 1.5f).setPage("Render").setVisibility(v -> info.getValue());
        maxDistance = owner.num("TextDistance", 32f, 4f, 128f).setPage("Render").setVisibility(v -> info.getValue());
        background = owner.bool("TextBackground", false).setPage("Render").setVisibility(v -> info.getValue());
        textColor = owner.color("TextColor", 240, 244, 255, 255).setPage("Render").setVisibility(v -> info.getValue());
    }
}
