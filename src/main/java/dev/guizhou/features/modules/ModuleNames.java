package dev.guizhou.features.modules;

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** User-facing names can evolve without changing saved module IDs or telemetry keys. */
public final class ModuleNames {
    private static final Map<String, String> NAMES = Map.of(
            "GuangxiCrystal", "CrystalAura",
            "GuangxiPiston", "PistonCrystal",
            "GuangxiBed", "AutoBed",
            "GuangxiMine", "PacketMine",
            "AutoAnchor", "AnchorAura",
            "GuangxiElytraChase", "AerialCombat",
            "GuangxiElytra", "ElytraFlight",
            "InterfaceFeedback", "界面反馈");

    /** Chinese labels for the utility modules (Map.of above caps at ten pairs). */
    private static final Map<String, String> UTILITY_NAMES = Map.ofEntries(
            Map.entry("AntiCrystal", "防水晶占位"),
            Map.entry("AutoThrow", "智能投药"),
            Map.entry("ArmorFly", "甲飞"),
            Map.entry("AutoReconnect", "自动重连"),
            Map.entry("HoleESP", "安全洞显示"),
            Map.entry("HoleFill", "自动堵洞"),
            Map.entry("Trajectories", "轨迹预测"),
            Map.entry("Freecam", "自由相机"),
            Map.entry("Waypoints", "路径点"),
            Map.entry("VisualRange", "视距警报"),
            Map.entry("AutoArmor", "自动穿甲"),
            Map.entry("AutoEat", "自动进食"),
            Map.entry("MiddleClickFriend", "中键好友"));

    private ModuleNames() {}

    public static String displayName(String stableId) {
        return NAMES.getOrDefault(stableId, UTILITY_NAMES.getOrDefault(stableId, stableId));
    }

    public static String migrateDisplayName(String stableId, String saved) {
        return saved == null || saved.equals(stableId) ? displayName(stableId) : saved;
    }

    /** Translate only standard names; never rewrite a user-defined label. */
    public static String localizedDisplayName(String stableId, String saved) {
        String canonical = displayName(stableId);
        if (saved == null || saved.equals(stableId) || saved.equals(canonical))
            return dev.guizhou.util.lang.ClientLanguage.text(canonical);
        return saved;
    }

    /** Command literals stay fixed even when the user customizes a visible label. */
    public static String[] commandAliases(String stableId) {
        String original = stableId.toLowerCase(Locale.ROOT);
        String canonical = displayName(stableId).toLowerCase(Locale.ROOT);
        return original.equals(canonical) ? new String[]{original} : new String[]{original, canonical};
    }

    public static boolean matches(String stableId, String display, String query, boolean prefix) {
        String q = query.toLowerCase(Locale.ROOT);
        return match(stableId, q, prefix) || match(displayName(stableId), q, prefix)
                || display != null && match(display, q, prefix);
    }

    public record Match<T>(T value, boolean ambiguous) {}

    /** Stable IDs and standard aliases cannot be shadowed by old or user-edited display names. */
    public static <T> Match<T> resolve(Iterable<T> candidates, Function<T, String> stableId,
                                     Function<T, String> display, String query, boolean allowPrefix) {
        if (query == null || query.isBlank()) return new Match<>(null, false);
        String normalized = query.toLowerCase(Locale.ROOT);
        T found = null;
        int bestRank = 0;
        boolean ambiguous = false;
        for (T candidate : candidates) {
            String id = stableId.apply(candidate);
            String label = display.apply(candidate);
            int rank = match(id, normalized, false) ? 3 : match(displayName(id), normalized, false) ? 2
                    : label != null && match(label, normalized, false) ? 1 : 0;
            if (rank > bestRank) {
                bestRank = rank;
                found = candidate;
                ambiguous = false;
            } else if (rank > 0 && rank == bestRank && found != candidate) {
                ambiguous = true;
            }
        }
        if (bestRank > 0) return new Match<>(ambiguous ? null : found, ambiguous);
        if (!allowPrefix) return new Match<>(null, false);
        for (T candidate : candidates) {
            if (!matches(stableId.apply(candidate), display.apply(candidate), normalized, true)) continue;
            if (found != null && found != candidate) return new Match<>(null, true);
            found = candidate;
        }
        return new Match<>(found, false);
    }

    private static boolean match(String name, String query, boolean prefix) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return prefix ? normalized.startsWith(query) : normalized.equals(query);
    }
}
