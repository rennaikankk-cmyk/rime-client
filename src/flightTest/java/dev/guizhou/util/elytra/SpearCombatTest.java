package dev.guizhou.util.elytra;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class SpearCombatTest {
    public static void main(String[] args) {
        expect(false, SpearCombat.inUseWindow(7, 8, 40), "charging");
        expect(true, SpearCombat.inUseWindow(8, 8, 40), "first active tick");
        expect(true, SpearCombat.inUseWindow(48, 8, 40), "last active tick is inclusive");
        expect(false, SpearCombat.inUseWindow(49, 8, 40), "expired damage window");
        expect(false, SpearCombat.inUseWindow(8, 12, 40), "component delay differs from eight");
        expect(true, SpearCombat.inUseWindow(12, 12, 0), "zero-duration contact tick");
        expect(false, SpearCombat.inUseWindow(13, 12, 0), "zero-duration contact expired");
        expect(false, SpearCombat.inUseWindow(0, -1, 40), "invalid delay");
        expect(false, SpearCombat.inUseWindow(8, 8, -1), "invalid duration");
        expect(true, SpearCombat.inUseWindow(Integer.MAX_VALUE, 20, Integer.MAX_VALUE),
                "duration addition does not overflow");

        Vec3 eye = Vec3.ZERO;
        Vec3 ahead = new Vec3(0.0, 0.0, 1.0);
        AABB body = new AABB(-0.3, -0.3, 2.7, 0.3, 0.3, 3.3);
        expect(true, ray(eye, ahead, body, 2.0, 4.5, 0.125), "target inside spear range");
        expect(false, ray(eye, ahead.scale(-1.0), body, 2.0, 4.5, 0.125), "looking away");
        expect(false, ray(eye, ahead, body.move(0.0, 0.0, 20.0), 2.0, 4.5, 0.125),
                "finite ray rejects distant target");
        expect(false, ray(eye, ahead, body.move(0.0, 0.0, -2.0), 2.0, 4.5, 0.125),
                "minimum range leaves a near deadzone");
        expect(true, ray(eye, ahead, body.move(0.0, 0.0, -1.0), 2.0, 4.5, 0.125),
                "ray starts inside target");
        expect(false, ray(eye, ahead, body.move(0.5, 0.0, 0.0), 2.0, 4.5, 0.125),
                "narrow miss outside actual hitbox margin");
        expect(true, ray(eye, ahead, body.move(0.5, 0.0, 0.0), 2.0, 4.5, 0.3),
                "defensive margin includes near miss");
        expect(true, ray(eye, ahead.scale(5.0), body, 2.0, 4.5, 0.125),
                "direction magnitude does not change range");
        expect(false, ray(eye, ahead, body, 2.0, 2.5, 0.125), "end before target");
        expect(false, ray(eye, Vec3.ZERO, body, 2.0, 4.5, 0.125), "zero direction");
        expect(false, ray(eye, new Vec3(Double.NaN, 0.0, 1.0), body, 2.0, 4.5, 0.125),
                "nonfinite direction");
        expect(false, ray(eye, ahead, body, 5.0, 4.5, 0.125), "reversed range");
        expect(false, ray(eye, ahead, body, 2.0, Double.POSITIVE_INFINITY, 0.125), "infinite range");
        expect(false, ray(eye, ahead, body, 2.0, 4.5, -0.1), "negative margin");

        // Reproduce the measured armor-flight pose mismatch. A client aiming from
        // y+0.4 makes vanilla's y+1.62 ray pass above this standing target.
        Vec3 position = new Vec3(10.0, 150.0, 20.0);
        Vec3 glideEye = position.add(0.0, 0.4, 0.0);
        Vec3 serverEye = position.add(0.0, 1.62, 0.0);
        AABB standingTarget = new AABB(9.7, 150.0, 22.7, 10.3, 151.8, 23.3);
        Vec3 targetCenter = standingTarget.getCenter();
        expect(false, ray(serverEye, targetCenter.subtract(glideEye), standingTarget, 2.0, 4.5, 0.125),
                "local glide origin makes the actual standing-eye ray miss overhead");
        Vec3 corrected = SpearCombat.selectAttackEye(position, glideEye, 1.62, true, true);
        expect(true, corrected.equals(serverEye), "owned armor cycle uses the attack pose eye");
        expect(true, ray(serverEye, targetCenter.subtract(corrected), standingTarget, 2.0, 4.5, 0.125),
                "corrected origin crosses the actual target box");
        expect(true, SpearCombat.selectAttackEye(position, glideEye, 1.62, false, true) == glideEye,
                "ordinary elytra gliding retains its local eye");
        expect(true, SpearCombat.selectAttackEye(position, glideEye, 1.62, true, false) == glideEye,
                "non-gliding poses are not forced to standing");
        expect(true, SpearCombat.selectAttackEye(position, glideEye, 2.43, true, true)
                .equals(position.add(0.0, 2.43, 0.0)), "standing height comes from player dimensions, not a constant");
        System.out.println("SpearCombatTest: all checks passed");
    }

    private static boolean ray(Vec3 origin, Vec3 direction, AABB box,
                               double min, double max, double margin) {
        return SpearCombat.intersectsRay(origin, direction, box, min, max, margin);
    }

    private static void expect(boolean expected, boolean actual, String message) {
        if (expected != actual) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }
}
