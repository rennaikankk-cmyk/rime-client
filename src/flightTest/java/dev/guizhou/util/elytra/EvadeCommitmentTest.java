package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;
import static dev.guizhou.util.elytra.EvadeCommitment.*;

public final class EvadeCommitmentTest {
    private static int checks;
    private static final Vec3 X = new Vec3(1,0,0), Z = new Vec3(0,0,1);
    private static final Key A = new Key(1,1,"spear",10), B = new Key(1,2,"pearl",20);
    public static void main(String[] args) {
        EvadeCommitment c = new EvadeCommitment();
        check(c.update(0,A,false,X,3,2,d->true).reason()==Reason.START,"start");
        check(c.update(1,A,false,Z,3,2,d->true).direction().equals(X),"world direction stays fixed");
        check(c.update(2,A,false,Z,3,2,d->true).reason()==Reason.HOLD,"hold bounded interval");
        check(c.update(3,A,false,Z,3,2,d->true).reason()==Reason.REARM,"hold expires without renewal");
        check(c.update(4,A,false,Z,3,2,d->true).direction().equals(Z),"rearm continues avoidance");
        check(c.update(5,A,false,Z,3,2,d->true).reason()==Reason.START,"rearm ends");
        check(c.update(6,B,true,X,3,2,d->true).reason()==Reason.PREEMPT,"new event preempts");
        check(c.update(7,B,true,Z,3,2,d->d.z>0).direction().equals(Z),"wall interrupts lock");
        check(c.update(8,B,true,X,3,2,d->false).direction().equals(Vec3.ZERO),"no safe direction");
        c.reset();
        c.update(0,A,false,X,3,2,d->true);
        check(c.update(1,A,true,Z,3,2,d->true).reason()==Reason.PREEMPT,"danger escalation");
        c.reset(); c.update(0,A,false,X,3,2,d->true);
        check(c.update(3,B,false,Z,3,2,d->true).reason()==Reason.PREEMPT,"new threat during rearm");
        check(c.update(4,B,false,new Vec3(Double.NaN,0,0),3,2,d->true).reason()==Reason.INVALID,"NaN resets");
        check(c.update(5,B,false,Z,3,2,d->true).reason()==Reason.START,"invalid leaves no stale lock");
        check(c.update(1,A,false,X,3,2,d->true).reason()==Reason.START,"clock rewind");
        check(c.update(30,A,false,Z,3,2,d->true).reason()==Reason.START,"stale tick gap");
        c.reset(); c.update(0,A,false,X,99,99,d->true);
        check(c.update(6,A,false,Z,99,99,d->true).reason()==Reason.REARM,"maximum commitment bounded");
        check(c.update(10,A,false,Z,99,99,d->true).reason()==Reason.START,"maximum rearm bounded");
        c.reset(); c.update(0,A,false,X,3,0,d->true);
        check(c.update(3,A,false,Z,3,0,d->true).reason()==Reason.START,"rearm can be disabled");
        check(c.update(4,new Key(2,1,"spear",10),false,X,3,0,d->true).reason()==Reason.PREEMPT,"new world session");
        c.reset(); c.update(0,A,false,X,3,2,d->true);
        check(c.update(1,null,false,Z,3,2,d->true).direction().equals(X),"lost observation does not rotate escape");
        check(c.update(3,null,false,Z,3,2,d->true).reason()==Reason.IDLE,"lost threat cannot extend commitment");
        // Deterministic noisy-heading replay: verifies the narrow anti-chatter claim, not combat superiority.
        c.reset(); int baselineReversals=0, heldReversals=0; Vec3 previous=X, held=X;
        for(int t=0;t<120;t++) {
            Vec3 desired=t%2==0?X:X.scale(-1);
            Vec3 result=c.update(t,A,false,desired,3,2,d->true).direction();
            if(previous.dot(desired)<0)baselineReversals++;
            if(held.dot(result)<0)heldReversals++;
            previous=desired; held=result;
        }
        check(heldReversals<baselineReversals,"replay reduces rapid reversals");
        System.out.println("EvadeCommitmentTest: "+checks+" checks; synthetic reversals="+baselineReversals+" -> "+heldReversals);
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
