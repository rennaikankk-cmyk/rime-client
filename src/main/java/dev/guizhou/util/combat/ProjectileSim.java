package dev.guizhou.util.combat;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic projectile integration matching vanilla 1.21 tick order, with collisions
 * delegated to a {@link Collider} so the math is testable without a world.
 *
 * <ul>
 *   <li>Throwables (pearl, snowball, egg, potion, XP bottle): gravity, then drag, then move.</li>
 *   <li>Arrows and tridents: move, then drag, then gravity.</li>
 * </ul>
 */
public final class ProjectileSim {
    public enum Order { GRAVITY_FIRST, MOVE_FIRST }

    public record Profile(double gravity, double drag, double waterDrag, Order order) {}

    public static final Profile THROWABLE = new Profile(0.03, 0.99, 0.8, Order.GRAVITY_FIRST);
    public static final Profile POTION = new Profile(0.05, 0.99, 0.8, Order.GRAVITY_FIRST);
    public static final Profile XP_BOTTLE = new Profile(0.07, 0.99, 0.8, Order.GRAVITY_FIRST);
    public static final Profile ARROW = new Profile(0.05, 0.99, 0.6, Order.MOVE_FIRST);
    public static final Profile TRIDENT = new Profile(0.05, 0.99, 0.99, Order.MOVE_FIRST);

    public interface Collider {
        /** Fraction in [0, 1] along the segment where it first hits something, or a negative value. */
        double hit(double x0, double y0, double z0, double x1, double y1, double z1);

        default boolean inWater(double x, double y, double z) { return false; }
    }

    /** {@code points} holds x,y,z triples; {@code hit} is the last point when {@code landed}. */
    public record Result(List<double[]> points, boolean landed, int ticks) {
        public double[] end() { return points.get(points.size() - 1); }
    }

    private ProjectileSim() {}

    /** Launch direction of {@code Projectile.shootFromRotation}, scaled to {@code speed}, no inaccuracy. */
    public static double[] launch(float yawDegrees, float pitchDegrees, float pitchOffset, double speed) {
        double yaw = Math.toRadians(yawDegrees), pitch = Math.toRadians(pitchDegrees);
        double x = -Math.sin(yaw) * Math.cos(pitch);
        double y = -Math.sin(Math.toRadians(pitchDegrees + pitchOffset));
        double z = Math.cos(yaw) * Math.cos(pitch);
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length < 1.0e-9) return new double[]{0, 0, 0};
        return new double[]{x / length * speed, y / length * speed, z / length * speed};
    }

    /** Vanilla {@code BowItem.getPowerForTime}: 0..1 after {@code useTicks} of drawing. */
    public static double bowPower(int useTicks) {
        double f = useTicks / 20.0;
        f = (f * f + f * 2.0) / 3.0;
        return Math.min(1.0, f);
    }

    public static Result simulate(double x, double y, double z, double vx, double vy, double vz,
                                  Profile profile, int maxTicks, double minY, Collider collider) {
        List<double[]> points = new ArrayList<>();
        points.add(new double[]{x, y, z});
        for (int tick = 1; tick <= maxTicks; tick++) {
            if (profile.order() == Order.GRAVITY_FIRST) {
                vy -= profile.gravity();
                double drag = collider.inWater(x, y, z) ? profile.waterDrag() : profile.drag();
                vx *= drag; vy *= drag; vz *= drag;
            }
            double nx = x + vx, ny = y + vy, nz = z + vz;
            double fraction = collider.hit(x, y, z, nx, ny, nz);
            if (fraction >= 0.0) {
                double f = Math.min(1.0, fraction);
                points.add(new double[]{x + (nx - x) * f, y + (ny - y) * f, z + (nz - z) * f});
                return new Result(points, true, tick);
            }
            boolean water = collider.inWater(x, y, z);
            x = nx; y = ny; z = nz;
            points.add(new double[]{x, y, z});
            if (profile.order() == Order.MOVE_FIRST) {
                double drag = water ? profile.waterDrag() : profile.drag();
                vx *= drag; vy *= drag; vz *= drag;
                vy -= profile.gravity();
            }
            if (y < minY) return new Result(points, false, tick);
        }
        return new Result(points, false, maxTicks);
    }
}
