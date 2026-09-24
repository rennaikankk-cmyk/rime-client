package dev.guizhou.features.commands.ui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Graphite + iris chat palette (RGB, no alpha). Minecraft-free so formatting logic stays testable. */
public final class ChatPalette {
    public static final int TEXT = 0xECEEF4, MUTED = 0x8A92A6, SUBTLE = 0x5D6478,
            ACCENT = 0x8B7CFF, ACCENT_2 = 0x5CC8FF, ACCENT_SOFT = 0xB9AEFF,
            SUCCESS = 0x4ADE80, WARNING = 0xFBBF24, DANGER = 0xF87171, DANGER_SOFT = 0xFCA5A5;

    /** Legacy markup names ({green}, {red}, ...) remapped onto the new palette. */
    public static final Map<String, Integer> LEGACY;

    static {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("black", 0x0F1117);
        map.put("dark_blue", 0x4656C8);
        map.put("dark_green", 0x22A35A);
        map.put("dark_aqua", 0x3A9CC9);
        map.put("dark_red", 0xDC4B4B);
        map.put("dark_purple", ACCENT);
        map.put("gold", 0xF5A524);
        map.put("gray", MUTED);
        map.put("dark_gray", SUBTLE);
        map.put("blue", 0x6D8BFF);
        map.put("green", SUCCESS);
        map.put("aqua", ACCENT_2);
        map.put("red", DANGER);
        map.put("light_purple", ACCENT_SOFT);
        map.put("yellow", WARNING);
        map.put("white", TEXT);
        map.put("reset", TEXT);
        map.put("text", TEXT);
        map.put("muted", MUTED);
        map.put("subtle", SUBTLE);
        map.put("cyan", ACCENT_2);
        map.put("ok", SUCCESS);
        map.put("warn", WARNING);
        map.put("err", DANGER);
        LEGACY = Collections.unmodifiableMap(map);
    }

    private ChatPalette() {}

    public static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int result = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            result |= Math.round(((a >>> shift) & 255) * (1 - t) + ((b >>> shift) & 255) * t) << shift;
        }
        return result;
    }
}
