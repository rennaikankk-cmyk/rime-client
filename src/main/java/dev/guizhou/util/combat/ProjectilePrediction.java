package dev.guizhou.util.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;

/** Client-thread world adapter shared by rendering and combat; never reads a world asynchronously. */
public final class ProjectilePrediction {
    public static ProjectileSim.Result simulate(Projectile projectile, int ticks) {
        Vec3 p = projectile.position(), v = projectile.getDeltaMovement();
        return ProjectileSim.simulate(p.x, p.y, p.z, v.x, v.y, v.z, profile(projectile),
                Math.clamp(ticks, 1, 200), projectile.level().getMinY() - 8, collider(projectile.level(), projectile));
    }

    public static ProjectileSim.Profile profile(Projectile projectile) {
        if (projectile instanceof ThrownTrident) return ProjectileSim.TRIDENT;
        if (projectile instanceof AbstractArrow) return ProjectileSim.ARROW;
        if (projectile instanceof AbstractThrownPotion) return ProjectileSim.POTION;
        if (projectile instanceof ThrowableItemProjectile item && item.getItem().is(Items.EXPERIENCE_BOTTLE))
            return ProjectileSim.XP_BOTTLE;
        return ProjectileSim.THROWABLE;
    }

    public static ProjectileSim.Collider collider(Level level, Entity source) {
        return new ProjectileSim.Collider() {
            private int step;
            public double hit(double x0, double y0, double z0, double x1, double y1, double z1) {
                int age = (source instanceof Projectile ? source.tickCount : 0) + step++;
                // Vanilla 1.21.11 grows the entity hit margin during a projectile's first ticks.
                double margin = Math.clamp((age - 2) / 20.0, 0.0, 0.3);
                Vec3 from = new Vec3(x0, y0, z0), to = new Vec3(x1, y1, z1);
                double length = from.distanceTo(to);
                if (length < 1e-9) return -1;
                double best = -1;
                BlockHitResult block = level.clipIncludingBorder(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE, source));
                if (block.getType() == HitResult.Type.BLOCK) best = block.getLocation().distanceTo(from) / length;
                for (Entity e : level.getEntities(source, new AABB(from, to).inflate(1.0),
                        e -> e.isAlive() && !e.isSpectator() && e.isPickable()
                                && !(e instanceof Projectile)
                                && !(source instanceof Projectile p && e == p.getOwner()))) {
                    AABB box = e.getBoundingBox().inflate(margin);
                    double fraction;
                    if (box.contains(from)) fraction = 0;
                    else {
                        var clip = box.clip(from, to);
                        if (clip.isEmpty()) continue;
                        fraction = clip.get().distanceTo(from) / length;
                    }
                    if (best < 0 || fraction < best) best = fraction;
                }
                return best;
            }

            public boolean inWater(double x, double y, double z) {
                return level.getFluidState(BlockPos.containing(x, y, z)).is(FluidTags.WATER);
            }
        };
    }

    private ProjectilePrediction() {}
}
