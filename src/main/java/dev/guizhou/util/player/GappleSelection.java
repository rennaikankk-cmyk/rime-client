package dev.guizhou.util.player;

/** Inventory preference never replaces a meal already in progress. */
public final class GappleSelection {
    public enum Apple { NORMAL, ENCHANTED }

    private GappleSelection() {}

    public static Apple choose(boolean preferNormal, boolean fallback, boolean normalAvailable,
                               boolean enchantedAvailable, Apple eating) {
        if (eating != null) return eating;
        Apple preferred = preferNormal ? Apple.NORMAL : Apple.ENCHANTED;
        if (preferNormal ? normalAvailable : enchantedAvailable) return preferred;
        return fallback && (preferNormal ? enchantedAvailable : normalAvailable)
                ? preferNormal ? Apple.ENCHANTED : Apple.NORMAL : preferred;
    }
}
