package dev.guizhou.util.elytra;

import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 1.21.1 spear combat adapter. The public surface mirrors the 1.21.11 native
 * implementation so the chase module and tests keep compiling; the internals resolve
 * spears through {@link SpearCapability1211} (ViaBackwards-translated metadata with
 * server overrides, native registry defaults otherwise). Held-charge contact combat
 * is the only supported path; the stab packet is never emitted.
 */
public final class SpearCombat {
    private SpearCombat() {
    }

    public static boolean isSpear(ItemStack stack) {
        return spec(stack) != null;
    }

    public static int chargeTicks(ItemStack stack) {
        SpearCapability1211.Spec weapon = spec(stack);
        return weapon == null ? 0 : weapon.delayTicks();
    }

    /**
     * Last usable damage tick measured from the start of item use, inclusive.
     * Native semantics: delay + active damage window (computeDamageUseDuration).
     */
    public static int damageDurationTicks(ItemStack stack) {
        SpearCapability1211.Spec weapon = spec(stack);
        return weapon == null || weapon.damage() == null ? 0
                : (int)Math.min(Integer.MAX_VALUE,(long)weapon.delayTicks()+weapon.activeDurationTicks());
    }

    /** Whether charge is complete and its damage window is still open. */
    public static boolean isReady(Player player) {
        if (player == null || !player.isUsingItem()) return false;
        SpearCapability1211.Spec weapon = spec(player.getUseItem());
        return weapon != null && weapon.damage() != null
                && inUseWindow(player.getTicksUsingItem(), weapon.delayTicks(), weapon.activeDurationTicks());
    }

    public static boolean hasDamageWindowExpired(Player player) {
        if (player == null || !player.isUsingItem()) return false;
        SpearCapability1211.Spec weapon = spec(player.getUseItem());
        return weapon != null && weapon.damage() != null
                && player.getTicksUsingItem() > (long) weapon.delayTicks() + weapon.activeDurationTicks();
    }

    /** Estimate for route planning only; the weapon controller still owns starting use. */
    public static int readyInTicks(Player player) {
        if (player == null) return Integer.MAX_VALUE;
        ItemStack stack = player.isUsingItem() ? player.getUseItem() : player.getMainHandItem();
        SpearCapability1211.Spec weapon = spec(stack);
        if (weapon == null || weapon.damage() == null) return Integer.MAX_VALUE;
        return remainingCharge(player.isUsingItem(), player.getTicksUsingItem(),
                weapon.delayTicks(), weapon.activeDurationTicks());
    }

    static int remainingCharge(boolean using, int useTicks, int delayTicks, int durationTicks) {
        if (delayTicks < 0 || durationTicks < 0 || useTicks < 0) return Integer.MAX_VALUE;
        long left = !using || useTicks > (long) delayTicks + durationTicks
                ? (long) delayTicks + 1 : Math.max(0L, (long) delayTicks - useTicks);
        return (int) Math.min(Integer.MAX_VALUE, left);
    }

    public static double minRange(Player player, ItemStack stack) {
        SpearCapability1211.Spec weapon = spec(stack);
        if (weapon == null || player == null) return 0.0;
        return player.getAbilities().instabuild ? weapon.minCreativeRange() : weapon.minRange();
    }

    public static double maxRange(Player player, ItemStack stack) {
        SpearCapability1211.Spec weapon = spec(stack);
        if (weapon == null || player == null) return 0.0;
        return player.getAbilities().instabuild ? weapon.maxCreativeRange() : weapon.maxRange();
    }

    /** Armor flight keeps a local glide pose across the owned wing/chestplate cycle;
     * the server evaluates kinetic contact against standing dimensions. Aim from that
     * attack origin; ordinary gliding keeps its real eye. */
    public static Vec3 attackEye(Player player, boolean armorSwapActive) {
        return selectAttackEye(player.position(), player.getEyePosition(),
                player.getEyeHeight(Pose.STANDING), armorSwapActive, player.getPose() == Pose.FALL_FLYING);
    }

