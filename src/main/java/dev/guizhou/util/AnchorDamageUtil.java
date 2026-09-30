package dev.guizhou.util;

import dev.guizhou.util.combat.ExplosionDamageModel;
import dev.guizhou.util.combat.CombatSnapshotService;
import dev.guizhou.util.combat.ExplosionExposure;
import dev.guizhou.util.diagnostics.ClientDiagnostics;
import dev.guizhou.util.traits.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/** Power-five blast model shared by anchors and beds. Call only on the client thread. */
public final class AnchorDamageUtil implements Util {
    public static final double DIAMETER = 10.0;

    private AnchorDamageUtil() { throw new AssertionError(); }

    public record Target(LivingEntity entity, Vec3 pos, AABB box, float armor, float toughness,
                         float resistMult, int protPoints) {
        public Target at(Vec3 position) {
            return new Target(entity, position, box.move(position.subtract(pos)), armor, toughness, resistMult, protPoints);
        }
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

    public static float maxDamage(Target target, Vec3 explosionPos, double diameter) {
        return targetDamage(target, target.pos().distanceTo(explosionPos), 1.0, diameter);
    }

    /** One synchronous search. Discard before sending an action; final safety checks stay fresh. */
    public static final class Calculation {
        private final ExplosionExposure exposure = new ExplosionExposure();
        private final Map<LivingEntity, Target> targets = new IdentityHashMap<>();
        private final Map<DamageKey, Float> damages = new HashMap<>();

        public Target target(LivingEntity entity) {
            return targets.computeIfAbsent(entity, Target::of);
        }

        public float damage(Target target, Vec3 center, double diameter,
                            BlockPos removed, BlockPos other, BlockPos solidBase) {
            DamageKey key = new DamageKey(target, center, diameter, immutable(removed), immutable(other), immutable(solidBase));
            Float cached = damages.get(key);
            if (cached != null) {
                ClientDiagnostics.count("combat.blast.damageCache.hit", 1);
                return cached;
            }
            double distance = target.pos().distanceTo(center);
            if (distance > diameter || mc.level == null) return 0;
            ClientDiagnostics.count("combat.power5.exposure", 1);
            double visible = exposure.exposure(center, target.box(), pos -> {
                if (pos.equals(key.removed()) || pos.equals(key.other())) return net.minecraft.world.phys.shapes.Shapes.empty();
                if (pos.equals(key.solidBase())) return net.minecraft.world.phys.shapes.Shapes.block();
                return exposure.shapeAt(pos);
            }, "combat.power5.rays");
            float result = targetDamage(target, distance, visible, diameter);
            if (damages.size() < 4096) damages.put(key, result);
            ClientDiagnostics.count("combat.blast.damageCache.miss", 1);
            return result;
        }

        private static BlockPos immutable(BlockPos pos) { return pos == null ? null : pos.immutable(); }
        private record DamageKey(Target target, Vec3 center, double diameter,
                                 BlockPos removed, BlockPos other, BlockPos solidBase) {}
    }

    public static float damage(Target target, Vec3 explosionPos, BlockPos removedSource) {
        return damage(target, explosionPos, removedSource, null);
    }

    /** Both bed halves are removed before the blast; anchors remove only their own block. */
    public static float damage(Target target, Vec3 explosionPos, BlockPos removedSource, BlockPos removedOther) {
        double distance = target.pos().distanceTo(explosionPos);
        if (distance > DIAMETER || mc.level == null) return 0f;
        return new Calculation().damage(target, explosionPos, DIAMETER, removedSource, removedOther, null);
    }

    public static float selfDamage(Vec3 explosionPos, BlockPos anchorPos) {
        return mc.player == null ? 0f : damage(Target.of(mc.player), explosionPos, anchorPos);
    }

    /** Same exposure model for other blast powers; an end crystal uses {@code diameter = 12}. */
    public static float explosionDamage(Target target, Vec3 explosionPos, double diameter, BlockPos removedSource) {
        return explosionDamage(target, explosionPos, diameter, removedSource, null);
    }

    /** Evaluate a planned obsidian base without mutating the live world. */
    public static float explosionDamage(Target target, Vec3 explosionPos, double diameter,
                                        BlockPos removedSource, BlockPos solidBase) {
        double distance = target.pos().distanceTo(explosionPos);
        if (distance > diameter || mc.level == null) return 0f;
        return new Calculation().damage(target, explosionPos, diameter, removedSource, null, solidBase);
    }

    private static float targetDamage(Target target, double distance, double exposure) {
        return targetDamage(target, distance, exposure, DIAMETER);
    }

    private static float targetDamage(Target target, double distance, double exposure, double diameter) {
        if (mc.level == null || distance > diameter) return 0f;
        double impact = (1.0 - distance / diameter) * exposure;
        float raw = (float) ((impact * impact + impact) / 2.0 * 7.0 * diameter + 1.0);
        switch (mc.level.getDifficulty()) {
            case PEACEFUL -> { return 0f; }
            case EASY -> raw = Math.min(raw / 2f + 1f, raw);
            case HARD -> raw *= 1.5f;
            default -> { }
        }
        return ExplosionDamageModel.reduce(raw, target.armor(), target.toughness(),
                target.resistMult(), target.protPoints());
    }

}
