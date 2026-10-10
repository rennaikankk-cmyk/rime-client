package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;

public final class FlightViewAnchorTest {
    private static int checks;

    public static void main(String[] args) {
        lockedCameraTurn();
        toggleAndReset();
        invalidAngles();
        System.out.println("FlightViewAnchorTest passed: " + checks + " checks");
    }

    private static void lockedCameraTurn() {
        FlightViewAnchor anchor = new FlightViewAnchor();
        anchor.update(true, 35.0F, -20.0F);
        Vec3 direction = anchor.direction();
        Vec3 strafe = wasd(anchor, 1.0, 0.0);
        Vec3 forward = wasd(anchor, 0.0, 1.0);
        for (int turn = 0; turn <= 360; turn += 45) {
            anchor.update(true, 35.0F + turn, turn % 180 - 90.0F);
            check(same(anchor.direction(), direction), "A full camera turn keeps flight direction anchored");
            check(anchor.yaw() == 35.0F, "Camera turns do not change the input yaw");
            check(same(wasd(anchor, 1.0, 0.0), strafe), "Strafe retains its anchored world direction");
            check(same(wasd(anchor, 0.0, 1.0), forward), "Forward retains its anchored world direction");
        }
    }

    private static void toggleAndReset() {
        FlightViewAnchor anchor = new FlightViewAnchor();
        check(anchor.yaw() == 0.0F && same(anchor.direction(), new Vec3(0.0, 0.0, 1.0)),
                "An unused anchor has a finite default direction");
        anchor.update(false, 10.0F, 15.0F);
        check(same(anchor.direction(), ElytraPhysics.pitchYawToRotation(15.0F, 10.0F)),
                "Unlocked flight follows the camera");
        anchor.update(false, 30.0F, -25.0F);
        check(anchor.yaw() == 30.0F, "Unlocked flight continuously updates");
        anchor.update(true, 80.0F, -35.0F);
        check(anchor.yaw() == 80.0F, "Enabling free look records the current camera");
        anchor.update(false, -70.0F, 40.0F);
        check(anchor.yaw() == -70.0F, "Disabling free look releases the old anchor");
        anchor.update(true, 120.0F, 25.0F);
        check(same(anchor.direction(), ElytraPhysics.pitchYawToRotation(25.0F, 120.0F)),
                "A later free-look session captures fresh angles");
        anchor.reset();
        anchor.update(true, -130.0F, -40.0F);
        check(same(anchor.direction(), ElytraPhysics.pitchYawToRotation(-40.0F, -130.0F)),
                "Reset allows a new world to capture angles even while free look remains on");
        anchor.update(false, 0.0F, 120.0F);
        check(anchor.direction().y < -0.9999, "Pitch clamps at ninety degrees downward");
        anchor.update(false, 0.0F, -120.0F);
        check(anchor.direction().y > 0.9999, "Pitch clamps at ninety degrees upward");
    }

    private static void invalidAngles() {
        FlightViewAnchor anchor = new FlightViewAnchor();
        anchor.update(false, 50.0F, -10.0F);
        Vec3 valid = anchor.direction();
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            anchor.update(false, invalid, 20.0F);
            check(anchor.yaw() == 50.0F && same(anchor.direction(), valid), "Invalid yaw keeps the last valid anchor");
            anchor.update(true, 90.0F, invalid);
            check(anchor.yaw() == 50.0F && same(anchor.direction(), valid), "Invalid pitch cannot partially update angles");
        }
        anchor.update(true, -80.0F, 30.0F);
        check(anchor.yaw() == -80.0F, "Invalid first samples do not lock out later valid samples");
        anchor.update(false, Float.NaN, 0.0F);
        anchor.update(true, 75.0F, -30.0F);
        check(anchor.yaw() == 75.0F, "A toggle releases the anchor even if its camera sample is invalid");
    }

    private static Vec3 wasd(FlightViewAnchor anchor, double strafe, double forward) {
        return ElytraPhysics.movementInputToVelocity(new Vec3(strafe, 0.0, forward), 1.0F, anchor.yaw());
    }

    private static boolean same(Vec3 first, Vec3 second) {
        return first.subtract(second).lengthSqr() < 1.0E-12;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
