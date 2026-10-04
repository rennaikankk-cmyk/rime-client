package dev.guizhou.util.elytra;

import java.util.ArrayDeque;
import net.minecraft.world.phys.Vec3;

/** A bounded memory of traversed passages, never permission to cross a new obstacle. */
public final class FlightEscapeTrail {
    public static final int MAX_POINTS = 96;
    private final ArrayDeque<Vec3> trail = new ArrayDeque<>();
    private long previousTick = Long.MIN_VALUE;
    private Vec3 previous;
    private Vec3 started;
    private int blockedTicks;
    private boolean active;

    public boolean active() { return active; }
    int size() { return trail.size(); }

    public void reset() {
        trail.clear(); previous = started = null; previousTick = Long.MIN_VALUE;
        blockedTicks = 0; active = false;
    }

    /** Called once per movement tick. No search, and no history extension while escaping. */
    public Vec3 update(Vec3 position, long tick, boolean blocked, FlightRoutePlanner.Clearance space) {
        return update(position, tick, blocked, space, false);
    }

    /** After landing, retrace the horizontal corridor only if a standing body and floor still fit. */
    public Vec3 updateGround(Vec3 position, long tick, boolean blocked, FlightRoutePlanner.Clearance space) {
        return update(position, tick, blocked, space, true);
    }

    private Vec3 update(Vec3 position, long tick, boolean blocked, FlightRoutePlanner.Clearance space, boolean grounded) {
        if (!finite(position) || space == null) { reset(); return null; }
        if (previous != null && (tick < previousTick || tick - previousTick > 20
                || position.distanceToSqr(previous) > 32 * 32)) reset();
        Vec3 lastObserved = previous;
        if (tick != previousTick) {
            blockedTicks = blocked ? Math.min(20, blockedTicks + 1) : 0;
            previousTick = tick;
            previous = position;
        }
        if (!active && blockedTicks >= 12 && trail.size() >= 2) {
            active = true;
            started = position;
        }
        if (!active) {
            if (!blocked && space.clear(position, position)) {
                // Keep a traversed corner even when it falls between the two-block samples.
                // Both new legs must be clear now; a changed wall still invalidates the history.
                if (!trail.isEmpty() && !space.clear(waypoint(trail.peekLast(), position, grounded), position)) {
                    Vec3 bend = lastObserved == null ? null : waypoint(lastObserved, position, grounded);
                    if (bend != null && space.clear(waypoint(trail.peekLast(), position, grounded), bend)
                            && space.clear(bend, position)) append(bend);
                    else trail.clear();
                }
                if (trail.isEmpty() || position.distanceToSqr(waypoint(trail.peekLast(), position, grounded)) >= 4) append(position);
            }
            return null;
        }
        // Give the route enough room to leave its dead end before considering pursuit again.
        if (position.distanceToSqr(started) >= 64 && space.clear(position, position.add(0, 6, 0))) {
            reset(); return null;
        }
        while (!trail.isEmpty() && position.distanceToSqr(waypoint(trail.peekLast(), position, grounded)) <= .65 * .65) {
            if (trail.size() > 1 && position.distanceToSqr(waypoint(trail.peekLast(), position, grounded)) > 1e-8) {
                var points = trail.descendingIterator();
                points.next();
                if (!space.clear(position, waypoint(points.next(), position, grounded))) break;
            }
            trail.removeLast();
        }
        if (trail.isEmpty()) { reset(); return null; }
        Vec3 waypoint = waypoint(trail.peekLast(), position, grounded);
        // Do not skip a blocked corner by drawing a straight line to an older sample.
        if (!space.clear(position, waypoint)) return Vec3.ZERO;
        Vec3 delta = waypoint.subtract(position);
        return delta.normalize().scale(Math.min(.9, delta.length()));
    }

    private void append(Vec3 position) {
        trail.addLast(position);
        if (trail.size() > MAX_POINTS) trail.removeFirst();
    }

    private static Vec3 waypoint(Vec3 point, Vec3 position, boolean grounded) {
        return grounded ? new Vec3(point.x, position.y, point.z) : point;
    }

    private static boolean finite(Vec3 v) {
        return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z)
                && Double.isFinite(v.lengthSqr());
    }
}
