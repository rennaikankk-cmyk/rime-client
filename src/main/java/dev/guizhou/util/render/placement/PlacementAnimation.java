package dev.guizhou.util.render.placement;

/** Clock-independent math; callers supply elapsed monotonic seconds. */
public final class PlacementAnimation {
    private PlacementAnimation() {}
    public static double blend(double seconds, double smoothness) {
        if (!Double.isFinite(seconds) || seconds <= 0) return 0;
        if (!Double.isFinite(smoothness) || smoothness <= 1) return 1;
        // Preserve the old slider's feel at 60 FPS, with the same response at every FPS.
        return -Math.expm1(Math.log1p(-1.0 / smoothness) * seconds * 60.0);
    }
    public static double alpha(double seconds, double hold, double fade) {
        if (!Double.isFinite(seconds) || !Double.isFinite(hold) || !Double.isFinite(fade)) return 0;
        if (seconds <= Math.max(0, hold)) return 1;
        return Math.max(0, Math.min(1, 1 - (seconds - Math.max(0, hold)) / Math.max(.001, fade)));
    }
}
