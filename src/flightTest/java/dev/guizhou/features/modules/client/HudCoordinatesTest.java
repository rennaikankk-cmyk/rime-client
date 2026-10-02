package dev.guizhou.features.modules.client;

public final class HudCoordinatesTest {
    public static void main(String[] args) {
        for (int originalWidth : new int[]{427, 854, 1920}) {
            for (int card : new int[]{22, 96, 204}) {
                for (int original : new int[]{0, (originalWidth - card) / 2, originalWidth - card - 4}) {
                    double anchor = HudCoordinates.normalize(original, originalWidth, card);
                    for (int changed : new int[]{240, 480, 960}) {
                        int displayed = HudCoordinates.pixel(anchor, changed, card);
                        check(displayed >= 0 && displayed + card <= changed, "resize stays inside viewport");
                        check(HudCoordinates.pixel(anchor, originalWidth, card) == original, "resize round trip restores original");
                    }
                    check(HudCoordinates.pixel(anchor, originalWidth, card * 2) >= 0, "card scaling uses saved anchor");
                    check(HudCoordinates.pixel(anchor, originalWidth, card) == original, "card-size round trip restores original");
                    double migrated = HudCoordinates.migrate(original / (double) originalWidth, originalWidth, card);
                    check(HudCoordinates.pixel(migrated, originalWidth, card) == original, "legacy position keeps visible placement");
                }
            }
        }
        double anchor = .97;
        check(HudCoordinates.pixel(anchor, 50, 100) == 0, "oversized card displays at zero");
        check(HudCoordinates.pixel(anchor, 854, 100) == 731, "oversized display does not replace saved anchor");
        check(HudCoordinates.pixel(Double.NaN, 100, 20) == 40, "invalid coordinate safely centres");
        System.out.println("HudCoordinatesTest passed: viewport, card-size, legacy and oversized round trips");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
