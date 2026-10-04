package dev.guizhou.util.elytra;

import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/** Ordinary walking inputs, with each quantized direction checked against the actual passage. */
public final class GroundPassage {
    private GroundPassage() {}

    public static Input steer(Input real, Vec3 desired, float yaw, FlightRoutePlanner.Clearance space) {
        if (real.forward() || real.backward() || real.left() || real.right() || real.jump() || real.shift()
                || desired == null || !Double.isFinite(desired.lengthSqr()) || !Float.isFinite(yaw)
                || space == null || desired.horizontalDistanceSqr() < .14 * .14) return real;
        Vec3 aim = new Vec3(desired.x, 0, desired.z).normalize();
        double radians = Math.toRadians(yaw), best = .25;
        Input chosen = real;
        for (int forward = -1; forward <= 1; forward++) {
            for (int left = -1; left <= 1; left++) {
                if (forward == 0 && left == 0) continue;
                Vec3 direction = new Vec3(-forward * Math.sin(radians) + left * Math.cos(radians), 0,
                        forward * Math.cos(radians) + left * Math.sin(radians)).normalize();
                double score = aim.dot(direction);
                if (score <= best || !space.clear(Vec3.ZERO, direction.scale(.65))) continue;
                best = score;
                chosen = new Input(forward > 0, forward < 0, left > 0, left < 0, false, false, false);
            }
        }
        return chosen;
    }
}
