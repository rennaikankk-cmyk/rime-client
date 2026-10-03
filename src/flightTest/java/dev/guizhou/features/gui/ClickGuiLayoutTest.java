package dev.guizhou.features.gui;

public final class ClickGuiLayoutTest {
    private static int checks;

    public static void main(String[] args) {
        for (int[] size : new int[][]{{320, 240}, {427, 240}, {480, 270}, {640, 360}, {854, 480}, {1920, 1080}}) {
            for (double requested : new double[]{.6, 1, 1.5, Double.NaN}) {
                for (int count : new int[]{1, 7, 8}) {
                    var view = ClickGuiLayout.calculate(size[0], size[1], requested, count);
                    require(view.cellHeight() >= ClickGuiLayout.HEADER + ClickGuiLayout.ROW * 3, "Three visible module rows");
                    require(view.width() - 66 - 164 >= 100, "Search and toolbar have separate space");
                    for (int i = 0; i < count; i++) {
                        float x = view.x(i), y = view.y(i);
                        require(x >= 12 && y >= 48, "Toolbar clearance");
                        require(x + ClickGuiLayout.WIDTH <= view.width() - 12 + .01, "Panel within right edge");
                        require(y + view.cellHeight() <= view.height() - 24 + .01, "Footer clearance");
                        for (int j = 0; j < i; j++)
                            require(x >= view.x(j) + ClickGuiLayout.WIDTH || view.x(j) >= x + ClickGuiLayout.WIDTH
                                    || y >= view.y(j) + view.cellHeight() || view.y(j) >= y + view.cellHeight(), "Panels cannot overlap");
                    }
                }
            }
        }
        float savedX = 700, savedY = 300;
        require(ClickGuiLayout.visibleX(savedX, 427) < savedX, "Temporarily clamp in narrow viewport");
        require(ClickGuiLayout.visibleX(savedX, 1000) == savedX, "Restore original horizontal position");
        require(ClickGuiLayout.visibleY(savedY, 240) < savedY, "Temporarily clamp in short viewport");
        require(ClickGuiLayout.visibleY(savedY, 600) == savedY, "Restore original vertical position");
        require(Float.isFinite(ClickGuiLayout.visibleX(Float.NaN, 640)), "Invalid saved X");
        require(Float.isFinite(ClickGuiLayout.visibleY(Float.POSITIVE_INFINITY, 360)), "Invalid saved Y");
        System.out.println("ClickGuiLayoutTest passed: " + checks + " checks");
    }

    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
