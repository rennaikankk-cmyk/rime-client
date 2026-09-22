package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.function.Predicate;

/** Small numeric search for a climb lane; never reads a world or submits packets. */
public final class CombatClimbPlan {
    public record Choice(CoupledCruise.Selection selection, Vec3 preview) { }
    private Vec3 committed;
    private long chosenTick = Long.MIN_VALUE;
    private boolean approachingApex;

    public void reset() { committed = null; chosenTick = Long.MIN_VALUE; approachingApex = false; }

    public Choice choose(CoupledCruise.Config config, Vec3 apexOffset, Vec3 velocity, Vec3 previousLook,
                         double gravity, boolean dual, long tick, Predicate<Vec3> clear) {
        if (!finite(apexOffset) || !finite(velocity) || !finite(previousLook) || config == null
                || !Double.isFinite(gravity) || gravity < 0 || clear == null) { reset(); return null; }
        if (tick < chosenTick) reset();
        double stoppingRoom = Math.max(8, Math.max(0, velocity.y) * 3 + 3);
        if (apexOffset.y <= stoppingRoom + (approachingApex ? 4 : 0)) {
            committed = null;
            approachingApex = true;
            return null;
        }
        approachingApex = false;
        var headings = new ArrayList<Vec3>(6);
        if (committed != null) headings.add(committed);
        Vec3 toward = new Vec3(apexOffset.x, 0, apexOffset.z);
        if (toward.lengthSqr() > 1e-6) headings.add(toward.normalize());
        double d = Math.sqrt(0.5);
        for (int x : new int[]{-1, 1}) for (int z : new int[]{-1, 1}) headings.add(new Vec3(x*d, 0, z*d));
        var controller = new CoupledCruise();
        Choice best = null, held = null;
        Vec3 bestHeading = null;
        double bestScore = -Double.MAX_VALUE, heldScore = -Double.MAX_VALUE;
        for (Vec3 heading : headings) {
            var selection = controller.select(config, new CoupledCruise.Intent(heading, 1, false), 0, 0, dual);
            Vec3 simulated = ElytraPhysics.simulateGlide(velocity, selection.look(), gravity);
            var result = controller.apply(config, selection, velocity, simulated, selection.look(), previousLook);
            if (!result.feasible() || result.velocity().y <= 0 || result.velocity().y >= apexOffset.y - 3
                    || !clear.test(result.velocity().scale(2))) continue;
            // Lift matters, but keep a reachable dive lane rather than spiralling away from the target.
            double miss = apexOffset.subtract(result.velocity().scale(4)).horizontalDistance();
            double score = result.velocity().y - 0.08 * miss;
            var choice = new Choice(selection, result.velocity());
            if (committed != null && heading.dot(committed) > 0.99999) { held = choice; heldScore = score; }
            if (score > bestScore) { bestScore = score; best = choice; bestHeading = heading; }
        }
        if (held != null && (tick - chosenTick < 6 || bestScore - heldScore < 0.25)) return held;
        if (best != null) { committed = bestHeading; chosenTick = tick; }
        else reset();
        return best;
    }

    private static boolean finite(Vec3 v) {
        return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }
}
