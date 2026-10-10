package dev.guizhou.features.modules.client;

/** Positions are fractions of the room available to move the whole card, not its top-left corner. */
public final class HudCoordinates {
    private HudCoordinates() {}

    public static double normalize(int pixel, int viewport, int size) {
        int room = Math.max(0, viewport - Math.max(0, size));
        return room == 0 ? 0.5 : Math.clamp(pixel / (double) room, 0, 1);
    }

    public static int pixel(double fraction, int viewport, int size) {
        if (!Double.isFinite(fraction)) fraction = 0.5;
        return (int) Math.round(Math.clamp(fraction, 0, 1) * Math.max(0, viewport - Math.max(0, size)));
    }

    /** Migrate old screen-relative positions at the first measured viewport, retaining the visible position. */
    public static double migrate(double oldFraction, int viewport, int size) {
        return normalize((int) Math.round(oldFraction * viewport), viewport, size);
    }
}
