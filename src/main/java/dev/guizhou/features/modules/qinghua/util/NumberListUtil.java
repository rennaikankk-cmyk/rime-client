package dev.guizhou.features.modules.qinghua.util;

import java.util.ArrayList;
import java.util.List;

/** Parse comma / newline / ||| delimited number or string lists from Setting&lt;String&gt;. */
public final class NumberListUtil {
    private NumberListUtil() {}

    public static List<String> parseStrings(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        String normalized = raw.replace("|||", "\n").replace(',', '\n');
        for (String line : normalized.split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public static List<Double> parseDoubles(String raw) {
        List<Double> out = new ArrayList<>();
        for (String part : parseStrings(raw)) {
            try {
                out.add(Double.parseDouble(part.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public static double[] parseDoubleArray(String raw) {
        List<Double> list = parseDoubles(raw);
        double[] arr = new double[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    public static List<String> parseStrings(List<String> list) {
        return list == null ? List.of() : list;
    }

    public static double[] parseDoubleArray(List<String> list) {
        if (list == null || list.isEmpty()) return new double[0];
        List<Double> tmp = new ArrayList<>();
        for (String s : list) {
            try {
                tmp.add(Double.parseDouble(s.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        double[] arr = new double[tmp.size()];
        for (int i = 0; i < tmp.size(); i++) arr[i] = tmp.get(i);
        return arr;
    }
}
