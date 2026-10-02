package dev.guizhou.util.player;

import static dev.guizhou.util.player.GappleSelection.Apple.*;

public final class GappleSelectionTest {
    private static int checks;
    public static void main(String[] args) {
        check(GappleSelection.choose(true, true, true, true, null) == NORMAL, "Both stocked: honor ordinary preference");
        check(GappleSelection.choose(false, true, true, true, null) == ENCHANTED, "Both stocked: honor enchanted preference");
        check(GappleSelection.choose(false, true, true, false, null) == NORMAL, "Enchanted sold out: ordinary remains usable");
        check(GappleSelection.choose(true, true, false, true, null) == ENCHANTED, "Ordinary sold out: enchanted remains usable");
        check(GappleSelection.choose(false, false, true, false, null) == ENCHANTED, "Explicit no-fallback refuses ordinary");
        check(GappleSelection.choose(true, false, false, true, null) == NORMAL, "Explicit no-fallback refuses enchanted");
        for (boolean prefer : new boolean[]{false, true}) {
            for (boolean fallback : new boolean[]{false, true}) {
                for (boolean ordinary : new boolean[]{false, true}) {
                    for (boolean enchanted : new boolean[]{false, true}) {
                        check(GappleSelection.choose(prefer, fallback, ordinary, enchanted, NORMAL) == NORMAL,
                                "Restock, settings or consumption acknowledgement cannot interrupt an ordinary meal");
                        check(GappleSelection.choose(prefer, fallback, ordinary, enchanted, ENCHANTED) == ENCHANTED,
                                "Restock, settings or consumption acknowledgement cannot interrupt an enchanted meal");
                    }
                }
            }
        }
        System.out.println("GappleSelectionTest passed: " + checks + " checks");
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
