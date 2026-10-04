package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;

public final class FlightEscapeTrailTest {
    private static int checks;
    private static final FlightRoutePlanner.Clearance OPEN = (a,b) -> true;
    public static void main(String[] args) {
        FlightEscapeTrail trail = new FlightEscapeTrail();
        for (int tick=0; tick<10; tick++) {
            check(trail.update(new Vec3(tick,0,0),tick,false,OPEN)==null,"Normal flight stays under its owner's control");
        }
        Vec3 end = new Vec3(9,0,0);
        for (int tick=10;tick<21;tick++) check(trail.update(end,tick,true,OPEN)==null,"Transient obstruction cannot start retreat");
        Vec3 step = trail.update(end,21,true,OPEN);
        check(trail.active() && step.x<0 && step.length()<=.9,"Sustained blockage retraces traversed flight at bounded speed");
        check(trail.update(end,22,true,(a,b)->false).equals(Vec3.ZERO),"A newly placed wall blocks even a previously traversed leg");
        check(trail.active(),"Blocked retreat stays safe rather than handing the same wall back to pursuit");
        trail.update(new Vec3(100,0,0),23,false,OPEN);
        check(!trail.active() && trail.size()==1,"Teleport invalidates the old cave trail");
        trail.update(new Vec3(100,0,0),0,false,OPEN);
        check(!trail.active() && trail.size()==1,"Clock/session rollback discards old path");
        trail.reset();
        for(int tick=0;tick<400;tick++) trail.update(new Vec3(tick*2,0,0),tick,false,OPEN);
        check(trail.size()==FlightEscapeTrail.MAX_POINTS,"History is bounded on long travel");
        trail.reset();
        // A roofed L-shaped passage: direct diagonal exit is blocked, but reversing the legs works.
        FlightRoutePlanner.Clearance tunnel = (a,b)-> {
            int steps=Math.max(1,(int)Math.ceil(a.distanceTo(b)*20));
            for(int i=0;i<=steps;i++) {
                Vec3 p=a.lerp(b,(double)i/steps);
                if(Math.abs(p.y)>.2 || !(p.x>=-.4 && p.x<=6.4 && Math.abs(p.z)<.4
                        || Math.abs(p.x-6)<.4 && p.z>=-.4 && p.z<=6.4)) return false;
            }
            return true;
        };
        int tick=0;
        for(int x=0;x<=6;x++)trail.update(new Vec3(x,0,0),tick++,false,tunnel);
        for(int z=1;z<=6;z++)trail.update(new Vec3(6,0,z),tick++,false,tunnel);
        Vec3 at=new Vec3(6,0,6);
        for(int i=0;i<12;i++)step=trail.update(at,tick++,true,tunnel);
        for(int i=0;i<80 && trail.active();i++) {
            step=trail.update(at,tick++,false,tunnel);
            if(step!=null) {
                check(tunnel.clear(at,at.add(step)),"Every reverse leg clears the full corridor, without corner cutting");
                at=at.add(step);
            }
        }
        check(!trail.active() && at.distanceTo(Vec3.ZERO)<=.65,"Roofed dead end reverses both turns to its recorded entrance");
        trail.reset(); tick=0;
        for (int i=0;i<=27;i++) trail.update(new Vec3(.6+i*.2,0,0),tick++,false,tunnel);
        for (int i=1;i<=30;i++) trail.update(new Vec3(6,0,i*.2),tick++,false,tunnel);
        at=new Vec3(6,0,6);
        for(int i=0;i<12;i++) trail.update(at,tick++,true,tunnel);
        for(int i=0;i<100 && trail.active();i++) {
            step=trail.update(at,tick++,false,tunnel);
            if(step!=null) {
                check(tunnel.clear(at,at.add(step)),"Unaligned breadcrumb corners also preserve every live swept leg");
                at=at.add(step);
            }
        }
        check(!trail.active() && at.distanceTo(new Vec3(.6,0,0))<=.65,
                "A turn between the two-block samples retains the earlier passage, not just the final dead end");
        trail.reset();
        for (int x=0;x<=8;x++) trail.update(new Vec3(x,.5,0),x,false,OPEN);
        for (int t=9;t<21;t++) step=trail.updateGround(new Vec3(8,0,0),t,true,(a,b)->a.y==0 && b.y==0);
        check(trail.active() && step.x<0 && step.y==0,"Landing projects flight history onto a separately validated standing corridor");
        check(trail.updateGround(new Vec3(8,0,0),21,true,(a,b)->false).equals(Vec3.ZERO),
                "A gliding passage with insufficient standing clearance or floor support cannot become a walking exit");
        trail.reset();
        check(trail.update(Vec3.ZERO,1,true,OPEN)==null,"No history cannot invent an exit");
        trail.update(new Vec3(Double.NaN,0,0),2,true,OPEN);
        check(!trail.active() && trail.size()==0,"Invalid position clears path");
        System.out.println("FlightEscapeTrailTest passed: "+checks+" checks");
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
