package dev.guizhou.util;

import dev.guizhou.util.traits.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownExperienceBottle;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

public class InteractionUtil implements Util {
    public static boolean canBreak(BlockPos blockPos, BlockState state) {
        if (!mc.player.isCreative() && state.getDestroySpeed(mc.level, blockPos) < 0) return false;
        return state.getShape(mc.level, blockPos) != Shapes.empty();
    }

    public static boolean isPlaceable(BlockPos pos, boolean entityCheck) {
        if (!mc.level.getBlockState(pos).canBeReplaced()) return false;
        for (Entity e : mc.level.getEntitiesOfClass(Entity.class, new AABB(pos), e -> !(e instanceof ThrownExperienceBottle || e instanceof ItemEntity || e instanceof ExperienceOrb))) {
            if (e instanceof Player) return false;
            return !entityCheck;
        }
        return true;
    }

    public static boolean breakBlock(BlockPos pos) {
        if (!canBreak(pos, mc.level.getBlockState(pos))) return false;
        BlockPos bp = pos instanceof BlockPos.MutableBlockPos ? new BlockPos(pos) : pos;

        mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, bp, Direction.UP));
        mc.player.swing(InteractionHand.MAIN_HAND);
        mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, bp, Direction.UP));

        mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));

        return true;
    }

    public static void useItem(BlockPos pos) {
        useItem(pos, InteractionHand.MAIN_HAND);
    }

    public static void useItem(BlockPos pos, InteractionHand hand) {
        if (mc.level == null || mc.player == null || mc.gameMode == null) return;
        Direction direction = mc.hitResult != null ? ((BlockHitResult) mc.hitResult).getDirection() : Direction.DOWN;
        InteractionResult result = mc.gameMode.useItemOn(mc.player, hand, new BlockHitResult(
                Vec3.atCenterOf(pos), direction, pos, false
        ));
        if (result != null && (result.consumesAction() || result == net.minecraft.world.InteractionResult.SUCCESS)) {
            mc.player.connection.send(new ServerboundSwingPacket(hand));
        }
    }

    public static boolean place(BlockPos pos, boolean airPlace) {
        return place(pos, airPlace, InteractionHand.MAIN_HAND);
    }

    public static boolean place(BlockPos pos, boolean airPlace, InteractionHand hand) {
        if (mc.level == null || mc.player == null || mc.gameMode == null) return false;
        if (!isPlaceable(pos, false)) return false;
        Direction direction = calcSide(pos);
        if (direction == null) {
            if (airPlace)
                direction = mc.hitResult != null ? ((BlockHitResult) mc.hitResult).getDirection() : Direction.DOWN;
            else return false;
        }
        BlockPos bp = airPlace ? pos : pos.relative(direction);
        InteractionResult result = mc.gameMode.useItemOn(mc.player, hand, new BlockHitResult(
                airPlace ? Vec3.atCenterOf(pos) : Vec3.atCenterOf(bp).relative(direction.getOpposite(), 0.5),
                airPlace ? direction : direction.getOpposite(), bp, false
        ));
        if (result != null && (result.consumesAction() || result == net.minecraft.world.InteractionResult.SUCCESS)) {
            mc.player.connection.send(new ServerboundSwingPacket(hand));
        }
        return true;
    }

    public static Direction calcSide(BlockPos pos) {
        for (Direction d : Direction.values())
            if (!mc.level.getBlockState(pos.offset(d.getNormal())).canBeReplaced()) return d;
        return null;
    }

    public static double getBlockBreakingSpeed(int slot, BlockPos pos) {
        double delta = getBreakDelta(mc.player.getInventory().getItem(slot), mc.level.getBlockState(pos), pos);
        return delta <= 0 ? Double.POSITIVE_INFINITY : Math.ceil(1.0 / delta) * 50.0;
    }

    public static double getBlockBreakingSpeed(int slot, BlockState block) {
        double speed = rawMiningSpeed(slot, block, mc.player.onGround());

        float hardness = block.getDestroySpeed(mc.level, mc.player.blockPosition());
        if (hardness < 0 || speed <= 0) return Double.POSITIVE_INFINITY;
        if (hardness == 0) return 0;
        double delta = speed / hardness / (!block.requiresCorrectToolForDrops()
                || mc.player.getInventory().getItem(slot).isCorrectToolForDrops(block) ? 30 : 100);
        return Math.ceil(1.0 / delta) * 50.0;
    }

    public static double rawMiningSpeed(int slot, BlockState block, boolean onGround) {
        return miningSpeed(mc.player.getInventory().getItem(slot), block, onGround);
    }

    private static float miningSpeed(ItemStack stack, BlockState state, boolean onGround) {
        float speed = stack.getDestroySpeed(state);
        if (speed > 1) speed += (float) miningAttribute(Attributes.MINING_EFFICIENCY, stack);

        if (MobEffectUtil.hasDigSpeed(mc.player)) {
            speed *= 1 + (MobEffectUtil.getDigSpeedAmplification(mc.player) + 1) * 0.2F;
        }

        if (mc.player.hasEffect(MobEffects.DIG_SLOWDOWN)) {
            float k = switch (mc.player.getEffect(MobEffects.DIG_SLOWDOWN).getAmplifier()) {
                case 0 -> 0.3F;
                case 1 -> 0.09F;
                case 2 -> 0.0027F;
                default -> 8.1E-4F;
            };

            speed *= k;
        }

        speed *= (float) miningAttribute(Attributes.BLOCK_BREAK_SPEED, stack);
        if (mc.player.isEyeInFluid(FluidTags.WATER))
            speed *= (float) miningAttribute(Attributes.SUBMERGED_MINING_SPEED, stack);

        if (!onGround) {
            speed /= 5.0F;
        }

        return speed;
    }

    /** Models a hypothetical main-hand tool without changing the live inventory or attributes. */
    private static double miningAttribute(Holder<Attribute> attribute, ItemStack tool) {
        AttributeInstance live = mc.player.getAttribute(attribute);
        if (live == null) return attribute.value().getDefaultValue();
        ItemStack held = mc.player.getMainHandItem();
        if (tool == held) return live.getValue();
        AttributeInstance estimate = new AttributeInstance(attribute, ignored -> {});
        estimate.replaceFrom(live);
        held.forEachModifier(EquipmentSlot.MAINHAND, (type, modifier) -> {
            if (type.equals(attribute)) estimate.removeModifier(modifier.id());
        });
        tool.forEachModifier(EquipmentSlot.MAINHAND, (type, modifier) -> {
            if (type.equals(attribute)) estimate.addOrUpdateTransientModifier(modifier);
        });
        return estimate.getValue();
    }

    /**
     * Per-tick break progress for mining {@code state} at {@code pos} with {@code stack} held,
     * as a fraction of the block (so {@code 1 / delta} ticks to break it).
     */
    public static float getBreakDelta(ItemStack stack, BlockState state, BlockPos pos) {
        float hardness = state.getDestroySpeed(mc.level, pos);
        if (hardness < 0) return 0;
        if (hardness == 0) return 1;
        float speed = miningSpeed(stack, state, mc.player.onGround());

        boolean harvestable = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
        return speed / hardness / (harvestable ? 30 : 100);
    }

    public static double breakDelta(double speed, BlockState block) {
        float hardness = block.getDestroySpeed(null, null);
        if (hardness == -1) return 0;

        return speed / hardness / 30.0;
    }

    public static int fastestToolSlot(BlockState block) {
        int best = mc.player.getInventory().selected;
        double bestSpeed = -1;

        for (int i = 0; i < 9; i++) {
            double speed = rawMiningSpeed(i, block, true);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = i;
            }
        }

        return best;
    }

    public static Direction right(Direction direction) {
        return switch (direction) {
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.NORTH;
            case NORTH -> Direction.EAST;
            default -> throw new IllegalStateException("Unexpected value: " + direction);
        };
    }

    public static Direction left(Direction direction) {
        return switch (direction) {
            case EAST -> Direction.NORTH;
            case NORTH -> Direction.WEST;
            case WEST -> Direction.SOUTH;
            case SOUTH -> Direction.EAST;
            default -> throw new IllegalStateException("Unexpected value: " + direction);
        };
    }
}
