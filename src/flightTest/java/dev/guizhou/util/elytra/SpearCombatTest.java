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
