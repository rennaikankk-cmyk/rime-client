package dev.guizhou.features.commands.argument;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.guizhou.features.commands.CommandExceptions;

import java.awt.*;
import java.util.Collection;
import java.util.List;

public class ColorArgumentType implements ArgumentType<Color> {
    private static final List<String> EXAMPLES = List.of("hsb:55,100,100", "255,0,255", "0,255,255,80");
    private static final String[] RGBA_VALUES = {"红", "绿", "蓝", "透明度"};

    @Override
    public Color parse(StringReader reader) throws CommandSyntaxException {
        String value = reader.getRemaining();
        reader.setCursor(reader.getTotalLength());

        if (value.startsWith("#")) {
            try {
                return new Color(Integer.parseInt(value.substring(1), 16));
            } catch (NumberFormatException e) {
                throw CommandExceptions.invalidArgument("无效的十六进制颜色 %s，应为 #RRGGBB", value)
                        .createWithContext(reader);
            }
        }

        if (value.startsWith("hsb:")) {
            int[] elements = parseColorElements(reader, value.substring(4));

            int hue = elements[0];
            if (hue < 0 || hue > 360) {
                throw CommandExceptions.invalidArgument("色相必须在 0-360 之间")
                        .createWithContext(reader);
            }

            int saturation = elements[1];
            if (saturation < 0 || saturation > 100) {
                throw CommandExceptions.invalidArgument("饱和度必须在 0-100 之间")
                        .createWithContext(reader);
            }

            int brightness = elements[2];
            if (brightness < 0 || brightness > 100) {
                throw CommandExceptions.invalidArgument("亮度必须在 0-100 之间")
                        .createWithContext(reader);
            }

            return Color.getHSBColor((float) hue / 360.0f,
                    (float) saturation / 100.0f,
                    (float) brightness / 100.0f);
        }

        int[] elements = parseColorElements(reader, value);
        for (int i = 0; i < elements.length; ++i) {
            int element = elements[i];
            if (element < 0 || element > 255) {
                throw CommandExceptions.invalidArgument("%s通道必须在 0-255 之间", RGBA_VALUES[i])
                        .createWithContext(reader);
            }
        }

        return new Color(elements[0],
                elements[1],
                elements[2],
                elements.length == 4 ? elements[3] : 255);
    }

    private int[] parseColorElements(StringReader reader, String value) throws CommandSyntaxException {
        String[] parts = value.split(",");
        if (parts.length < 3 || parts.length > 4) {
            throw CommandExceptions.invalidArgument("颜色需要 3 个值（可选第 4 个透明度），格式为 r,g,b[,a] 或 hsb:h,s,b")
                    .createWithContext(reader);
        }

        int[] elements = new int[parts.length];
        for (int i = 0; i < parts.length; ++i) {
            try {
                elements[i] = Integer.parseInt(parts[i].strip());
            } catch (NumberFormatException e) {
                throw CommandExceptions.invalidArgument("颜色分量 \"%s\" 不是整数", parts[i])
                        .createWithContext(reader);
            }
        }
        return elements;
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }

    public static Color getColor(CommandContext<?> ctx, String name) {
        return ctx.getArgument(name, Color.class);
    }

    public static ColorArgumentType color() {
        return new ColorArgumentType();
    }
}
