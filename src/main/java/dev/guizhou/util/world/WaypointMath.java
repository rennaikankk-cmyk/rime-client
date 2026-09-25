package dev.guizhou.util.world;

import java.util.Locale;

/** Dimension coordinate conversion and per-server file naming for waypoints (no Minecraft types). */
public final class WaypointMath {
    public static final String OVERWORLD = "minecraft:overworld";
    public static final String NETHER = "minecraft:the_nether";
    public static final String END = "minecraft:the_end";

    private WaypointMath() {}

    /**
     * Horizontal position of {@code (x, z)} from dimension {@code from} as seen in {@code to}:
     * the nether is 1:8 with the overworld; any other pair has no mapping and yields null.
     */
    public static double[] convert(double x, double z, String from, String to) {
        if (from == null || to == null) return null;
        if (from.equals(to)) return new double[]{x, z};
        if (from.equals(OVERWORLD) && to.equals(NETHER)) return new double[]{x / 8.0, z / 8.0};
        if (from.equals(NETHER) && to.equals(OVERWORLD)) return new double[]{x * 8.0, z * 8.0};
        return null;
    }

    /** Stable, filesystem-safe key: "Play.Example.com:25565" and "play.example.com" share one file. */
    public static String serverKey(String address) {
        if (address == null) return "unknown";
        String key = address.trim().toLowerCase(Locale.ROOT);
        if (key.endsWith(":25565")) key = key.substring(0, key.length() - ":25565".length());
        key = key.replaceAll("[^a-z0-9._-]", "_");
        while (key.startsWith(".")) key = key.substring(1);
        if (key.length() > 64) key = key.substring(0, 64);
        return key.isEmpty() ? "unknown" : key;
    }

    public static String shortDimension(String dimension) {
        if (NETHER.equals(dimension)) return "下界";
        if (END.equals(dimension)) return "末地";
        if (OVERWORLD.equals(dimension)) return "主世界";
        int colon = dimension == null ? -1 : dimension.indexOf(':');
        return dimension == null ? "?" : colon >= 0 ? dimension.substring(colon + 1) : dimension;
    }
}
