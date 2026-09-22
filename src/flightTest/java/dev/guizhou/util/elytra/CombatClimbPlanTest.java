package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;

public final class CombatClimbPlanTest {
    private static int checks;
    public static void main(String[] args) {
        var planner = new CombatClimbPlan();
        var config = CoupledCruise.Config.defaults();
        Vec3 velocity = new Vec3(1, 0.2, 1), look = new Vec3(1, 1, 1).normalize();
        Vec3 apex = new Vec3(10, 80, 10);
        for (boolean dual : new boolean[]{false, true}) {
            planner.reset();
            var choice = planner.choose(config, apex, velocity, look, 0.08, dual, false, 10, v -> true);
            check(choice != null && choice.preview().y > 0, "Feasible climb");
            check(choice.selection().dualActive() == dual, "Caller owns the protocol gate");
            var simulated = ElytraPhysics.simulateGlide(velocity, choice.selection().look(), 0.08);
            var second = dual ? choice.selection().boxLook() : look;
            var envelope = CoupledCruise.envelope(config, velocity, simulated, choice.selection().look(), second);
            check(envelope != null && envelope.contains(choice.preview(), 1e-8), "Climb stays inside its actual envelope");
            var held = planner.choose(config, apex.add(0.001, 0, -0.001), velocity, look, 0.08, dual, false, 11, v -> true);
            check(held != null && held.selection().look().distanceTo(choice.selection().look()) < 1e-8,
                    "Small target noise does not flip the lane");
            check(planner.choose(config, apex, velocity, look, 0.08, dual, false, 12, v -> false) == null,
                    "Collision veto overrides the committed lane");
            check(planner.choose(config, new Vec3(10, 7, 10), velocity, look, 0.08, dual, false, 13, v -> true) == null,
                    "Near apex returns control to the exact intercept");
            check(planner.choose(config, new Vec3(10, 12, 10), new Vec3(1, 4, 1), look, 0.08, dual, false, 14, v -> true) == null,
                    "Fast ascent leaves additional stopping room");
        }
        // GZBoost combat climb: the accelerated wish only materialises with dual view,
        // and the boosted lane must still satisfy the same envelope bracket it reports.
        for (boolean dual : new boolean[]{false, true}) {
            planner.reset();
            var boosted = planner.choose(config, apex, velocity, look, 0.08, dual, true, 40, v -> true);
            check(boosted != null && boosted.preview().y > 0, "Boosted climb feasible");
            check(boosted.selection().banked() == dual,
                    "Accelerated climb banks only on the dual-view protocol");
            var secondView = boosted.selection().dualActive() && boosted.selection().boxLook() != null
                    ? boosted.selection().boxLook() : look;
            var sim = ElytraPhysics.simulateGlide(velocity, boosted.selection().look(), 0.08);
            var box = CoupledCruise.envelope(config, velocity, sim, boosted.selection().look(), secondView);
            check(box != null && box.contains(boosted.preview(), 1e-8),
                    "Boosted climb stays inside its reported bracket");
            var again = planner.choose(config, apex, velocity, look, 0.08, dual, true, 41, v -> true);
            check(again != null && again.selection().look().distanceTo(boosted.selection().look()) < 1e-8,
                    "Committed boosted lane holds across ticks");
            var settled = planner.choose(config, apex, velocity, look, 0.08, dual, true, 42, v -> true);
            if (dual) check(settled != null && settled.selection().boxLook() != null
                    && settled.selection().boxLook().distanceTo(again.selection().boxLook()) < 1e-8,
                    "A held lane keeps a steady bank side once committed");
            else check(settled != null && settled.selection().boxLook() == null,
                    "Without the dual-view protocol the climb reports no second view");
        }
        check(planner.choose(config, apex, velocity, look, Double.NaN, true, true, 20, v -> true) == null, "Invalid gravity");
        check(planner.choose(config, null, velocity, look, 0.08, true, true, 20, v -> true) == null, "Missing target");
        planner.reset();
        check(planner.choose(config,new Vec3(10,7,10),velocity,look,.08,true,true,30,v->true)==null,"Exit fast climb near apex");
        check(planner.choose(config,new Vec3(10,9,10),velocity,look,.08,true,true,31,v->true)==null,"Small apex noise cannot re-enter fast climb");
        check(planner.choose(config,new Vec3(10,40,10),velocity,look,.08,true,true,32,v->true)!=null,"A genuinely distant apex permits fast climb again");
        System.out.println("CombatClimbPlanTest passed: " + checks + " checks");
    }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
