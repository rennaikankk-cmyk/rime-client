package dev.guizhou.util.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.util.AnchorDamageUtil;
import dev.guizhou.util.EnchantmentUtil;
import dev.guizhou.util.traits.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Worst single hit the local player could take in the next moments: loaded crystals,
 * charged anchors and beds in dimensions where they explode, crystal spots an enemy can
 * place on, the landing of the current fall, an elytra wall impact and armed enemies in reach.
 * Client thread only; the result is cached per tick because combat modules re-ask many times.
 */
public final class LethalPredictor implements Util {
    public record Options(boolean crystals, boolean placeThreat, boolean blocks, boolean fall,
                          boolean elytra, boolean melee, double enemyRange) {}

    public record Threat(float damage, String source) {
        static final Threat NONE = new Threat(0f, "");
        Threat max(float value, String name) { return value > damage ? new Threat(value, name) : this; }
    }

    private static final double CRYSTAL_DIAMETER = 12.0;
    private static final int PLACE_CANDIDATES = 24;
    private static final int BLOCK_RADIUS = 5;

    private static Object cachedLevel, cachedPlayer;
    private static int cachedTick = Integer.MIN_VALUE;
    private static Options cachedOptions;
    private static Threat cached = Threat.NONE;

    private LethalPredictor() {}

    public static Threat predict(Options options) {
        if (mc.player == null || mc.level == null || !mc.isSameThread()) return Threat.NONE;
        if (cachedLevel == mc.level && cachedPlayer == mc.player && cachedTick == mc.player.tickCount
                && options.equals(cachedOptions)) return cached;
        Threat threat = Threat.NONE;
        AnchorDamageUtil.Target self = AnchorDamageUtil.Target.of(mc.player);
        List<Player> enemies = enemies(options.enemyRange());
        if (options.crystals()) threat = crystals(self, threat);
        if (options.placeThreat() && !enemies.isEmpty()) threat = placeable(self, enemies, threat);
        if (options.blocks()) threat = explosiveBlocks(self, threat);
        if (options.fall()) threat = threat.max(fallDamage(), "Fall");
        if (options.elytra()) threat = threat.max(elytraImpact(), "Elytra");
        if (options.melee()) threat = melee(enemies, threat);
        cachedLevel = mc.level;
        cachedPlayer = mc.player;
        cachedTick = mc.player.tickCount;
        cachedOptions = options;
        cached = threat;
        return threat;
    }

    private static List<Player> enemies(double range) {
        List<Player> out = new ArrayList<>();
        double rangeSq = range * range;
        for (Player other : mc.level.players()) {
            if (other == mc.player || !other.isAlive() || other.isSpectator() || other.isCreative()) continue;
            if (GuizhouWare.friendManager.isFriend(other)) continue;
            if (other.distanceToSqr(mc.player) <= rangeSq) out.add(other);
        }
        return out;
    }

    private static Threat crystals(AnchorDamageUtil.Target self, Threat threat) {
        AABB area = mc.player.getBoundingBox().inflate(CRYSTAL_DIAMETER);
        for (EndCrystal crystal : mc.level.getEntitiesOfClass(EndCrystal.class, area)) {
            if (!crystal.isAlive()) continue;
            threat = threat.max(AnchorDamageUtil.explosionDamage(self, crystal.position(), CRYSTAL_DIAMETER, null), "Crystal");
        }
        return threat;
    }

