package dev.guizhou.util.player;

public final class AutoUseWhitelistTest {
    private static int checks;

    public static void main(String[] args) {
        defaultRegexBehavior();
        invalidRegexStopsMatching();
        patternCache();
        System.out.println("AutoUseWhitelistTest passed: " + checks + " checks");
    }

    private static void defaultRegexBehavior() {
        AutoUseWhitelist whitelist = AutoUseWhitelist.of("^(.*spear|.*sword|shield)$");
        check(whitelist.valid(), "The default pattern must compile");
        check(whitelist.matches("iron_sword") && whitelist.matches("wind_spear"),
                "Item paths matching the default groups pass");
        check(whitelist.matches("shield"), "The bare shield path passes");
        check(whitelist.matches("minecraft:iron_sword") && whitelist.matches("minecraft:netherite_spear"),
                "Full ids pass through the prefixed groups");
        check(!whitelist.matches("bow") && !whitelist.matches("trident") && !whitelist.matches("mace"),
                "Items outside the whitelist stay out");
        check(!whitelist.matches(""), "Empty ids never match");
        check(!whitelist.matches(null), "Null ids never match or throw");
    }

    private static void invalidRegexStopsMatching() {
        AutoUseWhitelist broken = AutoUseWhitelist.of("([unclosed");
        check(!broken.valid(), "An invalid pattern reports invalid");
        check(!broken.matches("iron_sword") && !broken.matches("shield"),
                "An invalid pattern matches nothing");
        for (int i = 0; i < 100; i++) {
            AutoUseWhitelist again = AutoUseWhitelist.of("([unclosed");
            check(again == broken && !again.valid() && !again.matches("iron_sword"),
                    "Reusing an invalid pattern stays cached, invalid and inert");
        }
    }

    private static void patternCache() {
        AutoUseWhitelist first = AutoUseWhitelist.of("^(.*spear|.*sword|shield)$");
        check(AutoUseWhitelist.of("^(.*spear|.*sword|shield)$") == first,
                "The same regex reuses the cached instance");
        AutoUseWhitelist other = AutoUseWhitelist.of("^shield$");
        check(other != first && other.valid() && other.matches("shield") && !other.matches("iron_sword"),
                "A changed regex compiles and applies immediately");
        check(AutoUseWhitelist.of("^shield$") == other,
                "The cache follows the most recent regex");
        AutoUseWhitelist back = AutoUseWhitelist.of("^(.*spear|.*sword|shield)$");
        check(back.valid() && back.matches("iron_sword"),
                "Switching back still yields a working whitelist");
        check(AutoUseWhitelist.of(null).matches(""),
                "A null setting degrades to a compiled empty pattern instead of throwing");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
