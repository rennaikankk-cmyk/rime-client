package dev.guizhou.features.modules.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.TickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.HeartbeatManager;
import dev.guizhou.util.EnchantmentUtil;
import dev.guizhou.util.MathUtil;
import dev.guizhou.util.combat.ThrowEnvironment;
import dev.guizhou.util.combat.UtilityActionGuard;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.inventory.ItemUseTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Entity occupation of nearby crystal bases; independent of the existing crystal attack modes. */
public final class AntiCrystalModule extends Module {
    public enum Method { Crossbow, Firework, ItemFrame }
    private final Setting<Method> method = mode("Method", Method.Crossbow).described("用已装填的多重射击弩、无爆炸烟花或展示框占据水晶位置；会消耗物品。");
    private final Setting<Double> range = num("Range", 3.0, 1.0, 4.5).described("只保护自身附近可见的黑曜石/基岩顶面，单位格。");
    private final Setting<Integer> delay = num("DelayTicks", 10, 4, 40).described("每次提交后的等待 tick 数；有实体占位时不重复使用。");
    private final Setting<Boolean> enemyOnly = bool("EnemyOnly", true).described("附近六格有非好友玩家时才消耗物品。");
    private final Setting<Boolean> yieldCrystal = bool("YieldAutoCrystal", true).described("自动水晶开启时暂停，避免堵住自己的放置；Rusher 始终优先。");
    private int nextTick;
    private Object sessionPlayer, sessionLevel;
    private String status;
    public AntiCrystalModule() {
        super("AntiCrystal", "用实体占据周围水晶位置，巡航和空战时自动让行。", Category.COMBAT);
        displayName.setValue("防水晶占位");
    }
    @Override public void onDisable() { nextTick = 0; status = null; ItemUseTracker.cancel("AntiCrystal"); }
    @Override public String getDisplayInfo() { return status; }
    @Subscribe private void onTick(TickEvent event) {
        if (!HeartbeatManager.authorized()) { status = "授权不可用"; return; }
        if (sessionPlayer != mc.player || sessionLevel != mc.level) {
            nextTick = 0; sessionPlayer = mc.player; sessionLevel = mc.level;
        }
        status = UtilityActionGuard.pauseReason();
        if (status != null) return;
        var crystal = GuizhouWare.moduleManager.getModuleByClass(GuangxiCrystalModule.class);
        if (yieldCrystal.getValue() && crystal != null && crystal.isEnabled()) { status = "自动水晶优先"; return; }
        if (ItemUseTracker.waiting("AntiCrystal")) { status = "等待动作确认"; return; }
        if (mc.player.tickCount < nextTick) { status = "冷却"; return; }
        if (enemyOnly.getValue() && !ThrowEnvironment.nearEnemy(6)) { status = "等待敌人"; return; }
        var item = InventoryUtil.find(this::suitable, InventoryUtil.FULL_SCOPE);
        if (!item.found()) { status = "缺少可用物品"; return; }
        BlockHitResult hit = findBase();
        if (hit == null) { status = "周围无空位"; return; }
        float[] aim = MathUtil.calcAngle(mc.player.getEyePosition(), hit.getLocation());
        if (ItemUseTracker.use("AntiCrystal", item, aim[0], aim[1], method.getValue() == Method.Crossbow ? null : hit)) {
            nextTick = mc.player.tickCount + delay.getValue(); status = "已提交占位";
        } else status = "动作暂缓";
    }
    private boolean suitable(ItemStack stack) {
        return switch (method.getValue()) {
            case Crossbow -> stack.is(Items.CROSSBOW) && EnchantmentUtil.has(Enchantments.MULTISHOT, stack)
                    && stack.get(DataComponents.CHARGED_PROJECTILES) != null
                    && !stack.get(DataComponents.CHARGED_PROJECTILES).isEmpty()
                    && !stack.get(DataComponents.CHARGED_PROJECTILES).contains(Items.FIREWORK_ROCKET);
            case Firework -> stack.is(Items.FIREWORK_ROCKET) && (stack.get(DataComponents.FIREWORKS) == null
                    || stack.get(DataComponents.FIREWORKS).explosions().isEmpty());
            case ItemFrame -> stack.is(Items.ITEM_FRAME);
        };
    }
    private BlockHitResult findBase() {
        BlockPos origin = mc.player.blockPosition();
        int radius = (int) Math.ceil(range.getValue());
        BlockHitResult best = null; double distance = range.getValue() * range.getValue();
        Vec3 eye = mc.player.getEyePosition();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -2, -radius), origin.offset(radius, 1, radius))) {
            var state = mc.level.getBlockState(pos);
            if (!state.is(Blocks.OBSIDIAN) && !state.is(Blocks.BEDROCK)) continue;
            if (!mc.level.getBlockState(pos.above()).isAir() || !mc.level.getBlockState(pos.above(2)).isAir()) continue;
            if (ItemUseTracker.waitingAt(pos)) continue;
            AABB space = new AABB(pos.above()).expandTowards(0, 1, 0);
            if (!mc.level.getEntities((net.minecraft.world.entity.Entity) null, space, entity -> entity.isAlive() && !entity.isSpectator()).isEmpty()) continue;
            Vec3 point = new Vec3(pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5);
            double d = eye.distanceToSqr(point);
            if (d >= distance) continue;
            var ray = mc.level.clip(new ClipContext(eye, point.add(0, -.01, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (ray.getType() != HitResult.Type.BLOCK || !ray.getBlockPos().equals(pos) || ray.getDirection() != Direction.UP) continue;
            distance = d; best = new BlockHitResult(point, Direction.UP, pos.immutable(), false);
        }
        return best;
    }
}
