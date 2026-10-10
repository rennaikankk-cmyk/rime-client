package dev.guizhou.features.modules.hud;

/** Describes observable movement without claiming knowledge of another player's client. */
final class FlightMotionLabels {
    private FlightMotionLabels() {}
    static String label(String observed) {
        if (observed == null) return dev.guizhou.util.lang.ClientLanguage.text("待观察");
        return switch (observed) {
            case "GROUND" -> dev.guizhou.util.lang.ClientLanguage.text("地面移动");
            case "GLIDE" -> dev.guizhou.util.lang.ClientLanguage.text("鞘翅滑翔");
            case "GLIDE ACCEL" -> dev.guizhou.util.lang.ClientLanguage.text("滑翔加速");
            case "ARMOR AIR" -> dev.guizhou.util.lang.ClientLanguage.text("胸甲空中");
            case "AIRBORNE" -> dev.guizhou.util.lang.ClientLanguage.text("非滑翔空中");
            default -> dev.guizhou.util.lang.ClientLanguage.text("待观察");
        };
    }
}
