package dev.guizhou.util.lang;

import com.google.gson.JsonParser;
import dev.guizhou.features.commands.ui.CommandUsage;
import dev.guizhou.features.commands.ui.SyntaxErrors;
import dev.guizhou.features.modules.ModuleNames;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public final class ClientLanguageTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        check(!ClientLanguage.isChinese(), "English is the boot default");
        check(ClientLanguage.text("编辑 HUD").equals("Edit HUD"), "English menu");
        check(ClientLanguage.settingName("菜单大小 (%)").equals("Menu scale (%)"), "English setting label");
        check(ClientLanguage.settingName("TargetHud 背景颜色").equals("TargetHud background color"), "Dynamic setting label");
        check(ModuleNames.localizedDisplayName("AutoArmor", "自动穿甲").equals("AutoArmor"), "Legacy default label localized");
        check(ModuleNames.localizedDisplayName("AutoArmor", "My 自定义").equals("My 自定义"), "Custom label untouched");
        check(new CommandUsage("add <玩家>", "添加好友").render("rime ", "friend").equals("rime friend add <player>"), "English command hint");
        check(SyntaxErrors.describe(com.mojang.brigadier.exceptions.CommandSyntaxException.BUILT_IN_EXCEPTIONS.readerExpectedInt().create()).equals("Expected an integer here"), "English error");
        String custom = "玩家名 and user-entered text";
        check(ClientLanguage.text(custom).equals(custom), "Unknown content untouched");
        try (var stream = ClientLanguageTest.class.getResourceAsStream("/assets/guizhouware/lang/client_en.json")) {
            var entries = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            var format = Pattern.compile("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z]");
            check(entries.size() >= 880, "Complete source catalog");
            for (var entry : entries.entrySet()) {
                String translated = entry.getValue().getAsString();
                check(!translated.codePoints().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff), "English entry: " + entry.getKey());
                check(format.matcher(entry.getKey()).results().map(m -> m.group()).toList()
                        .equals(format.matcher(translated).results().map(m -> m.group()).toList()), "Format placeholders: " + entry.getKey());
            }
        }
        ClientLanguage.bind(() -> true);
        check(ClientLanguage.text("编辑 HUD").equals("编辑 HUD"), "Chinese restored immediately");
        check(ClientLanguage.text("Edit HUD").equals("编辑 HUD"), "Cached label follows Chinese");
        check(ModuleNames.localizedDisplayName("AutoArmor", "自动穿甲").equals("自动穿甲"), "Chinese module default");
        ClientLanguage.bind(() -> false);
        check(ClientLanguage.text("编辑 HUD").equals("Edit HUD"), "English restored immediately");
        System.out.println("ClientLanguageTest passed: " + checks + " checks");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
}
