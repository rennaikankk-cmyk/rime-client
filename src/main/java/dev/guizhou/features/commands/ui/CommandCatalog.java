package dev.guizhou.features.commands.ui;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Chinese metadata for commands whose classes do not declare their own (commands shared with other
 * work streams). A command's own {@code category/summary/usage} declarations always take precedence.
 */
public final class CommandCatalog {
    public record Entry(CommandCategory category, String summary, List<CommandUsage> usages) {}

    private static final Map<String, Entry> ENTRIES = Map.of(
            "friend", new Entry(CommandCategory.SOCIAL, "管理好友列表", List.of(
                    new CommandUsage("list", "查看好友"),
                    new CommandUsage("add <玩家>", "添加好友"),
                    new CommandUsage("remove <好友>", "移除好友"),
                    new CommandUsage("clear", "清空好友列表"),
                    new CommandUsage("radius add <格数>", "把半径内的玩家加为好友"),
                    new CommandUsage("temp <天数> add <玩家>", "添加限时好友"),
                    new CommandUsage("temp <天数> radius add <格数>", "把半径内的玩家加为限时好友"))),
            "prefix", new Entry(CommandCategory.CONFIG, "修改命令前缀", List.of(
                    new CommandUsage("<前缀>", "例如 prefix ! 之后用 !help"))));

    private CommandCatalog() {}

    public static Entry lookup(String name) {
        return name == null ? null : ENTRIES.get(name.toLowerCase(Locale.ROOT));
    }
}