    static Vec3 selectAttackEye(Vec3 position, Vec3 localEye, double standingEyeHeight,
                               boolean armorSwapActive, boolean localGlidePose) {
        return armorSwapActive && localGlidePose
                ? position.add(0.0, standingEyeHeight, 0.0) : localEye;
    }

    /** All present kinetic effects, their active windows, and the unobstructed contact ray. */
    public static boolean threatens(Player attacker, Player defender, double extraReach, double extraMargin) {
        if (attacker == null || defender == null || attacker == defender
                || attacker.level() != defender.level() || !attacker.isUsingItem()
                || !attacker.isAlive() || !defender.isAlive() || attacker.isSpectator()
                || defender.isSpectator() || attacker.isPassengerOfSameVehicle(defender)) return false;
        SpearCapability1211.Spec weapon = spec(attacker.getUseItem());
        if (weapon == null || attacker.getTicksUsingItem() < weapon.delayTicks()) return false;

        Vec3 look = attacker.getLookAngle();
        Vec3 attackerMotion = knownMotion(attacker);
        Vec3 defenderMotion = knownMotion(defender);
        double speedPerSecond = look.dot(attackerMotion) * 20.0;
        double relativeSpeedPerSecond = Math.max(0.0, look.dot(attackerMotion) - look.dot(defenderMotion)) * 20.0;
        if (!weapon.threatens(attacker.getTicksUsingItem(),speedPerSecond,relativeSpeedPerSecond)) return false;

        double reach = nonnegativeFinite(extraReach);
        double margin = weapon.hitboxMargin() + nonnegativeFinite(extraMargin);
        double maxRange = (attacker.getAbilities().instabuild ? weapon.maxCreativeRange() : weapon.maxRange()) + reach;
        double movementExtension = Math.max(0.0, attackerMotion.dot(look));
        double minRange = attacker.getAbilities().instabuild ? weapon.minCreativeRange() : weapon.minRange();
        Vec3 eye=attacker.getEyePosition().add(ObservedEntityPosition.offset(attacker));
        AABB body=defender.getBoundingBox().move(ObservedEntityPosition.offset(defender));
        if (!intersectsRay(eye,look,body,minRange,maxRange+movementExtension,margin)) return false;
        Vec3 start=eye.add(look.scale(minRange)), end=eye.add(look.scale(maxRange+movementExtension));
        AABB expanded=body.inflate(margin);
        Vec3 contact=expanded.contains(start)?start:expanded.clip(start,end).orElse(null);
        if (contact==null) return false;
        return attacker.level().clip(new net.minecraft.world.level.ClipContext(eye,contact,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE,attacker)).getType()
                == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    private record MotionSample(Vec3 position,int tick,Vec3 velocity) {}
    private static final java.util.Map<Player,MotionSample> MOTION = new java.util.WeakHashMap<>();

    /** Old RemotePlayer delta movement often omits position-packet motion. */
    private static Vec3 knownMotion(Player player) {
        if (player instanceof net.minecraft.client.player.LocalPlayer) return player.getDeltaMovement();
        Vec3 position=player.position().add(ObservedEntityPosition.offset(player));
        MotionSample previous=MOTION.get(player);
        int tick=player.tickCount;
        if (previous!=null && tick==previous.tick()) return previous.velocity();
        Vec3 velocity=player.getDeltaMovement();
        if (previous!=null) {
            int elapsed=tick-previous.tick();
            Vec3 delta=position.subtract(previous.position());
            velocity=elapsed>0 && elapsed<=5 && finite(delta) && delta.lengthSqr()<=400.0*elapsed*elapsed
                    ? delta.scale(1.0/elapsed):Vec3.ZERO;
        }
        if (!finite(velocity) || velocity.lengthSqr()>400.0) velocity=Vec3.ZERO;
        MOTION.put(player,new MotionSample(position,tick,velocity));
        return velocity;
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

    private static SpearCapability1211.Spec spec(ItemStack stack) {
        return SpearCapability1211.detect(stack);
    }

    private static double nonnegativeFinite(double value) {
        return Double.isFinite(value) && value > 0.0 ? Math.min(value, Float.MAX_VALUE / 2.0) : 0.0;
    }

    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x)
                && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}
