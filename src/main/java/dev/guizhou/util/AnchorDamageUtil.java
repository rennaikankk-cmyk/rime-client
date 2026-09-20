package dev.guizhou.util;

import dev.guizhou.util.combat.ExplosionDamageModel;
import dev.guizhou.util.combat.CombatSnapshotService;
import dev.guizhou.util.diagnostics.ClientDiagnostics;
import dev.guizhou.util.traits.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.function.BiFunction;

/** Power-five blast model shared by anchors and beds. Call only on the client thread. */
public final class AnchorDamageUtil implements Util {
    public static final double DIAMETER = 10.0;

    private AnchorDamageUtil() { throw new AssertionError(); }

    public record Target(LivingEntity entity, Vec3 pos, AABB box, float armor, float toughness,
                         float resistMult, int protPoints) {
        public static Target of(LivingEntity living) { return of(living, false); }

        public static Target of(LivingEntity living, boolean assumeBestArmor) {
            var snapshot = CombatSnapshotService.get(living);
            return new Target(living, new Vec3(snapshot.x(), snapshot.y(), snapshot.z()),
                    new AABB(snapshot.minX(), snapshot.minY(), snapshot.minZ(), snapshot.maxX(), snapshot.maxY(), snapshot.maxZ()),
                    snapshot.armor(), snapshot.toughness(), snapshot.resistanceMultiplier(),
                    assumeBestArmor ? snapshot.assumedProtection() : snapshot.protection());
        }
    }

    public static float maxDamage(Target target, Vec3 explosionPos) {
        return targetDamage(target, target.pos().distanceTo(explosionPos), 1.0);
    }

    public static float damage(Target target, Vec3 explosionPos, BlockPos removedSource) {
        return damage(target, explosionPos, removedSource, null);
    }

    /** Both bed halves are removed before the blast; anchors remove only their own block. */
    public static float damage(Target target, Vec3 explosionPos, BlockPos removedSource, BlockPos removedOther) {
        double distance = target.pos().distanceTo(explosionPos);
        if (distance > DIAMETER || mc.level == null) return 0f;
        return targetDamage(target, distance, exposure(explosionPos, target.box(), removedSource, removedOther));
    }

    public static float selfDamage(Vec3 explosionPos, BlockPos anchorPos) {
        return mc.player == null ? 0f : damage(Target.of(mc.player), explosionPos, anchorPos);
    }

    private static float targetDamage(Target target, double distance, double exposure) {
        if (mc.level == null || distance > DIAMETER || exposure <= 0) return 0f;
        double impact = (1.0 - distance / DIAMETER) * exposure;
        if (impact <= 0) return 0f;
        float raw = (float) ((impact * impact + impact) / 2.0 * 7.0 * DIAMETER + 1.0);
        switch (mc.level.getDifficulty()) {
            case PEACEFUL -> { return 0f; }
            case EASY -> raw = Math.min(raw / 2f + 1f, raw);
            case HARD -> raw *= 1.5f;
            default -> { }
        }
        return ExplosionDamageModel.reduce(raw, target.armor(), target.toughness(),
                target.resistMult(), target.protPoints());
    }

    private static double exposure(Vec3 source, AABB box, BlockPos removedSource, BlockPos removedOther) {
        // Vanilla's bounding-box-dependent sampling avoids the old fixed 27-point approximation.
        double stepX = 1.0 / (box.getXsize() * 2.0 + 1.0);
        double stepY = 1.0 / (box.getYsize() * 2.0 + 1.0);
        double stepZ = 1.0 / (box.getZsize() * 2.0 + 1.0);
        if (stepX <= 0 || stepY <= 0 || stepZ <= 0) return 0;
        double offsetX = (1.0 - Math.floor(1.0 / stepX) * stepX) / 2.0;
        double offsetZ = (1.0 - Math.floor(1.0 / stepZ) * stepZ) / 2.0;
        ClientDiagnostics.count("combat.power5.exposure", 1);
        RayContext context = new RayContext(source);
        BiFunction<RayContext, BlockPos, BlockHitResult> tester = (ray, pos) -> {
            if (pos.equals(removedSource) || pos.equals(removedOther)) return null;
            BlockState state = mc.level.getBlockState(pos);
            if (state.isAir()) return null;
            VoxelShape shape = state.getCollisionShape(mc.level, pos);
            return shape.isEmpty() ? null : shape.clip(ray.point, ray.source, pos);
        };
        int total = 0, unblocked = 0;
        for (double x = 0; x <= 1.0; x += stepX) {
            for (double y = 0; y <= 1.0; y += stepY) {
                for (double z = 0; z <= 1.0; z += stepZ) {
                    context.point = new Vec3(box.minX + box.getXsize() * x + offsetX,
                            box.minY + box.getYsize() * y, box.minZ + box.getZsize() * z + offsetZ);
                    if (BlockGetter.traverseBlocks(context.point, source, context, tester, ray -> null) == null) unblocked++;
                    total++;
                }
            }
        }
        ClientDiagnostics.count("combat.power5.rays", total);
        return total == 0 ? 0 : (double) unblocked / total;
    }

    private static final class RayContext {
        private final Vec3 source;
        private Vec3 point;
        private RayContext(Vec3 source) { this.source = source; }
    }
}
