package dev.guizhou.features.modules.hud;

/** Describes observable movement without claiming knowledge of another player's client. */
final class FlightMotionLabels {
    private FlightMotionLabels() {}
    static String label(String observed) {
        if (observed == null) return "待观察";
        return switch (observed) {
            case "GROUND" -> "地面移动";
            case "GLIDE" -> "鞘翅滑翔";
            case "GLIDE ACCEL" -> "滑翔加速";
            case "ARMOR AIR" -> "胸甲空中";
            case "AIRBORNE" -> "非滑翔空中";
            default -> "待观察";
        };
    }
}
