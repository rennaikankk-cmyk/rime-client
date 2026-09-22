package dev.guizhou.util.elytra;

/** Descent measured from accepted movement, never a fabricated player fall distance. */
public final class MaceFlightWindow {
    private double y = Double.NaN;
    private double distance;
    private long tick = Long.MIN_VALUE;
    private boolean descending;

    public void observe(long now, double nextY, boolean grounded) {
        if (!Double.isFinite(nextY)) { reset(); return; }
        if (now < tick || tick == Long.MIN_VALUE || now - tick > 3 || !Double.isFinite(y)
                || Math.abs(nextY - y) > 64) {
            distance = 0;
            descending = false;
        } else if (grounded || nextY > y + 1e-5) {
            distance = 0;
            descending = false;
        } else if (nextY < y - 1e-5) {
            distance += y - nextY;
            descending = true;
        }
        y = nextY;
        tick = now;
    }

    public boolean ready(long now, double minimum) {
        return Double.isFinite(minimum) && descending && now >= tick && now - tick <= 2
                && distance > Math.max(1.5, minimum);
    }

    public static net.minecraft.world.phys.Vec3 approach(net.minecraft.world.phys.Vec3 offset) {
        if (offset == null || !Double.isFinite(offset.lengthSqr())) return net.minecraft.world.phys.Vec3.ZERO;
        double y = Math.min(0, offset.y);
        double axis = Math.max(Math.abs(offset.x), Math.abs(offset.z));
        if (offset.horizontalDistance() > 4 && axis > 0.1) y = Math.max(y, -axis);
        return new net.minecraft.world.phys.Vec3(offset.x, y, offset.z);
    }

    public void clearCharge() { distance = 0; descending = false; }

    public void reset() { clearCharge(); y = Double.NaN; tick = Long.MIN_VALUE; }
}
