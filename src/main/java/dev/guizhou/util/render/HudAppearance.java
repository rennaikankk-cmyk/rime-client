package dev.guizhou.util.render;

/** Render-thread scoped palette. It never changes item/skin colors or semantic warning colors. */
public record HudAppearance(int background, int foreground, int accent, float backgroundOpacity) {
    private static HudAppearance current;

    public static HudAppearance use(HudAppearance value) {
        HudAppearance previous = current;
        current = value;
        return previous;
    }

    public static int color(int original) {
        return current == null ? original : current.map(original);
    }

    public int map(int original) {
        int rgb = original & 0xFFFFFF;
        int mapped;
        float opacity = 1;
        switch (rgb) {
            case 0x171717, 0x182338 -> { mapped = background; opacity = backgroundOpacity; }
            case 0x262626, 0x25334B -> { mapped = standard() ? 0xFF000000 | rgb : mix(background, foreground, .07f); opacity = backgroundOpacity; }
            case 0x343434, 0x405471 -> { mapped = standard() ? 0xFF000000 | rgb : mix(background, foreground, .18f); opacity = backgroundOpacity; }
            case 0xFAFAFA, 0xE0E6F2 -> mapped = foreground;
            case 0xA1A1A1, 0xADB8D0 -> mapped = standard() ? 0xFF000000 | rgb : mix(background, foreground, .65f);
            case 0xE5E5E5, 0x72A8D8 -> mapped = accent;
            case 0xFFFFFF -> {
                if ((original >>> 24) != 26) return original; // TargetHUD border, never the player skin tint.
                mapped = 0xFFFFFFFF; opacity = backgroundOpacity;
            }
            default -> { return original; }
        }
        int alpha = Math.round((original >>> 24) * (mapped >>> 24) / 255f * Math.clamp(opacity, 0f, 1f));
        return alpha << 24 | mapped & 0xFFFFFF;
    }

    private boolean standard() {
        return background == 0xFF171717 && foreground == 0xFFFAFAFA
                || background == 0xFF182338 && foreground == 0xFFE0E6F2;
    }

    private static int mix(int a, int b, float t) {
        int result = a & 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8)
            result |= Math.round(((a >>> shift) & 255) * (1-t) + ((b >>> shift) & 255) * t) << shift;
        return result;
    }

    /** One scale for content and hit bounds, fitted to the viewport without changing the saved choice. */
    public static float fitScale(float requested, int w, int h, int sw, int sh) {
        if (!Float.isFinite(requested)) requested = 1;
        return Math.max(.01f, Math.min(Math.clamp(requested,.5f,2f),
                Math.min(Math.max(1,sw-4)/(float)Math.max(1,w), Math.max(1,sh-4)/(float)Math.max(1,h))));
    }
}
