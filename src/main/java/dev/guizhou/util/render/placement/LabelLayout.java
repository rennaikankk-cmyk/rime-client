package dev.guizhou.util.render.placement;

import java.util.List;

/** Bounded screen-space allocator shared by every placement module. */
public final class LabelLayout {
    private LabelLayout() {}
    public record Rect(double x, double y, double width, double height) {
        public boolean intersects(Rect other) {
            return x < other.x + other.width + 3 && x + width + 3 > other.x
                    && y < other.y + other.height + 3 && y + height + 3 > other.y;
        }
    }
    public static Rect place(double x, double y, double width, double height,
                             int screenWidth, int screenHeight, List<Rect> occupied) {
        if (!Double.isFinite(x + y + width + height) || width <= 0 || height <= 0
                || width > screenWidth - 8 || height > screenHeight - 8) return null;
        double step = height + 5;
        double[][] offsets = {{0,0},{0,-step},{0,step},{0,-step*2},{0,step*2},
                {-width-6,0},{width+6,0},{-width-6,-step},{width+6,-step}};
        for (double[] offset : offsets) {
            Rect next = new Rect(Math.max(4, Math.min(screenWidth-width-4, x-width/2+offset[0])),
                    Math.max(4, Math.min(screenHeight-height-4, y-height/2+offset[1])), width, height);
            if (occupied.stream().noneMatch(next::intersects)) return next;
        }
        return null; // Never paint over another label when the screen is full.
    }
}
