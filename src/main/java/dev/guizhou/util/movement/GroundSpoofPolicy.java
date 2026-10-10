package dev.guizhou.util.movement;

/** Shared ground-bit rules. Fall protection always wins over hunger suppression. */
public final class GroundSpoofPolicy {
    private GroundSpoofPolicy() {}

    public static boolean falling(double fallDistance, double threshold, double verticalSpeed,
                                  boolean grounded) {
        return !grounded && Double.isFinite(fallDistance) && Double.isFinite(verticalSpeed)
                && fallDistance >= threshold && verticalSpeed < 0.0;
    }

    public static boolean hideGround(boolean packetGround, boolean grounded, boolean previouslyGrounded,
                                     boolean jumping, double fallDistance, double verticalSpeed,
                                     boolean mining, boolean fallProtection) {
        return !fallProtection && packetGround && grounded && previouslyGrounded && !jumping
                && fallDistance == 0.0 && Double.isFinite(verticalSpeed)
                && Math.abs(verticalSpeed) < 0.1 && !mining;
    }
}
