package dev.guizhou.util.combat;

import com.google.gson.*;
import java.util.*;
import java.util.function.BiPredicate;
import static dev.guizhou.util.combat.AuraPolicy.*;
import static dev.guizhou.util.combat.AuraPath.Point;

public final class AuraPolicyTest {
    private static int checks;
    public static void main(String[] args) {
        check(Math.abs(afterArmor(10,20,8,0)-3)<1e-8,"vanilla armor reduction");
        check(Math.abs(afterArmor(10,20,8,4)-9)<1e-8,"Breach subtracts effectiveness, not armor points");
        check(afterArmor(10,0,0,4)==10,"Breach cannot amplify unarmored damage");
        check(afterArmor(10,20,8,4)>afterArmor(20,20,8,0),"smart mode can prefer Breach over larger raw damage");
        Clock clock = new Clock();
        clock.reset(0,0);
        double sword = cooldown(1.6,1);
        for (int tick = 1; tick <= 24; tick++) { clock.tick(tick,10); check(!clock.ready(sword),"10 TPS waits 25 client ticks"); }
        clock.tick(25,10); check(clock.ready(sword),"TPS adjustment occurs once");
        clock.submitted(); check(!clock.ready(sword),"accepted attack resets cooldown");
        clock.tick(26,20); clock.tick(26,20); check(clock.elapsed()==1,"duplicate tick does not accelerate");
        check(!clock.ready(cooldown(0,1)),"invalid weapon speed cannot attack");
        clock.tick(27,Double.NaN); check(clock.elapsed()==2,"nonfinite TPS fallback");
        record Target(String name, int health, boolean valid) {}
        Target named = new Target("excluded",1,false), a = new Target("a",10,true), b = new Target("b",20,true);
        var candidates = candidates(List.of(named,b,a),Target::valid,Comparator.comparingInt(Target::health),3);
        check(candidates.equals(List.of(a,b)),"exclude before sorting to preserve fallback");
        check(choose(candidates,b,TargetMode.SINGLE,t -> 0)==b,"single retains target");
        check(choose(candidates,b,TargetMode.SWITCH,t -> 0)==a,"switch follows priority");
        check(choose(candidates,a,TargetMode.MULTI,t -> t==a?1:0)==b,"multi cycles unhit target");
        check(choose(List.of(),a,TargetMode.MULTI,t -> 0)==null,"empty clears target");
        check(candidates(List.of(a,b),Target::valid,Comparator.comparingInt(Target::health),1).size()==1,"target cap");
        List<Integer> moves = new ArrayList<>();
        var trip = AuraTeleport.roundTrip(0,List.of(1,2,3),p -> { moves.add(p); return true; },() -> true,p -> {moves.add(p); return true;});
        check(trip.attacked() && trip.restored() && moves.equals(List.of(1,2,3,2,1,0)),"full return order");
        moves.clear();
        trip = AuraTeleport.roundTrip(0,List.of(1,2,3),p -> p!=2,() -> {throw new AssertionError("partial entry attacked");},p -> { moves.add(p); return true; });
        check(!trip.attacked() && trip.restored() && moves.equals(List.of(0)),"partial entry returns without attack");
        moves.clear();
        try {
            AuraTeleport.roundTrip(0,List.of(1,2),p -> true,() -> {throw new IllegalStateException("attack failed");},p -> {moves.add(p);return true;});
            throw new AssertionError("exception swallowed");
        } catch (IllegalStateException expected) { check(moves.equals(List.of(1,0)),"attack exception still returns"); }
        moves.clear();
        try {
            AuraTeleport.roundTrip(0,List.of(1,2),p -> true,() -> true,p -> {moves.add(p);if(p==1)throw new IllegalStateException();return true;});
        } catch (IllegalStateException expected) { check(moves.equals(List.of(1,0)),"return exception still attempts origin"); }
        trip = AuraTeleport.roundTrip(0,List.of(1),p -> true,() -> true,p -> false);
        check(trip.attacked() && !trip.restored(),"refused return is reported");
        Point origin = new Point(0,0,0), goal = new Point(8,0,0);
        var route = AuraPath.find(origin,goal,3,12,384,(p,q)->true);
        verifyRoute(origin,goal,route,3,(p,q)->true);
        BiPredicate<Point,Point> wall = (p,q) -> !(Math.min(p.x(),q.x())<=4 && Math.max(p.x(),q.x())>=4
                && Math.min(p.y(),q.y())<2 && Math.max(p.y(),q.y())>-2 && Math.min(p.z(),q.z())<2 && Math.max(p.z(),q.z())>-2);
        route = AuraPath.find(origin,goal,3,12,384,wall);
        verifyRoute(origin,goal,route,3,wall);
        check(route.stream().anyMatch(p->Math.abs(p.y())>=2 || Math.abs(p.z())>=2),"route goes around wall");
        check(AuraPath.find(origin,goal,3,12,384,(p,q)->false).isEmpty(),"sealed origin rejected");
        check(AuraPath.find(origin,goal,3,6,384,(p,q)->true).isEmpty(),"range bounded");
        check(AuraPath.find(origin,new Point(Double.NaN,0,0),3,12,384,(p,q)->true).isEmpty(),"invalid coordinate rejected");
        check(AuraPath.find(origin,goal,3,12,1,wall).isEmpty(),"search budget bounded");
        JsonObject old = JsonParser.parseString("{\"Delay\":0.92,\"Criticals\":true,\"Bind\":42,\"DisplayName\":\"Mine\"}").getAsJsonObject();
        var migrated = AuraConfigMigration.migrate(old).getAsJsonObject();
        check(migrated.get("AttackCD").getAsDouble()==1 && migrated.get("CritMode").getAsString().equals("SMART"),"new defaults replace obsolete packet criticals");
        check(migrated.get("Bind").getAsInt()==42 && migrated.get("DisplayName").getAsString().equals("Mine"),"bindings and label preserved");
        check(old.has("Delay") && !migrated.has("Delay"),"migration does not mutate original");
        check(migrated.equals(AuraConfigMigration.migrate(migrated)),"migration idempotent");
        System.out.println("AuraPolicyTest passed: "+checks+" checks");
    }
    private static void verifyRoute(Point origin,Point goal,List<Point> route,double step,BiPredicate<Point,Point> clear) {
        check(!route.isEmpty() && route.getLast().equals(goal),"route reaches exact endpoint");
        Point previous = origin;
        for (Point point : route) { check(previous.distance(point)<=step+1e-8 && clear.test(previous,point),"bounded collision-free edge"); previous=point; }
    }
    private static void check(boolean value,String message) { checks++; if(!value)throw new AssertionError(message); }
}
