package dev.guizhou.features.commands.argument;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.guizhou.features.commands.CommandExceptions;

public record NumberArgumentType<T extends Number>(Class<T> type, Constraint<T> constraint) implements ArgumentType<T> {
    @SuppressWarnings("unchecked")
    @Override
    public T parse(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        String input = reader.readString();

        T value = null;
        try {
            if (type.isAssignableFrom(Integer.class)) {
                value = (T) Integer.valueOf(input);
            } else if (type.isAssignableFrom(Double.class)) {
                value = (T) Double.valueOf(input);
            } else if (type.isAssignableFrom(Float.class)) {
                value = (T) Float.valueOf(input);
            } else if (type.isAssignableFrom(Long.class)) {
                value = (T) Long.valueOf(input);
            }
        } catch (NumberFormatException ignored) {
            // Reported below as a syntax error; a raw runtime exception would escape Brigadier's parser.
        }

        if (value == null) {
            reader.setCursor(start);
            throw CommandExceptions.invalidArgument(type.isAssignableFrom(Integer.class) || type.isAssignableFrom(Long.class)
                            ? "需要一个整数，但输入的是 \"%s\"" : "需要一个数字，但输入的是 \"%s\"", input)
                    .createWithContext(reader);
        }

        if (constraint != null) {
            constraint.validate(reader, value);
        }

        return value;
    }

    public static <T extends Number> Constraint<T> minMax(T min, T max) {
        return (reader, input) -> {
            double value = input.doubleValue();
            if (min.doubleValue() > value) {
                throw CommandExceptions.invalidArgument("数值不能小于 %s", min.toString())
                        .createWithContext(reader);
            }
            if (max.doubleValue() < value) {
                throw CommandExceptions.invalidArgument("数值不能大于 %s", max.toString())
                        .createWithContext(reader);
            }
        };
    }

    public static <T extends Number> T get(Class<T> type, CommandContext<?> ctx, String name) {
        return ctx.getArgument(name, type);
    }

    public static <T extends Number> NumberArgumentType<T> number(Class<T> type) {
        return number(type, null);
    }

    public static <T extends Number> NumberArgumentType<T> number(Class<T> type, Constraint<T> constraint) {
        return new NumberArgumentType<>(type, constraint);
    }

    public interface Constraint<T extends Number> {
        void validate(StringReader reader, T input) throws CommandSyntaxException;
    }
}
