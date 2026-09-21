package dev.guizhou.util.render.placement;


/** Layout bounds for world-anchored text; overlaps are hidden, never displaced. */
public final class LabelLayout {
    private LabelLayout() {}
    public record Rect(double x, double y, double width, double height) {
        public boolean intersects(Rect other) {
            return x < other.x + other.width + 3 && x + width + 3 > other.x
                    && y < other.y + other.height + 3 && y + height + 3 > other.y;
        }
    }
    public static double fitScale(double textWidth,double textHeight,Rect interior,double maximum) {
        if(!Double.isFinite(textWidth+textHeight+interior.width+interior.height+maximum)
                || textWidth<=0 || textHeight<=0 || interior.width<=0 || interior.height<=0 || maximum<=0) return 0;
        return Math.min(maximum,Math.min(interior.width/textWidth,interior.height/textHeight));
    }
}