    /** Bases an enemy could crystal right now; only the nearest few are ray-traced. */
    private static Threat placeable(AnchorDamageUtil.Target self, List<Player> enemies, Threat threat) {
        BlockPos feet = mc.player.blockPosition();
        List<BlockPos> bases = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    BlockPos base = feet.offset(dx, dy, dz);
                    BlockState state = mc.level.getBlockState(base);
                    if (!state.is(Blocks.OBSIDIAN) && !state.is(Blocks.BEDROCK)) continue;
                    BlockPos above = base.above();
                    if (!mc.level.getBlockState(above).isAir()) continue;
                    if (!reachableByEnemy(above, enemies)) continue;
                    bases.add(base);
                }
            }
        }
        Vec3 center = mc.player.position();
        bases.sort(Comparator.comparingDouble(pos -> Vec3.atBottomCenterOf(pos.above()).distanceToSqr(center)));
        int evaluated = 0;
        for (BlockPos base : bases) {
            if (evaluated >= PLACE_CANDIDATES) break;
            BlockPos above = base.above();
            // A player standing in the spot blocks the crystal item.
            if (!mc.level.getEntitiesOfClass(Player.class, new AABB(above)).isEmpty()) continue;
            evaluated++;
            Vec3 blast = Vec3.atBottomCenterOf(above);
            threat = threat.max(AnchorDamageUtil.explosionDamage(self, blast, CRYSTAL_DIAMETER, null), "Crystal spot");
        }
        return threat;
    }

    private static boolean reachableByEnemy(BlockPos pos, List<Player> enemies) {
        Vec3 target = Vec3.atCenterOf(pos);
        for (Player enemy : enemies) {
            if (enemy.getEyePosition().distanceToSqr(target) <= 6.5 * 6.5) return true;
        }
        return false;
    }

    private static Threat explosiveBlocks(AnchorDamageUtil.Target self, Threat threat) {
        BlockPos feet = mc.player.blockPosition();
        boolean anchors = !mc.level.environmentAttributes().getValue(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS, feet);
        boolean beds = mc.level.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, feet).explodes();
        if (!anchors && !beds) return threat;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -BLOCK_RADIUS; dx <= BLOCK_RADIUS; dx++) {
            for (int dy = -BLOCK_RADIUS; dy <= BLOCK_RADIUS; dy++) {
                for (int dz = -BLOCK_RADIUS; dz <= BLOCK_RADIUS; dz++) {
                    cursor.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    BlockState state = mc.level.getBlockState(cursor);
                    if (anchors && state.is(Blocks.RESPAWN_ANCHOR)
                            && state.getValue(RespawnAnchorBlock.CHARGE) > 0) {
                        BlockPos pos = cursor.immutable();
                        threat = threat.max(AnchorDamageUtil.damage(self, Vec3.atCenterOf(pos), pos), "Anchor");
                    } else if (beds && state.is(BlockTags.BEDS)) {
                        BlockPos pos = cursor.immutable();
                        BlockPos other = pos.relative(BedBlock.getConnectedDirection(state));
                        threat = threat.max(AnchorDamageUtil.damage(self, Vec3.atCenterOf(pos), pos, other), "Bed");
                    }
                }
            }
        }
        return threat;
    }

    private static float fallDamage() {
        var player = mc.player;
        if (player.onGround() || player.isFallFlying() || player.isInWater() || player.isInLava()
                || player.getAbilities().flying || player.onClimbable()
                || player.getDeltaMovement().y >= 0 && player.fallDistance <= 0) return 0f;
        if (player.hasEffect(MobEffects.SLOW_FALLING) || player.hasEffect(MobEffects.LEVITATION)) return 0f;
        BlockPos.MutableBlockPos cursor = player.blockPosition().mutable();
        int minY = mc.level.getMinY();
        double landingY = Double.NaN;
        float multiplier = 1f;
        for (int i = 0; i < 96 && cursor.getY() >= minY; i++) {
            BlockState state = mc.level.getBlockState(cursor);
            if (!state.getFluidState().isEmpty()) return 0f;
            if (state.is(Blocks.SLIME_BLOCK) || state.is(Blocks.COBWEB) || state.is(Blocks.POWDER_SNOW)
                    || state.is(Blocks.SCAFFOLDING) || state.is(BlockTags.CLIMBABLE)) return 0f;
            var shape = state.getCollisionShape(mc.level, cursor);
            if (!shape.isEmpty()) {
                landingY = cursor.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
                if (state.is(Blocks.HAY_BLOCK) || state.is(Blocks.HONEY_BLOCK)) multiplier = 0.2f;
                else if (state.is(BlockTags.BEDS)) multiplier = 0.5f;
                break;
            }
            cursor.move(0, -1, 0);
        }
        if (Double.isNaN(landingY) || landingY > player.getY()) return 0f;
        double predicted = player.fallDistance + (player.getY() - landingY);
        float raw = TotemSafety.fallDamage(predicted, player.getAttributeValue(Attributes.SAFE_FALL_DISTANCE),
                player.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER)) * multiplier;
        return TotemSafety.fallAfterReductions(raw, protectionLevels(),
                EnchantmentUtil.getLevel(Enchantments.FEATHER_FALLING, player.getItemBySlot(EquipmentSlot.FEET)),
                resistance());
    }

    /** Kinetic damage only matters when the next few ticks of motion run into a wall. */
    private static float elytraImpact() {
        var player = mc.player;
        if (!player.isFallFlying()) return 0f;
        Vec3 motion = player.getDeltaMovement();
        double horizontal = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        if (horizontal < 0.3) return 0f;
        Vec3 start = player.position().add(0, 0.3, 0);
        Vec3 end = start.add(motion.x * 3.0, 0, motion.z * 3.0);
        BlockHitResult hit = mc.level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) return 0f;
        return TotemSafety.kineticDamage(horizontal) * resistance();
    }

    private static Threat melee(List<Player> enemies, Threat threat) {
        float armor = (float) mc.player.getAttributeValue(Attributes.ARMOR);
        float toughness = (float) mc.player.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        int protection = protectionLevels();
        for (Player enemy : enemies) {
            if (enemy.distanceToSqr(mc.player) > 6.0 * 6.0) continue;
            ItemStack weapon = enemy.getMainHandItem();
            boolean mace = weapon.is(Items.MACE);
            if (!mace && !weapon.is(ItemTags.SWORDS) && !weapon.is(ItemTags.AXES) && !weapon.is(Items.TRIDENT)) continue;
            ItemAttributeModifiers modifiers = weapon.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
            double base = modifiers.compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND);
            MobEffectInstance strength = enemy.getEffect(MobEffects.STRENGTH);
            if (strength != null) base += 3.0 * (strength.getAmplifier() + 1);
            boolean airborne = !enemy.onGround() && !enemy.isFallFlying();
            float smash = 0f;
            if (mace && airborne) {
                double fall = enemy.fallDistance;
                smash = TotemSafety.maceFallBonus(fall)
                        + (fall > 1.5 ? (float) (0.5 * EnchantmentUtil.getLevel(Enchantments.DENSITY, weapon) * fall) : 0f);
            }
            float raw = TotemSafety.meleeRaw(base, EnchantmentUtil.getLevel(Enchantments.SHARPNESS, weapon), airborne, smash);
            float reduced = ExplosionDamageModel.reduce(raw, armor, toughness, resistance(), protection);
            threat = threat.max(reduced, mace ? "Mace" : "Melee");
        }
        return threat;
    }

    private static int protectionLevels() {
        int total = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            total += EnchantmentUtil.getLevel(Enchantments.PROTECTION, mc.player.getItemBySlot(slot));
        }
        return total;
    }

    private static float resistance() {
        MobEffectInstance effect = mc.player.getEffect(MobEffects.RESISTANCE);
        return ExplosionDamageModel.resistanceMultiplier(effect == null ? -1 : effect.getAmplifier());
    }
}
