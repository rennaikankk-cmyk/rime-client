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
            case 0x171717, 0x182338, 0x151820, 0x30302E, 0x262624 -> { mapped = background; opacity = backgroundOpacity; }
            case 0x262626, 0x25334B, 0x1D212C, 0x1F1E1C, 0x3A3935 -> { mapped = standard() ? 0xFF000000 | rgb : mix(background, foreground, .07f); opacity = backgroundOpacity; }
            case 0x343434, 0x405471, 0x2B3140, 0x45443F -> { mapped = standard() ? 0xFF000000 | rgb : mix(background, foreground, .18f); opacity = backgroundOpacity; }
            case 0xFAFAFA, 0xE0E6F2, 0xECEEF4, 0xFAF9F5 -> mapped = foreground;
            case 0xA1A1A1, 0xADB8D0, 0x8A92A6, 0xC2C0B6 -> mapped = standard() ? 0xFF000000 | rgb : mix(background, foreground, .65f);
            case 0x5D6478, 0x8A887F -> mapped = standard() ? 0xFF000000 | rgb : mix(background, foreground, .42f);
            case 0xE5E5E5, 0x72A8D8, 0x8B7CFF, 0xE08A6C -> mapped = accent;
            case 0x5CC8FF, 0xD97757 -> mapped = (accent & 0xFFFFFF) == 0xE08A6C || (accent & 0xFFFFFF) == 0x8B7CFF
                    ? 0xFF000000 | rgb : mix(accent, 0xFFFFFFFF, .28f);
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
                || background == 0xFF182338 && foreground == 0xFFE0E6F2
                || background == 0xFF151820 && foreground == 0xFFECEEF4
                || background == 0xFF30302E && foreground == 0xFFFAF9F5;
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
