package dev.guizhou.features.commands.ui;

import com.mojang.brigadier.exceptions.BuiltInExceptionProvider;
import com.mojang.brigadier.exceptions.CommandExceptionType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Chinese wording and input excerpts for Brigadier parse failures. */
public final class SyntaxErrors {
    public record Snippet(String before, String error, String after) {}

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private SyntaxErrors() {}

    public static boolean isUnknownCommand(CommandSyntaxException e) {
        return e.getType() == CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand();
    }

    public static String describe(CommandSyntaxException e) {
        BuiltInExceptionProvider b = CommandSyntaxException.BUILT_IN_EXCEPTIONS;
        CommandExceptionType type = e.getType();
        String raw = e.getRawMessage() == null ? "" : e.getRawMessage().getString();
        if (type == b.dispatcherUnknownCommand()) return dev.guizhou.util.lang.ClientLanguage.text("命令不完整或不存在");
        if (type == b.dispatcherUnknownArgument()) return dev.guizhou.util.lang.ClientLanguage.text("参数不正确");
        if (type == b.dispatcherExpectedArgumentSeparator()) return dev.guizhou.util.lang.ClientLanguage.text("参数之间需要用空格分隔");
        if (type == b.dispatcherParseException()) return dev.guizhou.util.lang.ClientLanguage.text("无法解析参数");
        if (type == b.readerExpectedInt() || type == b.readerInvalidInt()
                || type == b.readerExpectedLong() || type == b.readerInvalidLong()) return dev.guizhou.util.lang.ClientLanguage.text("这里需要一个整数");
        if (type == b.readerExpectedDouble() || type == b.readerInvalidDouble()
                || type == b.readerExpectedFloat() || type == b.readerInvalidFloat()) return dev.guizhou.util.lang.ClientLanguage.text("这里需要一个数字");
        if (type == b.readerExpectedBool() || type == b.readerInvalidBool()) return dev.guizhou.util.lang.ClientLanguage.text("这里需要 true 或 false");
        if (type == b.readerExpectedStartOfQuote() || type == b.readerExpectedEndOfQuote()
                || type == b.readerInvalidEscape()) return dev.guizhou.util.lang.ClientLanguage.text("引号不完整");
        if (type == b.readerExpectedSymbol()) return dev.guizhou.util.lang.ClientLanguage.text("缺少符号");
        if (type == b.integerTooLow() || type == b.longTooLow()
                || type == b.doubleTooLow() || type == b.floatTooLow()) return bound(dev.guizhou.util.lang.ClientLanguage.text("数值不能小于 "), raw);
        if (type == b.integerTooHigh() || type == b.longTooHigh()
                || type == b.doubleTooHigh() || type == b.floatTooHigh()) return bound(dev.guizhou.util.lang.ClientLanguage.text("数值不能大于 "), raw);
        if (type == b.literalIncorrect()) return dev.guizhou.util.lang.ClientLanguage.text("无法识别的子命令");
        return raw.isBlank() ? dev.guizhou.util.lang.ClientLanguage.text("参数不正确") : raw;
    }

    /**
     * Excerpt around the failing cursor: {@code before} is at most {@code maxBefore} characters (with a
     * leading ellipsis when cut), {@code error} is the offending token, {@code after} the rest.
     */
    public static Snippet snippet(String input, int cursor, int maxBefore) {
        if (input == null || cursor < 0) return null;
        int at = Math.min(cursor, input.length());
        int end = input.indexOf(' ', at);
        if (end < 0) end = input.length();
        int from = Math.max(0, at - Math.max(0, maxBefore));
        String before = (from > 0 ? "…" : "") + input.substring(from, at);
        String after = input.substring(end);
        if (after.length() > 16) after = after.substring(0, 16) + "…";
        return new Snippet(before, input.substring(at, end), after);
    }

    public static String firstToken(String input) {
        if (input == null) return "";
        String stripped = input.strip();
        int space = stripped.indexOf(' ');
        return space < 0 ? stripped : stripped.substring(0, space);
    }

    private static String bound(String label, String raw) {
        Matcher matcher = NUMBER.matcher(raw);
        return matcher.find() ? label + matcher.group() : label.strip();
    }
}
