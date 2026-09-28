package dev.guizhou.util.combat;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Short observed-motion forecast. Sweeps the actual pose; never reads a world off-thread. */
public final class CrystalMotionPrediction {
    public static final double SAMPLE_SPACING = 0.5;
    private static final int MAX_SAMPLES = 129;

    private CrystalMotionPrediction() {}

    /** One server tick plus estimated outbound latency, bounded by the user's horizon. */
    public static int horizon(int maximum, int latencyMs, double tickMs) {
        if (maximum <= 0) return 0;
        double tick = Double.isFinite(tickMs) ? Math.max(50, tickMs) : 50;
        return Math.min(maximum, 1 + (int)Math.ceil(Math.max(0, Math.min(2000, latencyMs)) / (2 * tick)));
    }

    public static List<Vec3> trace(Vec3 start, AABB box, Vec3 velocity, int ticks,
                                   boolean falling, Predicate<AABB> clear) {
        List<Vec3> path = new ArrayList<>();
        path.add(start);
        // Teleports/invalid deltas are not usable velocity samples.
        if (!Double.isFinite(velocity.lengthSqr()) || velocity.lengthSqr() > 32 * 32
                || ticks <= 0 || !clear.test(box)) return path;
        Vec3 position = start;
        Vec3 motion = velocity;
        for (int tick = 0; tick < Math.min(20, ticks); tick++) {
            int steps = Math.max(1, (int)Math.ceil(motion.length() / SAMPLE_SPACING));
            Vec3 step = motion.scale(1.0 / steps);
            if (step.lengthSqr() < 1.0e-10) break;
            for (int i = 0; i < steps; i++) {
                AABB at = box.move(position.subtract(start));
                // Sweeping the small segment also catches thin walls between sample endpoints.
                if (!clear.test(at.expandTowards(step))) return path;
                position = position.add(step);
                path.add(position);
                if (path.size() >= MAX_SAMPLES) return path;
            }
            if (falling) motion = new Vec3(motion.x * 0.91, (motion.y - 0.08) * 0.98, motion.z * 0.91);
        }
        return path;
    }
}
