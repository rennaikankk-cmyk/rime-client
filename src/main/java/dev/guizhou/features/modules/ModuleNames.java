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
            "AutoSword", "Aura",
            "AutoTotem", "Offhand",
            "GuangxiElytraChase", "AerialCombat",
            "GuangxiElytra", "ElytraFlight",
            "InterfaceFeedback", "界面反馈");

    /** Chinese labels for the utility modules (Map.of above caps at ten pairs). */
    private static final Map<String, String> UTILITY_NAMES = Map.ofEntries(
            Map.entry("MaceSmash", "重锤坠击"),
            Map.entry("AutoWeapon", "自动武器"),
            Map.entry("KillAura", "杀戮光环"),
            Map.entry("NewChunks", "区块时间线"),
            Map.entry("StashFinder", "物资搜索"),
            Map.entry("Supplies", "物资计数"),
            Map.entry("AntiCrystal", "防水晶占位"),
            Map.entry("AutoThrow", "智能投药"),
            Map.entry("ArmorFly", "甲飞"),
            Map.entry("SLMArmorFly", "SLM ArmorFly"),
            Map.entry("AutoReconnect", "自动重连"),
            Map.entry("HoleESP", "安全洞显示"),
            Map.entry("HoleFill", "自动堵洞"),
            Map.entry("Trajectories", "轨迹预测"),
            Map.entry("Freecam", "自由相机"),
            Map.entry("Waypoints", "路径点"),
            Map.entry("VisualRange", "视距警报"),
            Map.entry("AutoArmor", "自动穿甲"),
            Map.entry("AutoEat", "自动进食"),
            Map.entry("MiddleClickFriend", "中键好友"),
            Map.entry("ChestHistory", "容器历史"),
            Map.entry("KitReplenish", "套装补给"),
            Map.entry("AntiReplenish", "反补给"),
            Map.entry("ChunkLag", "区块延迟预警"),
            Map.entry("AntiBot", "假人过滤"),
            Map.entry("AntiLevitation", "防漂浮"),
            Map.entry("AutoCenter", "自动居中"),
            Map.entry("AutoFish", "自动钓鱼"),
            Map.entry("AutoJump", "自动跳跃"),
            Map.entry("AutoRespawn", "自动重生"),
            Map.entry("AutoTool", "自动工具"),
            Map.entry("AutoWalk", "自动前进"),
            Map.entry("ChestStealer", "容器拿取"),
            Map.entry("ItemSaver", "耐久保护"),
            Map.entry("LagNotifier", "卡顿提示"),
            Map.entry("NoSoundLag", "声音限流"),
            Map.entry("SafeWalk", "边缘保护"),
            Map.entry("ViewLock", "视角锁定"),
            Map.entry("UnfocusedFps", "后台帧率"));

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
