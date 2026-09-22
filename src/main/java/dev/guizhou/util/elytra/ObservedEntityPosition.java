package dev.guizhou.util.elytra;

import dev.guizhou.mixin.entity.LivingEntityInterpolationAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class ObservedEntityPosition {
    private ObservedEntityPosition() {}
    public static Vec3 offset(Entity entity) {
        Vec3 delta = rawOffset(entity);
        // Match the native-version attack safety bound for teleports/discontinuities.
        return Double.isFinite(delta.lengthSqr()) && delta.lengthSqr() <= 144.0 ? delta : Vec3.ZERO;
    }

    /** Uncapped received-minus-rendered delta; callers own discontinuity policy. */
    public static Vec3 rawOffset(Entity entity) {
        if (!(entity instanceof LivingEntityInterpolationAccessor lerp) || lerp.guizhouware$lerpSteps() <= 0) return Vec3.ZERO;
        Vec3 delta = new Vec3(lerp.guizhouware$lerpX(), lerp.guizhouware$lerpY(), lerp.guizhouware$lerpZ()).subtract(entity.position());
        return Double.isFinite(delta.lengthSqr()) ? delta : Vec3.ZERO;
    }
}
