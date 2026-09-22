package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;

/** Tick-based hysteresis for steering decisions; collision vetoes remain immediate. */
public final class CombatSteering {
    private CombatSteering() {}

    public static final class Side {
        private int selected = 1, candidate, samples;
        private long lastTick = Long.MIN_VALUE, changedTick = Long.MIN_VALUE;

        public void reset() {
            selected = 1;
            candidate = samples = 0;
            lastTick = changedTick = Long.MIN_VALUE;
        }

        /** Returns zero when neither route is safe. Repeated calls in one tick do not count as evidence. */
        public int choose(long tick, int preferred, boolean positiveClear, boolean negativeClear) {
            if (tick < lastTick) reset();
            if (lastTick != Long.MIN_VALUE && tick - lastTick > 1) candidate = samples = 0;
            boolean fresh = tick != lastTick;
            lastTick = tick;
            if (!positiveClear && !negativeClear) { candidate = samples = 0; return 0; }
            if (changedTick == Long.MIN_VALUE) {
                selected = preferred < 0 && negativeClear || !positiveClear ? -1 : 1;
                changedTick = tick;
                return selected;
            }
            if (selected > 0 ? !positiveClear : !negativeClear) {
                selected = -selected;
                changedTick = tick;
                candidate = samples = 0;
                return selected;
            }
            int next = Integer.signum(preferred);
            if (next == 0 || next == selected || (next > 0 ? !positiveClear : !negativeClear)) {
                candidate = samples = 0;
                return selected;
            }
            if (fresh) {
                samples = candidate == next ? samples + 1 : 1;
                candidate = next;
            }
            if (samples >= 3 && tick - changedTick >= 6) {
                selected = next;
                changedTick = tick;
                candidate = samples = 0;
            }
            return selected;
        }
    }

    /** Different enter/exit distances prevent boundary noise from reversing movement. */
    public static final class RangeBand {
        private boolean below;
        public void reset() { below = false; }
        public boolean below(double distance, double enter, double exit) {
            if (!Double.isFinite(distance) || !Double.isFinite(enter) || !Double.isFinite(exit)
                    || distance < 0 || enter < 0 || exit <= enter) { reset(); return false; }
            if (distance < enter) below = true;
            else if (distance > exit) below = false;
            return below;
        }
    }

    /** Bounded angular steering, once per movement tick. Never smooths requested speed. */
    public static final class Motion {
        private Vec3 direction;
        private long lastTick = Long.MIN_VALUE, context;
        public void reset() { direction = null; lastTick = Long.MIN_VALUE; }
        public Vec3 apply(long tick, long encounter, Vec3 request, double maxDegrees, boolean immediate) {
            if (request == null || !Double.isFinite(request.lengthSqr())) { reset(); return Vec3.ZERO; }
            double speed = request.length();
            if (speed < 1e-6) { reset(); return request; }
            Vec3 desired = request.scale(1 / speed);
            if (immediate || direction == null || encounter != context || tick < lastTick || tick - lastTick > 2
                    || !Double.isFinite(maxDegrees) || maxDegrees <= 0) {
                direction = desired;
            } else if (tick != lastTick) {
                double dot = Math.clamp(direction.dot(desired), -1, 1);
                double angle = Math.acos(dot);
                double limit = Math.toRadians(maxDegrees) * Math.min(2, tick - lastTick);
                if (angle <= limit) direction = desired;
                else {
                    Vec3 tangent = desired.subtract(direction.scale(dot));
                    // Opposite vectors have no unique shortest arc. Choose a stable plane.
                    if (tangent.lengthSqr() < 1e-10) {
                        Vec3 axis = Math.abs(direction.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
                        tangent = axis.cross(direction);
                    }
                    direction = direction.scale(Math.cos(limit)).add(tangent.normalize().scale(Math.sin(limit))).normalize();
                }
            }
            lastTick = tick; context = encounter;
            return direction.scale(speed);
        }
    }

    public static final class FailureWindow {
        private long lastTick = Long.MIN_VALUE;
        private int failures;
        public void reset() { lastTick = Long.MIN_VALUE; failures = 0; }
        /** A single unstable prediction must not restart a complete attack pass. */
        public boolean failed(long tick, boolean reachable) {
            if (tick < lastTick || lastTick != Long.MIN_VALUE && tick - lastTick > 1) reset();
            if (reachable) failures = 0;
            else if (tick != lastTick) failures++;
            lastTick = tick;
            return failures >= 2;
        }
    }
}
