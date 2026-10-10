package dev.guizhou.features.commands;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import dev.guizhou.features.Feature;
import dev.guizhou.features.commands.ui.ChatFeed;
import dev.guizhou.features.commands.ui.ChatTone;
import dev.guizhou.features.commands.ui.CommandCatalog;
import dev.guizhou.features.commands.ui.CommandCategory;
import dev.guizhou.features.commands.ui.CommandUsage;
import dev.guizhou.manager.CommandManager;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

public abstract class Command extends Feature {
    public static final int NO_OP = -1;
    public static final int SINGLE_FAILURE = 0;

    private final String[] aliases;
    private final List<CommandUsage> usages = new ArrayList<>();
    private String description = dev.guizhou.util.lang.ClientLanguage.text("暂无说明");
    private String summary;
    private CommandCategory category;

    public Command(String... aliases) {
        super(aliases[0]);
        this.aliases = aliases;
    }

    public abstract void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder);

    // ------------------------------------------------------------------ replies

    protected int success(String message, Object... format) {
        ChatFeed.legacy(ChatTone.SUCCESS, message, format);
        return NO_OP;
    }

    protected int success() {
        return SINGLE_SUCCESS;
    }

    protected int fail(String message, Object... format) {
        ChatFeed.legacy(ChatTone.ERROR, message, format);
        return NO_OP;
    }

    protected int fail() {
        return SINGLE_FAILURE;
    }

    protected int info(String message, Object... format) {
        ChatFeed.legacy(ChatTone.INFO, message, format);
        return NO_OP;
    }

    protected int warn(String message, Object... format) {
        ChatFeed.legacy(ChatTone.WARN, message, format);
        return NO_OP;
    }

    protected int reply(ChatTone tone, Component body) {
        ChatFeed.send(tone, body);
        return NO_OP;
    }

    /** Status line that replaces the previous reply with the same key instead of stacking. */
    protected int replyKeyed(String key, ChatTone tone, Component body) {
        ChatFeed.replace(getName() + ":" + key, tone, body);
        return NO_OP;
    }

    /** "模块 · 设置 → 新值", updated in place per setting. */
    protected int settingChanged(String module, String setting, Object value) {
        ChatFeed.settingChanged(module, setting, value);
        return NO_OP;
    }

    // ----------------------------------------------------------------- metadata

    public String[] getAliases() {
        return aliases;
    }

    protected void setDescription(String description) {
        this.description = description;
    }

    public String getDescription() {
        return dev.guizhou.util.lang.ClientLanguage.text(description);
    }

    public Command category(CommandCategory category) {
        this.category = category;
        return this;
    }

    public Command summary(String summary) {
        this.summary = summary;
        return this;
    }

    public Command usage(String syntax, String note) {
        usages.add(new CommandUsage(syntax, note));
        return this;
    }

    public CommandCategory getCategory() {
        if (category != null) return category;
        CommandCatalog.Entry entry = CommandCatalog.lookup(getName());
        return entry != null ? entry.category() : CommandCategory.OTHER;
    }

    /** One-line Chinese summary for help and completion tooltips. */
    public String getSummary() {
        if (summary != null) return dev.guizhou.util.lang.ClientLanguage.text(summary);
        CommandCatalog.Entry entry = CommandCatalog.lookup(getName());
        return dev.guizhou.util.lang.ClientLanguage.text(entry != null ? entry.summary() : description);
    }

    public List<CommandUsage> getUsages() {
        if (!usages.isEmpty()) return Collections.unmodifiableList(usages);
        CommandCatalog.Entry entry = CommandCatalog.lookup(getName());
        return entry != null ? entry.usages() : List.of();
    }

    public boolean isShown() {
        return true;
    }

    public static void sendMessage(String message, Object... format) {
        if (message == null) {
            return;
        }
        ChatFeed.legacy(ChatTone.INFO, message, format);
    }

    public static LiteralArgumentBuilder<CommandManager> literal(String literal) {
        return LiteralArgumentBuilder.literal(literal);
    }

    public static <T> RequiredArgumentBuilder<CommandManager, T> argument(String name, ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }
}
