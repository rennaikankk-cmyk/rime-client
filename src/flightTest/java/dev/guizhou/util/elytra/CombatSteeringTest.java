package dev.guizhou.util.elytra;

public final class CombatSteeringTest {
    private static int checks;
    public static void main(String[] args) {
        motionAndBands();
        var side = new CombatSteering.Side();
        check(side.choose(10, 1, true, true) == 1, "initial route");
        for (int tick=11; tick<=40; tick++)
            check(side.choose(tick, tick%2==0 ? 1 : -1, true, true)==1, "no alternating prediction jitter");
        check(side.choose(41,-1,true,true)==1, "one opposite prediction is insufficient");
        check(side.choose(41,-1,true,true)==1, "same tick cannot accumulate evidence");
        check(side.choose(42,-1,true,true)==1, "two opposite observations");
        check(side.choose(43,-1,true,true)==-1, "sustained new direction accepted");
        check(side.choose(43,0,true,false)==1, "blocked route changes immediately despite hold");
        check(side.choose(44,0,false,false)==0, "both blocked never return a lateral route");
        check(side.choose(45,0,true,true)==1, "resume clear held route");
        check(side.choose(1,-1,true,true)==-1, "time reset discards previous encounter");
        side.reset();
        check(side.choose(100,-1,true,false)==1, "initial preferred route cannot cross obstruction");
        var failures = new CombatSteering.FailureWindow();
        check(!failures.failed(10,false), "single missed intercept keeps pass");
        check(!failures.failed(10,false), "two evaluations are not two ticks");
        check(!failures.failed(11,true), "reachable intercept clears miss");
        check(!failures.failed(12,false), "new isolated miss");
        check(failures.failed(13,false), "persistent miss restarts pass");
        check(!failures.failed(20,false), "gap cannot count stale miss");
        check(!failures.failed(2,false), "new session cannot count stale miss");
        failures.reset();
        check(!failures.failed(3,false), "explicit reset");
        System.out.println("CombatSteeringTest passed: "+checks+" checks");
    }
    private static void motionAndBands() {
        var band = new CombatSteering.RangeBand();
        check(band.below(5,6,8), "Too close starts withdrawal");
        for (double d : new double[]{6.1,5.9,7,6,7.9})
            check(band.below(d,6,8), "Noise near entry does not alternate withdrawal");
        check(!band.below(8.1,6,8), "Clearance ends withdrawal");
        for (double d : new double[]{7.9,6.1,8,7})
            check(!band.below(d,6,8), "Hold clearance without re-entering on the exit boundary");
        band.reset();check(!band.below(7,6,8), "New encounter clears band");
        var first = new CombatSteering.Motion();
        var repeat = new CombatSteering.Motion();
        var east = new net.minecraft.world.phys.Vec3(6,0,0);
        var west = east.scale(-1);
        first.apply(1,1,east,35,false);repeat.apply(1,1,east,35,false);
        var previous = east;
        for (int tick=2;tick<20;tick++) {
            var requested = tick<8 ? west : new net.minecraft.world.phys.Vec3(0,-3,0);
            var actual = first.apply(tick,1,requested,35,false);
            for (int frame=0;frame<12;frame++)
                check(actual.distanceTo(repeat.apply(tick,1,requested,35,false))<1e-9,
                        "Repeated/render-rate calls cannot accelerate steering");
            check(Math.abs(actual.length()-requested.length())<1e-9, "Preserve requested speed including immediate braking");
            double angle=Math.toDegrees(Math.acos(Math.clamp(previous.normalize().dot(actual.normalize()),-1,1)));
            check(angle<=35.000001, "Bound nonurgent angular change including opposite directions");
            previous=actual;
        }
        check(first.apply(20,1,east,35,true).equals(east), "Urgent escape/hit response is immediate");
        check(first.apply(20,1,west,35,true).equals(west), "Urgency overrides a same-tick smoothing result");
        check(first.apply(21,2,east,35,false).equals(east), "Target/session change discards stale heading");
        check(first.apply(22,2,net.minecraft.world.phys.Vec3.ZERO,35,false).equals(net.minecraft.world.phys.Vec3.ZERO), "Zero intent stops immediately");
        var fv=new FlightVelocity(east,6);
        fv.preserveDirection();fv.set(west);fv.set(east);
        check(fv.isDirectional(), "Route updates preserve directional intent");
        fv.requestAvoidance(west);
        check(fv.isAvoiding() && fv.value().equals(west), "Obstacle override adopts the safe direction");
        fv.requestPrecision();fv.set(east);
        check(fv.isPrecise(), "Direction refinement preserves strike precision");
    }
    private static void check(boolean ok,String message) { checks++;if(!ok)throw new AssertionError(message); }
}
