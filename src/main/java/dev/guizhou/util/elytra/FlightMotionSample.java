package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;

/** Consecutive actual displacement, excluding teleports, collisions and external impulses. */
public final class FlightMotionSample {
    private Vec3 origin;
    private int tick = Integer.MIN_VALUE;
    private boolean eligible;

    public void reset() { origin=null;tick=Integer.MIN_VALUE;eligible=false; }

    public Vec3 sample(Vec3 position, int now, Vec3 fallback, boolean safe) {
        Vec3 result=fallback;
        if (safe && eligible && origin!=null && (long)now-tick==1 && finite(position)) {
            Vec3 delta=position.subtract(origin);
            if (finite(delta) && delta.lengthSqr()<=900) result=delta;
        }
        origin=finite(position)?position:null;
        tick=now;
        eligible=safe && origin!=null;
        return result;
    }

    private static boolean finite(Vec3 v) {
        return v!=null&&Double.isFinite(v.x)&&Double.isFinite(v.y)&&Double.isFinite(v.z);
    }
}
