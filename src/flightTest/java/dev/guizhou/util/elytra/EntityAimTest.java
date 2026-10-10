package dev.guizhou.util.elytra;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class EntityAimTest {
    private static int checks;

    public static void main(String[] args) {
        slimeHop();
        mobFallback();
        facingPoint();
        diagonalBoost();
        airAndGroundStrafe();
        System.out.println("EntityAimTest passed: " + checks + " checks");
    }

    private static void slimeHop() {
        Vec3 falling = EntityAim.hopLead(new Vec3(0.4, -0.3, 0.1));
        check(falling.equals(Vec3.ZERO), "A falling slime is not led through the bounce");
        Vec3 landed = EntityAim.lead(EntityAim.Kind.SLIME, new Vec3(0.2, 0.0, 0.2), true);
        check(landed.equals(Vec3.ZERO), "Zero vertical hop motion stays on the current box");
        Vec3 rising = EntityAim.hopLead(new Vec3(0.3, 0.4, 0.0));
        check(rising.y > 0.0 && rising.y < 0.4, "A rising hop lead stays short of the apex");
        check(rising.x > 0.0 && rising.x < 0.3, "Horizontal hop lead does not reach the next landing");
    }

    private static void mobFallback() {
        Vec3 unstable = EntityAim.lead(EntityAim.Kind.MOB, new Vec3(0.8, 0.0, 0.2), false);
        check(unstable.equals(Vec3.ZERO), "Unstable mob velocity falls back to the current box");
        Vec3 stable = EntityAim.lead(EntityAim.Kind.MOB, new Vec3(0.4, 0.0, 0.0), true);
        check(stable.x > 0.0 && stable.x <= 0.4, "A stable mob lead is at most one tick");
        check(EntityAim.lead(EntityAim.Kind.PLAYER, new Vec3(1, 0, 0), true).equals(Vec3.ZERO),
                "Player lead stays on the existing tracker, not this clamp");
    }

    private static void facingPoint() {
        AABB box = new AABB(0, 0, 0, 4, 4, 4);
        Vec3 eye = new Vec3(-2, 2, 2);
        Vec3 aim = EntityAim.facingPoint(eye, box);
        check(aim.x >= 0.0 && aim.x <= 0.3, "The facing point stays on the near face of a large box");
        check(aim.y > 1.0 && aim.y < 3.0, "The facing point stays inside the box height");
    }

    private static void diagonalBoost() {
        Vec3 flat = ElytraPhysics.diagonalGzBoost(new Vec3(6, 0, 0), Vec3.ZERO);
        check(flat.y > 0.0 && flat.x > 0.0, "A flat close request becomes the diagonal pull-up");
        Vec3 vertical = ElytraPhysics.diagonalGzBoost(new Vec3(0, 6, 0), new Vec3(1, 0, 0));
        check(vertical.horizontalDistance() > 0.1 && vertical.y > 0.0, "A pure climb borrows the hint and stays diagonal");
        Vec3 already = new Vec3(3, 3, 0);
        Vec3 kept = ElytraPhysics.diagonalGzBoost(already, Vec3.ZERO);
        check(kept.distanceTo(already) < 1.0E-6, "An existing diagonal request is unchanged");
    }

    private static void airAndGroundStrafe() {
        Vec3 straight = new Vec3(3, 0, 0);
        EntityAim.Choice airStraight = EntityAim.choose(EntityAim.Kind.PLAYER, false, true, false,
                1.2, new Vec3(1, 0, 0), new Vec3(1, 0, 0), true, straight);
        check(airStraight.pattern() == EntityAim.Pattern.USE_EXISTING && airStraight.offset().x == 3.0,
                "Stable similar-speed flight keeps the single straight lead");
        EntityAim.Choice airStrafe = EntityAim.choose(EntityAim.Kind.PLAYER, false, true, false,
                1.1, new Vec3(1, 0, 0), new Vec3(-1, 0, 0), true, straight);
        check(airStrafe.pattern() == EntityAim.Pattern.AIR_STRAFE && airStrafe.offset().length() < 0.2,
                "Reversing air strafe aims at the center, not the latest tick extended");
        EntityAim.Choice speedFlip = EntityAim.choose(EntityAim.Kind.PLAYER, true, false, false,
                0.4, new Vec3(2, 0, 0), new Vec3(0.2, 0, 0), true, straight);
        check(speedFlip.pattern() == EntityAim.Pattern.BOX && speedFlip.offset().equals(Vec3.ZERO),
                "A ground player speed flip does not extend the new speed");
        EntityAim.Choice mob = EntityAim.choose(EntityAim.Kind.MOB, true, false, false,
                0.3, new Vec3(0.4, 0, 0), new Vec3(0, 0, 0.4), true, straight);
        check(mob.pattern() == EntityAim.Pattern.GROUND_STRAFE
                        && Math.abs(mob.offset().x - 0.2) < 1.0E-6 && Math.abs(mob.offset().z - 0.2) < 1.0E-6,
                "A strafing ground mob aims at the averaged heading");
        EntityAim.Choice stand = EntityAim.choose(EntityAim.Kind.MOB, true, false, true,
                0.0, Vec3.ZERO, Vec3.ZERO, true, straight);
        check(stand.pattern() == EntityAim.Pattern.BOX, "A still armor stand stays on the current box");
        EntityAim.Choice slime = EntityAim.choose(EntityAim.Kind.SLIME, false, true, false,
                1.0, new Vec3(0.3, -0.4, 0), new Vec3(-0.3, -0.4, 0), true, straight);
        check(slime.motion() == EntityAim.Motion.STRAIGHT && slime.offset().equals(Vec3.ZERO),
                "A falling slime does not enter the strafe predictor");
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
