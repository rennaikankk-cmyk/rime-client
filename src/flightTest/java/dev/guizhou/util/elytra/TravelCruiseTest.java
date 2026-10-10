package dev.guizhou.util.elytra;
import net.minecraft.world.phys.Vec3;

public final class TravelCruiseTest {
    private static int checks;
    public static void main(String[] args) {
        var c = new TravelCruise();
        Vec3 down = c.select(TravelCruise.Mode.Pitch40,10,200,0,new Vec3(0,0,2),80,true,true);
        check(down.y < -.6 && !c.rocketRequested(), "descent conserves rockets");
        Vec3 up = c.select(TravelCruise.Mode.Pitch40,11,165,90,new Vec3(0,-1,2),80,true,true);
        check(up.y > .8 && Math.abs(up.x)<1e-6 && c.rocketRequested(), "predictive pullup keeps heading and requests thrust");
        check(c.select(TravelCruise.Mode.Pitch40,12,190,90,Vec3.ZERO,80,true,false).x<0, "unlocked heading follows camera");
        check(c.select(TravelCruise.Mode.Pitch40,13,190,90,Vec3.ZERO,80,false,true).equals(Vec3.ZERO)
                && !c.rocketRequested() && c.selection(true)==null, "takeover releases all travel requests");
        c.select(TravelCruise.Mode.FireworkGlide,14,250,180,Vec3.ZERO,80,true,true);
        check(c.rocketRequested(), "firework glide begins with altitude reserve");
        check(c.select(TravelCruise.Mode.FireworkGlide,15,288,180,new Vec3(0,1,2),80,true,true).y<0
                && !c.rocketRequested(), "ceiling forecast starts coasting before overshoot");
        check(c.select(TravelCruise.Mode.FireworkGlide,15,200,0,Vec3.ZERO,80,true,true).y<0, "duplicate tick never advances phase");
        check(c.select(TravelCruise.Mode.FireworkGlide,16,212,0,new Vec3(0,-1,2),80,true,true).y>0, "floor forecast restarts climb");
        check(c.select(TravelCruise.Mode.Pitch40,17,Double.NaN,0,Vec3.ZERO,80,true,true).equals(Vec3.ZERO), "invalid observation releases");
        c.reset();check(c.select(TravelCruise.Mode.Off,18,100,0,Vec3.ZERO,80,true,true).equals(Vec3.ZERO), "off has no request");
        for (var mode : TravelCruise.Mode.values()) if (mode != TravelCruise.Mode.Off) {
            c.reset();
            c.select(mode,0,200,45,Vec3.ZERO,80,true,true);
            Vec3 low = c.select(mode,1,158,45,new Vec3(0,-3,4),80,true,true);
            check(low.y>0 && c.rocketRequested(), mode+" honors lower height boundary");
            var selection = c.selection(true);
            check(selection.look().equals(low) && selection.dualActive() && Math.abs(selection.boxLook().y)<1e-6,
                    mode+" boost preserves requested pitch with an explicit second view");
            check(!c.selection(false).dualActive(), mode+" unsupported protocol uses single view");
            Vec3 empty = c.select(mode,2,150,45,new Vec3(0,-3,4),80,true,true,false);
            check(empty.y>0 && empty.y<.1 && !c.rocketRequested(), mode+" fuel exhaustion cancels the dive and requests shallow glide");
        }
        c.reset();
        Vec3 first = c.select(TravelCruise.Mode.Pitch40Optimized,0,200,0,new Vec3(0,0,2),400,true,true);
        check(first.y < -.99, "optimized waveform begins at upstream first sample");
        for(int tick=1; tick<=6; tick++) c.select(TravelCruise.Mode.Pitch40Optimized,tick,200,0,Vec3.ZERO,400,true,true);
        check(Math.abs(c.selection(false).look().y + Math.sin(Math.toRadians(35.51)))<1e-4, "optimized waveform advances once per tick");
        check(c.select(TravelCruise.Mode.Pitch40Optimized,30,200,90,new Vec3(0,0,2),400,true,true).y<-.99, "long gap resets waveform");
        for(var mode : new TravelCruise.Mode[]{TravelCruise.Mode.Pitch40,TravelCruise.Mode.Pitch40Optimized}) {
            c.reset();
            check(c.select(mode,0,161,0,new Vec3(.1,-.08,0),80,true,true).y>0 && c.rocketRequested(),
                    mode+" starts with a pull-up when wings have just opened");
        }
        System.out.println("TravelCruiseTest passed: "+checks+" checks");
    }
    static void check(boolean value,String message) { checks++; if (!value) throw new AssertionError(message); }
}

