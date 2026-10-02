package dev.guizhou.util.elytra;
import net.minecraft.world.phys.Vec3;
public final class DirectFlightControlTest {
    private static int checks;
    public static void main(String[] args) {
        check(DirectFlightControl.limit(new Vec3(2,2,0),2,2).equals(new Vec3(2,2,0)), "climbing does not steal horizontal budget");
        check(DirectFlightControl.limit(new Vec3(20,-30,0),2,3).equals(new Vec3(2,-3,0)), "independent settings enforced");
        Vec3 precise=new Vec3(.01,-.02,.03);
        check(DirectFlightControl.limit(precise,2,2).equals(precise), "terminal precision is never accelerated");
        check(Math.abs(DirectFlightControl.budget(2,3)-Math.sqrt(13))<1e-9, "planning budget covers both axes");
        for(int degrees=0;degrees<360;degrees++) {
            double a=Math.toRadians(degrees);Vec3 v=DirectFlightControl.limit(new Vec3(10*Math.sin(a),7,10*Math.cos(a)),2,3);
            check(Math.abs(v.horizontalDistance()-2)<1e-9&&v.y==3, "rotationally invariant horizontal cap");
        }
        check(DirectFlightControl.limit(new Vec3(Double.NaN,1,1),2,2).equals(Vec3.ZERO), "nonfinite movement rejected");
        Vec3 lane = DirectFlightControl.spearLane(new Vec3(2,-2,0), -2, 2);
        check(lane.equals(new Vec3(2,2,0)), "MXS charge retains height against a rising mace");
        lane = DirectFlightControl.spearLane(new Vec3(-2,-2,0), 0, 0);
        check(lane.x == -2 && lane.y == 1, "making charge room retains horizontal escape and gains height");
        lane = DirectFlightControl.spearLane(new Vec3(2,2,0), -10, -1);
        check(lane.y == -3, "height tracking does not hover forever above a falling opponent");
        check(DirectFlightControl.spearLane(Vec3.ZERO, Double.NaN, 0).equals(Vec3.ZERO), "invalid lane rejected");
        check(DirectFlightControl.spearLane(new Vec3(-2,-2,0),0,2,0).equals(new Vec3(-2,2,0)),
                "ready spear retreat preserves lateral escape without dropping below a rising opponent");
        check(DirectFlightControl.spearLane(new Vec3(2,-2,0),-2,2,0).equals(new Vec3(2,1,0)),
                "ready spear spends excess height gradually to approach the attack plane");
        check(DirectFlightControl.spearLane(Vec3.ZERO,0,0,Double.NaN).equals(Vec3.ZERO),
                "nonfinite lane height rejected");
        Vec3 downPass = new Vec3(1,-2,0);
        Vec3 toward = new Vec3(1,-3,0).normalize();
        Vec3 attacking = DirectFlightControl.mxsSpearStep(downPass,-2,2,true,false);
        check(attacking.equals(downPass) && attacking.dot(toward)>0,
                "a ready downward pass retains positive motion along the attack ray");
        check(DirectFlightControl.spearLane(downPass,-2,2,0).dot(toward)<0,
                "reproduction: unconditional height tracking reverses kinetic attack motion");
        check(DirectFlightControl.mxsSpearStep(downPass,-2,2,false,false).y==2,
                "an unfinished charge still tracks a rising opponent");
        check(DirectFlightControl.mxsSpearStep(downPass,0,2,true,true).y==2,
                "a ready retreat still avoids falling below the opponent");
        check(DirectFlightControl.mxsSpearStep(precise,0,0,true,false).equals(precise),
                "ready-pass steering preserves the spear planner's precise velocity");
        check(DirectFlightControl.macePressure(40,2,2,1,false), "rising stalemate permits a cooled ordinary hit");
        check(!DirectFlightControl.macePressure(39,2,2,1,false), "do not interrupt establishing a climb");
        check(!DirectFlightControl.macePressure(40,2,2,.95,false), "ordinary pressure respects attack cooldown");
        check(!DirectFlightControl.macePressure(40,2,2,1,true), "committed drop takes priority");
        check(!DirectFlightControl.macePressure(40,-2,2,1,false), "falling smash window must not become an ordinary hit");
        check(!DirectFlightControl.macePressure(40,2,0,1,false), "stationary targets retain normal smash planning");
        check(DirectFlightControl.shouldChargeSpear(80,8,2,false), "mixed mode gets a charge window between ordinary pressure hits");
        check(!DirectFlightControl.shouldChargeSpear(79,8,2,false), "allow the initial mace approach to settle");
        check(!DirectFlightControl.shouldChargeSpear(80,7,2,false), "charge does not interrupt the pressure-hit handoff");
        check(!DirectFlightControl.shouldChargeSpear(80,8,-.01,false), "do not start charge below a climbing mace");
        check(!DirectFlightControl.shouldChargeSpear(80,8,2,true), "committed natural dive retains ownership");
        check(!DirectFlightControl.shouldChargeSpear(-1,8,2,false), "phase rewind cannot start a new charge");
        check(!DirectFlightControl.shouldChargeSpear(80,-1,2,false), "attack clock rewind cannot start a new charge");
        check(!DirectFlightControl.shouldChargeSpear(80,8,Double.NaN,false), "invalid height cannot start charge");
        System.out.println("DirectFlightControlTest passed: " + checks + " checks");
    }
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
}
