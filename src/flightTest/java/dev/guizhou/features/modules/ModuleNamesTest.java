package dev.guizhou.features.modules;

import java.util.Locale;
import java.util.List;

public final class ModuleNamesTest {
    public static void main(String[] args) {
        check("PacketMine".equals(ModuleNames.migrateDisplayName("GuangxiMine", "GuangxiMine")), "old default migrates");
        check("My miner".equals(ModuleNames.migrateDisplayName("GuangxiMine", "My miner")), "custom label preserved");
        check("NoSlow".equals(ModuleNames.displayName("NoSlow")), "unchanged modules retain their names");
        check(ModuleNames.matches("GuangxiMine", "My miner", "PacketMine", false), "canonical alias survives customization");
        check(ModuleNames.matches("GuangxiMine", "My miner", "GuangxiMine", false), "saved ID remains an alias");
        check(ModuleNames.matches("GuangxiMine", "My miner", "my miner", false), "custom name accepted");
        check(!ModuleNames.matches("GuangxiMine", "My miner", "packet", false), "exact lookup does not silently abbreviate");
        check(ModuleNames.matches("GuangxiMine", "My miner", "packet", true), "prefix lookup opt-in");
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            check(ModuleNames.matches("GuangxiMine", "My miner", "PACKETMINE", false), "locale independent aliases");
            check(java.util.Arrays.equals(ModuleNames.commandAliases("GuangxiMine"), new String[]{"guangximine", "packetmine"}),
                    "command aliases keep legacy and canonical literals independent of locale");
        } finally { Locale.setDefault(previous); }
        check(java.util.Arrays.equals(ModuleNames.commandAliases("AutoAnchor"), new String[]{"autoanchor", "anchoraura"}),
                "renamed modules expose standard configuration commands");
        check(java.util.Arrays.equals(ModuleNames.commandAliases("NoSlow"), new String[]{"noslow"}),
                "unchanged command literals are deduplicated");
        resolutionPrecedence();
        System.out.println("ModuleNamesTest passed: legacy IDs, custom labels and locale-independent aliases");
    }
    private record Named(String id, String display) {}
    private static void resolutionPrecedence() {
        Named autoMine = new Named("AutoMine", "PacketMine");
        Named packetMine = new Named("GuangxiMine", "My miner");
        Named renamed = new Named("Other", "GuangxiMine");
        for (var modules : List.of(List.of(autoMine, packetMine, renamed), List.of(renamed, packetMine, autoMine))) {
            check(resolve(modules, "GuangxiMine", false).value() == packetMine,
                    "saved stable ID wins over a custom label regardless of registration order");
            check(resolve(modules, "PacketMine", false).value() == packetMine,
                    "standard PacketMine alias wins over legacy AutoMine custom label");
            check(resolve(modules, "AutoMine", false).value() == autoMine, "customization keeps original ID usable");
            check(resolve(modules, "MY MINER", false).value() == packetMine, "unique custom label remains usable");
        }
        Named stableAlias = new Named("PacketMine", "Different module");
        check(resolve(List.of(packetMine, stableAlias), "PacketMine", false).value() == stableAlias,
                "exact stable ID has priority over another module's standard alias");
        Named first = new Named("First", "Shared"), second = new Named("Second", "shared");
        var duplicate = resolve(List.of(first, second), "SHARED", false);
        check(duplicate.ambiguous() && duplicate.value() == null, "duplicate custom labels never select an arbitrary module");
        check(resolve(List.of(first, second, new Named("Shared", "Unique")), "Shared", false).value().id().equals("Shared"),
                "stable ID still wins when multiple custom labels collide");
        check(resolve(List.of(packetMine), "packet", false).value() == null, "full-name command rejects prefixes");
        check(resolve(List.of(packetMine), "packet", true).value() == packetMine, "unique prefix can be requested");
        var broad = resolve(List.of(autoMine, packetMine), "packet", true);
        check(broad.ambiguous() && broad.value() == null, "ambiguous prefix requires an exact name");
        check(resolve(List.of(packetMine), "", true).value() == null, "empty input cannot select the first module");
    }
    private static ModuleNames.Match<Named> resolve(List<Named> modules, String query, boolean prefix) {
        return ModuleNames.resolve(modules, Named::id, Named::display, query, prefix);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
