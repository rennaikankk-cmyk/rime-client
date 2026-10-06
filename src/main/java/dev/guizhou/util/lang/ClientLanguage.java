package dev.guizhou.util.lang;

import com.google.gson.JsonParser;
import dev.guizhou.features.gui.Labeled;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Translates client-owned presentation text only; never changes config IDs or player input. */
public final class ClientLanguage {
    public enum Language implements Labeled {
        ENGLISH, CHINESE;
        @Override public String label() { return this == ENGLISH ? "English" : "中文"; }
    }
    private static BooleanSupplier chinese = () -> true; // default audience language until ClickGui binds the saved preference
    private static final Map<String, String> ENGLISH = load();
    private static final Map<String, String> CHINESE = reverse();
    private ClientLanguage() {}

    public static void bind(BooleanSupplier preference) { chinese = preference; }
    public static boolean isChinese() { return chinese.getAsBoolean(); }
    public static String text(String source) {
        if (source == null) return "";
        return isChinese() ? CHINESE.getOrDefault(source, source) : ENGLISH.getOrDefault(source, source);
    }
    public static String settingName(String stableName) {
        if (isChinese()) return text(stableName);
        return englishSettingName(stableName);
    }
    public static String englishSettingName(String stableName) {
        String exact = ENGLISH.getOrDefault(stableName, stableName);
        if (!exact.equals(stableName)) return exact;
        for (String suffix : new String[]{" 大小 (%)", " 背景不透明度 (%)", " 背景颜色", " 文字颜色", " 强调色"}) {
            if (stableName.endsWith(suffix))
                return stableName.substring(0, stableName.length() - suffix.length()) + ENGLISH.getOrDefault(suffix, suffix);
        }
        return exact;
    }
    private static Map<String, String> load() {
        // 2026-10-06: must never crash the game from title-screen mixins; degrade to empty catalog.
        try (var stream = ClientLanguage.class.getResourceAsStream("/assets/guizhouware/lang/client_en.json")) {
            if (stream == null) {
                System.err.println("[Rime] language catalog missing; translations disabled");
                return Map.of();
            }
            var result = new HashMap<String, String>();
            JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject()
                    .entrySet().forEach(e -> result.put(e.getKey(), e.getValue().getAsString()));
            return Map.copyOf(result);
        } catch (Exception e) {
            System.err.println("[Rime] language catalog unavailable (" + e + "); translations disabled");
            return Map.of();
        }
    }
    private static Map<String, String> reverse() {
        var result = new HashMap<String, String>();
        ENGLISH.forEach((zh, en) -> result.putIfAbsent(en, zh));
        return Map.copyOf(result);
    }
}
