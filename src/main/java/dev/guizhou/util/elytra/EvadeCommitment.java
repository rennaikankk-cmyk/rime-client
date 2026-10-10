package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;
import java.util.Objects;
import java.util.function.Predicate;

/** Bounded world-space heading commitment. Debouncing never suppresses threat detection. */
public final class EvadeCommitment {
    public record Key(long session, int entity, String kind, long attack) {}
    public enum Reason { IDLE, START, HOLD, REARM, PREEMPT, BLOCKED, INVALID }
    public record Result(Vec3 direction, Reason reason) {}
    private Key key;
    private Vec3 heading = Vec3.ZERO;
    private long until, rearmUntil, lastTick = Long.MIN_VALUE;
    private boolean urgent;

    public Result update(long tick, Key threat, boolean urgentNow, Vec3 candidate,
                         int holdTicks, int rearmTicks, Predicate<Vec3> clear) {
        if (lastTick != Long.MIN_VALUE && (tick < lastTick || tick - lastTick > 20)) reset();
        lastTick = tick;
        if (!finite(candidate)) { reset(); return new Result(Vec3.ZERO, Reason.INVALID); }
        Vec3 proposed = candidate.lengthSqr() > 1.0e-9 ? candidate.normalize() : Vec3.ZERO;
        boolean newThreat = threat != null && !Objects.equals(threat, key);
        boolean escalated = threat != null && urgentNow && !urgent;
        boolean blocked = heading.lengthSqr() > 0 && !clear.test(heading);
        if (!newThreat && !escalated && !blocked && tick < until && heading.lengthSqr() > 0) {
            return new Result(heading, Reason.HOLD);
        }
        if (!clear.test(proposed)) {
            heading = Vec3.ZERO;
            until = rearmUntil = tick;
            return new Result(Vec3.ZERO, Reason.BLOCKED);
        }
        if (threat == null) {
            heading = Vec3.ZERO;
            return new Result(proposed, Reason.IDLE);
        }
        if (!newThreat && !escalated && !blocked && tick < rearmUntil) {
            // Recompute normally during rearm: no global blind/immune interval.
            return new Result(proposed, Reason.REARM);
        }
        Reason reason = blocked ? Reason.BLOCKED : newThreat && key != null || escalated
                ? Reason.PREEMPT : Reason.START;
        key = threat;
        urgent = urgentNow;
        heading = proposed;
        until = tick + Math.clamp(holdTicks, 1, 6);
        rearmUntil = until + Math.clamp(rearmTicks, 0, 4);
        return new Result(heading, reason);
    }

    public void reset() {
        key = null;
        heading = Vec3.ZERO;
        until = rearmUntil = 0;
        lastTick = Long.MIN_VALUE;
        urgent = false;
    }

    private static boolean finite(Vec3 v) {
        return v != null && Double.isFinite(v.lengthSqr());
    }
}
