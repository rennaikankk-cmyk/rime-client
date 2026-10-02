package dev.guizhou.features.commands.ui;

import com.google.common.collect.Iterables;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.tree.CommandNode;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.commands.ModuleCommand;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.ModuleNames;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.CommandManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds help pages, per-command usage cards and module setting listings. */
public final class CommandHelp {
    public static final int PER_PAGE = 8;
    private static final int MAX_SETTINGS = 24;

    private CommandHelp() {}

    // ------------------------------------------------------------------- listing

    public static void page(CommandManager manager, int requested) {
        List<Command> visible = HelpPager.grouped(
                manager.getCommands().stream().filter(Command::isShown).toList(),
                Command::getCategory, Command::getName);
        HelpPager.Page<Command> page = HelpPager.page(visible, requested, PER_PAGE);
        String prefix = manager.getCommandPrefix();

        MutableComponent body = Component.empty()
                .append(ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text("命令列表"), ChatPalette.TEXT))
                .append(ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text("   第 ") + page.page() + "/" + page.pages() + dev.guizhou.util.lang.ClientLanguage.text(" 页 · 共 ") + page.total() + dev.guizhou.util.lang.ClientLanguage.text(" 条"),
                        ChatPalette.SUBTLE));

        CommandCategory current = null;
        for (Command command : page.items()) {
            if (command.getCategory() != current) {
                current = command.getCategory();
                body.append("\n").append(ChatFeed.text("  ▍", current.color()))
                        .append(ChatFeed.text(current.label(), ChatPalette.MUTED));
            }
            body.append("\n    ")
                    .append(ChatFeed.suggest(prefix + command.getName(), prefix + command.getName() + " ",
                            hoverCard(command, prefix), ChatPalette.ACCENT_2))
                    .append(ChatFeed.text("  " + command.getSummary(), ChatPalette.MUTED));
        }

        body.append("\n  ");
        body.append(page.hasPrevious()
                ? ChatFeed.suggest(dev.guizhou.util.lang.ClientLanguage.text("‹ 上一页"), prefix + "help " + (page.page() - 1), null, ChatPalette.ACCENT_2)
                : ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text("‹ 上一页"), ChatPalette.SUBTLE));
        body.append(ChatFeed.text("   ", ChatPalette.SUBTLE));
        body.append(page.hasNext()
                ? ChatFeed.suggest(dev.guizhou.util.lang.ClientLanguage.text("下一页 ›"), prefix + "help " + (page.page() + 1), null, ChatPalette.ACCENT_2)
                : ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text("下一页 ›"), ChatPalette.SUBTLE));
        body.append(ChatFeed.text("    " + prefix + dev.guizhou.util.lang.ClientLanguage.text("help <命令> 查看用法 · ") + prefix + dev.guizhou.util.lang.ClientLanguage.text("<模块> <设置> <值> 改设置"),
                ChatPalette.SUBTLE));
        ChatFeed.replace("help", ChatTone.INFO, "☰", body);
    }

    // ------------------------------------------------------------------- details

    public static void detail(CommandManager manager, Command command) {
        String prefix = manager.getCommandPrefix();
        Module module = moduleFor(command);
        if (module != null) {
            ChatFeed.replace("help", ChatTone.INFO, "☰", moduleCard(prefix, command, module));
            return;
        }

        MutableComponent body = Component.empty()
                .append(ChatFeed.text(prefix + command.getName(), ChatPalette.ACCENT_2))
                .append(ChatFeed.text("  ·  " + command.getCategory().label(), command.getCategory().color()))
                .append("\n  ").append(ChatFeed.text(command.getSummary(), ChatPalette.TEXT));
        if (command.getAliases().length > 1) {
            body.append("\n  ").append(ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text("别名  "), ChatPalette.SUBTLE))
                    .append(ChatFeed.text(String.join(" · ", Arrays.copyOfRange(command.getAliases(), 1,
                            command.getAliases().length)), ChatPalette.MUTED));
        }
        body.append("\n  ").append(ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text("用法"), ChatPalette.SUBTLE));
        appendUsages(body, manager, command, Integer.MAX_VALUE);
        ChatFeed.replace("help", ChatTone.INFO, "☰", body);
    }

    /** Usage lines for a known command after a failed parse; returns the number of lines added. */
    public static int appendUsages(MutableComponent body, CommandManager manager, Command command, int limit) {
        String prefix = manager.getCommandPrefix();
        Module module = moduleFor(command);
        if (module != null) {
            body.append("\n    ").append(ChatFeed.suggest(prefix + command.getName() + dev.guizhou.util.lang.ClientLanguage.text(" <设置> <值>"),
                    prefix + command.getName() + " ", null, ChatPalette.ACCENT_2));
            body.append(ChatFeed.text("  " + prefix + "help " + command.getName() + dev.guizhou.util.lang.ClientLanguage.text(" 列出全部设置"),
                    ChatPalette.SUBTLE));
            return 1;
        }

        int added = 0;
        List<CommandUsage> usages = command.getUsages();
        if (!usages.isEmpty()) {
            for (CommandUsage usage : usages) {
                if (added >= limit) break;
                String fill = prefix + command.getName() + (usage.fill().isEmpty() ? " " : " " + usage.fill());
                body.append("\n    ")
                        .append(ChatFeed.suggest(usage.render(prefix, command.getName()), fill, null, ChatPalette.ACCENT_2))
                        .append(ChatFeed.text(usage.note().isEmpty() ? "" : "  " + usage.note(), ChatPalette.MUTED));
                added++;
            }
        } else {
            for (String usage : smartUsages(manager, command)) {
                if (added >= limit) break;
                String line = usage.isEmpty() ? prefix + command.getName() : prefix + command.getName() + " " + usage;
                body.append("\n    ").append(ChatFeed.suggest(line, prefix + command.getName() + " ", null,
                        ChatPalette.ACCENT_2));
                added++;
            }
        }
        if (added < Math.max(usages.size(), 0)) {
            body.append("\n    ").append(ChatFeed.suggest("… " + prefix + "help " + command.getName(),
                    prefix + "help " + command.getName(), null, ChatPalette.SUBTLE));
        }
        return added;
    }

    public static Component hoverCard(Command command, String prefix) {
        MutableComponent card = Component.empty()
                .append(ChatFeed.text(prefix + command.getName(), ChatPalette.ACCENT_2));
        if (command.getAliases().length > 1) {
            card.append(ChatFeed.text("  " + String.join(" · ",
                    Arrays.copyOfRange(command.getAliases(), 1, command.getAliases().length)), ChatPalette.SUBTLE));
        }
        card.append("\n").append(ChatFeed.text(command.getSummary(), ChatPalette.TEXT));
        int shown = 0;
        for (CommandUsage usage : command.getUsages()) {
            if (shown++ >= 6) break;
            card.append("\n").append(ChatFeed.text(usage.render(prefix, command.getName()), ChatPalette.MUTED));
            if (!usage.note().isEmpty()) card.append(ChatFeed.text("  " + usage.note(), ChatPalette.SUBTLE));
        }
        return card;
    }

    // -------------------------------------------------------------- module help

    public static Module moduleFor(Command command) {
        if (!(command instanceof ModuleCommand) || GuizhouWare.moduleManager == null) return null;
        List<Module> modules = GuizhouWare.moduleManager.getModules();
        Map<String, Module> index = moduleIndex;
        if (index == null || indexedModules != modules.size()) {
            Map<String, Module> rebuilt = new HashMap<>();
            for (Module module : modules) {
                for (String alias : ModuleNames.commandAliases(module.getName())) rebuilt.putIfAbsent(alias, module);
            }
            moduleIndex = index = rebuilt;
            indexedModules = modules.size();
        }
        return index.get(command.getName());
    }

    private static volatile Map<String, Module> moduleIndex;
    private static volatile int indexedModules = -1;

    public static String moduleSummary(Module module) {
        return dev.guizhou.util.lang.ClientLanguage.text("配置模块 ") + module.getDisplayName() + " · " + (module.isEnabled() ? dev.guizhou.util.lang.ClientLanguage.text("已开启") : dev.guizhou.util.lang.ClientLanguage.text("未开启"));
    }

    private static MutableComponent moduleCard(String prefix, Command command, Module module) {
        MutableComponent body = Component.empty()
                .append(ChatFeed.text(module.getDisplayName(), ChatPalette.TEXT))
                .append(ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text("  ·  模块设置  ·  "), ChatPalette.SUBTLE))
                .append(ChatFeed.text(module.isEnabled() ? dev.guizhou.util.lang.ClientLanguage.text("已开启") : dev.guizhou.util.lang.ClientLanguage.text("未开启"),
                        module.isEnabled() ? ChatPalette.SUCCESS : ChatPalette.MUTED))
                .append("\n  ").append(ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text("用法  ") + prefix + command.getName() + dev.guizhou.util.lang.ClientLanguage.text(" <设置> <值>"),
                        ChatPalette.SUBTLE));
        int shown = 0;
        List<Setting<?>> settings = configurable(module);
        for (Setting<?> setting : settings) {
            if (shown++ >= MAX_SETTINGS) break;
            String literal = settingLiteral(setting);
            body.append("\n    ")
                    .append(ChatFeed.suggest(literal, prefix + command.getName() + " " + literal + " ",
                            ChatFeed.text(typeHint(setting), ChatPalette.MUTED), ChatPalette.ACCENT_2))
                    .append(ChatFeed.text(" = ", ChatPalette.SUBTLE))
                    .append(ChatFeed.text(formatValue(setting.getValue()), ChatPalette.TEXT));
        }
        if (settings.size() > MAX_SETTINGS) {
            body.append("\n    ").append(ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text("… 还有 ") + (settings.size() - MAX_SETTINGS)
                    + dev.guizhou.util.lang.ClientLanguage.text(" 项，输入 ") + prefix + command.getName() + dev.guizhou.util.lang.ClientLanguage.text(" 后按 Tab 浏览"), ChatPalette.SUBTLE));
        }
        if (settings.isEmpty()) body.append("\n    ").append(ChatFeed.text(dev.guizhou.util.lang.ClientLanguage.text("此模块没有可通过命令修改的设置"), ChatPalette.MUTED));
        return body;
    }

    /** Must match the literal ModuleCommand registers for the setting. */
    public static String settingLiteral(Setting<?> setting) {
        return dev.guizhou.util.lang.ClientLanguage.englishSettingName(setting.getName()).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    public static List<Setting<?>> configurable(Module module) {
        List<Setting<?>> out = new ArrayList<>();
        for (Setting<?> setting : module.getSettings()) {
            Object value = setting.getDefaultValue();
            if (value instanceof Boolean || value instanceof Number || value instanceof Enum<?>
                    || value instanceof String || value instanceof Color) out.add(setting);
        }
        return out;
    }

    public static String formatValue(Object value) {
        if (value instanceof Color color) return String.format(Locale.ROOT, "#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
        if (value instanceof Boolean bool) return bool ? dev.guizhou.util.lang.ClientLanguage.text("开") : dev.guizhou.util.lang.ClientLanguage.text("关");
        if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            return number == Math.rint(number) ? String.valueOf((long) number) : String.format(Locale.ROOT, "%.2f", number)
                    .replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return String.valueOf(value);
    }

    public static String typeHint(Setting<?> setting) {
        Object value = setting.getDefaultValue();
        if (value instanceof Boolean) return "true / false";
        if (value instanceof Number) return dev.guizhou.util.lang.ClientLanguage.text("数值 ") + setting.getMin() + " ~ " + setting.getMax();
        if (value instanceof Enum<?> e) {
            StringBuilder options = new StringBuilder(dev.guizhou.util.lang.ClientLanguage.text("可选 "));
            Object[] constants = e.getDeclaringClass().getEnumConstants();
            for (int i = 0; i < constants.length; i++) {
                if (i > 0) options.append(" / ");
                options.append(constants[i].toString().toLowerCase(Locale.ROOT));
            }
            return options.toString();
        }
        if (value instanceof Color) return dev.guizhou.util.lang.ClientLanguage.text("颜色：#RRGGBB、r,g,b[,a] 或 hsb:h,s,b");
        return dev.guizhou.util.lang.ClientLanguage.text("文本");
    }

    // --------------------------------------------------------------- internals

    private static List<String> smartUsages(CommandManager manager, Command command) {
        try {
            ParseResults<CommandManager> results = manager.getDispatcher().parse(command.getName(), manager);
            if (results.getContext().getNodes().isEmpty()) return List.of();
            CommandNode<CommandManager> node = Iterables.getLast(results.getContext().getNodes()).getNode();
            Map<CommandNode<CommandManager>, String> usages = manager.getDispatcher().getSmartUsage(node, manager);
            List<String> out = new ArrayList<>(usages.values());
            if (node.getCommand() != null) out.add(0, "");
            return out;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }
}
