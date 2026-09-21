package dev.guizhou.util;

import dev.guizhou.mixin.entity.ColorParticleOptionAccessor;
import dev.guizhou.mixin.entity.LivingEntityEffectParticlesAccessor;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

public final class DamageSyncTracker {

    private DamageSyncTracker() {}

    private static final int RESISTANCE_COLOR = MobEffects.DAMAGE_RESISTANCE.value().getColor() & 0xFFFFFF;

    private static final int SLOWNESS_COLOR = MobEffects.MOVEMENT_SLOWDOWN.value().getColor() & 0xFFFFFF;

    private static final int STRENGTH_COLOR = MobEffects.DAMAGE_BOOST.value().getColor() & 0xFFFFFF;

    private static final int FULL_HIT_THRESHOLD = 10;

    private static boolean hasEffectColor(LivingEntity entity, int rgb) {
        List<ParticleOptions> particles = entity.getEntityData()
                .get(LivingEntityEffectParticlesAccessor.guizhouware$getDataEffectParticles());
        if (particles.isEmpty()) return false;
        for (int i = 0, n = particles.size(); i < n; i++) {
            if (particles.get(i) instanceof ColorParticleOption color
                    && (((ColorParticleOptionAccessor) (Object) color).guizhouware$getColor() & 0xFFFFFF) == rgb) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasResistance(LivingEntity entity) {
        return hasEffectColor(entity, RESISTANCE_COLOR);
    }

    public static boolean hasSlowness(LivingEntity entity) {
        return hasEffectColor(entity, SLOWNESS_COLOR);
    }

    public static boolean hasStrength(LivingEntity entity) {
        return hasEffectColor(entity, STRENGTH_COLOR);
    }

    public static boolean isTurtleMaster(LivingEntity entity) {
        return hasResistance(entity) && hasSlowness(entity);
    }

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    public static boolean hasArmorGap(LivingEntity entity) {
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            if (entity.getItemBySlot(slot).isEmpty()) return true;
        }
        return false;
    }

    public static boolean inImmunityWindow(LivingEntity entity) {
        return entity.invulnerableTime > FULL_HIT_THRESHOLD;
    }

    public static boolean shouldHold(LivingEntity entity) {
        return isTurtleMaster(entity) && !hasArmorGap(entity) && inImmunityWindow(entity);
    }
}
