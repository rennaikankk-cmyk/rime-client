package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;

public final class PearlLandingThreatTest {
    private static int checks;
    public static void main(String[] args) {
        Vec3 origin=Vec3.ZERO, landing=new Vec3(3,0,0);
        var near=PearlLandingThreat.assess(1,2,landing,2,origin,origin,3,true);
        check(near!=null && near.urgent(),"imminent hostile arrival");
        check(PearlLandingThreat.assess(1,2,landing,2,origin,origin,3,false)==null,"friendly arrival");
        check(PearlLandingThreat.assess(1,2,landing,13,origin,origin,3,true)==null,"uncertain distant horizon");
        check(PearlLandingThreat.assess(1,2,new Vec3(30,0,0),2,origin,origin,3,true)==null,"far landing");
        check(PearlLandingThreat.assess(1,2,landing,3,origin,new Vec3(-3,0,0),3,true)==null,"already moving clear");
        check(PearlLandingThreat.assess(1,2,new Vec3(12,0,0),3,origin,new Vec3(4,0,0),3,true)!=null,"future self at landing");
        check(PearlLandingThreat.assess(1,2,new Vec3(8,0,0),2,origin,origin,8,true)!=null,"spear arrival reach");
        check(PearlLandingThreat.assess(1,2,new Vec3(8,0,0),2,origin,origin,3,true)==null,"ordinary reach differs");
        check(PearlLandingThreat.assess(1,2,new Vec3(Double.NaN,0,0),2,origin,origin,3,true)==null,"invalid landing");
        check(PearlLandingThreat.assess(1,2,landing,0,origin,origin,3,true)==null,"expired arrival");
        check(PearlLandingThreat.clearance(origin,new Vec3(-1,0,0),2,near)>
                PearlLandingThreat.clearance(origin,new Vec3(1,0,0),2,near),"escape heads away from arrival");
        System.out.println("PearlLandingThreatTest: "+checks+" checks passed");
    }
    private static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
}
