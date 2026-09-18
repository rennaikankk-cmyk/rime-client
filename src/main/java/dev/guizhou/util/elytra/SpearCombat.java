package dev.guizhou.util.elytra;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.item.component.KineticWeapon;
import net.minecraft.world.item.component.PiercingWeapon;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

public final class SpearCombat {
    private SpearCombat() {
    }

    public static boolean isSpear(ItemStack stack) {
        return kineticWeapon(stack) != null;
    }

    public static int chargeTicks(ItemStack stack) {
        KineticWeapon weapon = kineticWeapon(stack);
        return weapon == null ? 0 : weapon.delayTicks();
    }

    /** Last usable damage tick measured from the start of item use, inclusive. */
    public static int damageDurationTicks(ItemStack stack) {
        KineticWeapon weapon = kineticWeapon(stack);
        return weapon == null ? 0 : weapon.computeDamageUseDuration();
    }

    /** Whether charge is complete and its damage window is still open. */
    public static boolean isReady(Player player) {
        if (player == null || !player.isUsingItem()) return false;
        KineticWeapon weapon = kineticWeapon(player.getUseItem());
        return weapon != null && weapon.damageConditions().isPresent()
                && inUseWindow(player.getTicksUsingItem(), weapon.delayTicks(),
                weapon.damageConditions().get().maxDurationTicks());
    }

    public static boolean hasDamageWindowExpired(Player player) {
        if (player == null || !player.isUsingItem()) return false;
        KineticWeapon weapon = kineticWeapon(player.getUseItem());
        return weapon != null && weapon.damageConditions().isPresent()
                && player.getTicksUsingItem() > (long) weapon.delayTicks()
                + weapon.damageConditions().get().maxDurationTicks();
    }

    public static double minRange(Player player, ItemStack stack) {
        return player == null ? 0.0 : attackRange(player, stack).effectiveMinRange(player);
    }

    public static double maxRange(Player player, ItemStack stack) {
        return player == null ? 0.0 : attackRange(player, stack).effectiveMaxRange(player);
    }

    /** Local threat estimate using the same component conditions and ray cast as kinetic attacks. */
    public static boolean threatens(Player attacker, Player defender, double extraReach, double extraMargin) {
        if (attacker == null || defender == null || attacker == defender
                || attacker.level() != defender.level() || !attacker.isUsingItem()
                || !attacker.isAlive() || !defender.isAlive()
                || !PiercingWeapon.canHitEntity(attacker, defender)) return false;
        KineticWeapon weapon = kineticWeapon(attacker.getUseItem());
        if (weapon == null || attacker.getTicksUsingItem() < weapon.delayTicks()) return false;

        int activeTicks = attacker.getTicksUsingItem() - weapon.delayTicks();
        Vec3 look = attacker.getLookAngle();
        double attackerSpeed = look.dot(KineticWeapon.getMotion(attacker));
        double relativeSpeed = Math.max(0.0, attackerSpeed - look.dot(KineticWeapon.getMotion(defender)));
        if (!satisfies(weapon.damageConditions(), activeTicks, attackerSpeed, relativeSpeed)
                && !satisfies(weapon.knockbackConditions(), activeTicks, attackerSpeed, relativeSpeed)
                && !satisfies(weapon.dismountConditions(), activeTicks, attackerSpeed, relativeSpeed)) return false;

        AttackRange actual = attackRange(attacker, attacker.getUseItem());
        float reach = (float) nonnegativeFinite(extraReach);
        float margin = (float) nonnegativeFinite(extraMargin);
        AttackRange defensive = new AttackRange(actual.minRange(), actual.maxRange() + reach,
                actual.minCreativeRange(), actual.maxCreativeRange() + reach,
                actual.hitboxMargin() + margin, actual.mobFactor());
        Vec3 rayDirection = attacker.getHeadLookAngle();
        double movementExtension = Math.max(0.0, attacker.getKnownMovement().dot(rayDirection));
        if (!intersectsRay(attacker.getEyePosition(), rayDirection, defender.getBoundingBox(),
                defensive.effectiveMinRange(attacker), defensive.effectiveMaxRange(attacker) + movementExtension,
                defensive.hitboxMargin())) return false;

        // Vanilla also clips the margin-to-hitbox segment, preventing hits through wall edges.
        return ProjectileUtil.getHitEntitiesAlong(attacker, defensive,
                entity -> entity == defender, ClipContext.Block.COLLIDER)
                .right().map(hits -> !hits.isEmpty()).orElse(false);
    }

    static boolean inUseWindow(int useTicks, int delayTicks, int activeDurationTicks) {
        return delayTicks >= 0 && activeDurationTicks >= 0
                && useTicks >= delayTicks && useTicks <= (long) delayTicks + activeDurationTicks;
    }

    static boolean intersectsRay(Vec3 origin, Vec3 direction, AABB box,
                                 double minRange, double maxRange, double margin) {
        if (!finite(origin) || !finite(direction) || box == null
                || !Double.isFinite(minRange) || !Double.isFinite(maxRange)
                || !Double.isFinite(margin) || minRange < 0.0 || maxRange < minRange || margin < 0.0
                || direction.lengthSqr() < 1.0E-12) return false;
        Vec3 unit = direction.normalize();
        Vec3 start = origin.add(unit.scale(minRange));
        Vec3 end = origin.add(unit.scale(maxRange));
        AABB expanded = box.inflate(margin);
        return expanded.contains(start) || expanded.clip(start, end).isPresent();
    }

    private static KineticWeapon kineticWeapon(ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : stack.get(DataComponents.KINETIC_WEAPON);
    }

    private static AttackRange attackRange(Player player, ItemStack stack) {
        AttackRange range = stack == null || stack.isEmpty() ? null : stack.get(DataComponents.ATTACK_RANGE);
        return range == null ? AttackRange.defaultFor(player) : range;
    }

    private static boolean satisfies(Optional<KineticWeapon.Condition> condition, int ticks,
                                     double attackerSpeed, double relativeSpeed) {
        return condition.isPresent() && condition.get().test(ticks, attackerSpeed, relativeSpeed, 1.0);
    }

    private static double nonnegativeFinite(double value) {
        return Double.isFinite(value) && value > 0.0 ? Math.min(value, Float.MAX_VALUE / 2.0) : 0.0;
    }

    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x)
                && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}
