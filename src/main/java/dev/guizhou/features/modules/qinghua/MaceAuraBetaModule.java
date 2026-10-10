package dev.guizhou.features.modules.qinghua;

import dev.guizhou.event.impl.entity.player.TickEvent;
import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.qinghua.util.BlockUtil;
import dev.guizhou.features.modules.qinghua.util.CombatUtils;
import dev.guizhou.features.modules.qinghua.util.NumberListUtil;
import dev.guizhou.features.modules.qinghua.util.SortPriority;
import dev.guizhou.features.modules.qinghua.util.TPUtil;
import dev.guizhou.features.modules.qinghua.util.TargetUtils;
import dev.guizhou.features.settings.Setting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Port of Pattern MaceAuraBeta — dual/predict/water/vclip attack path unchanged.
 * InvUtils/Friends/AChatUtils → InventoryUtil/friendManager/System.out; StringList → NumberListUtil.
 */
public class MaceAuraBetaModule extends Module {
    public enum SwitchMode { Silent, None, Inventory }
    public enum PredictionMode { Off, Legacy, Simple, Advanced, V2 }

    private final Setting<SortPriority> priority = mode("Priority", SortPriority.LowestDistance)
            .described("实体排序优先级");
    private final Setting<Integer> maxTarget = num("MaxTarget", 10, 1, 25)
            .described("目标的最大数量");
    private final Setting<Double> range = num("Range", 50.0, 1.0, 200.0)
            .described("目标的最大距离");
    private final Setting<Double> delay = num("Delay", 1.0, 0.0, 10.0)
            .described("攻击间隔 (tick)");
    private final Setting<SwitchMode> switchMode = mode("SwitchMode", SwitchMode.Silent)
            .described("物品切换模式");
    private final Setting<Boolean> swingHand = bool("SwingHand", false)
            .described("攻击时是否摆动手臂");
    private final Setting<Boolean> breach = bool("Breach", true)
            .described("有甲时启用破甲路径");
    private final Setting<Boolean> dual = bool("Dual", false)
            .described("有甲时先破甲再绕甲")
            .setVisibility(v -> breach.getValue());
    private final Setting<Integer> ignoreCount = num("IgnoreCount", 0, 0, 4)
            .described("剩余护甲件数 <= 此值走绕甲")
            .setVisibility(v -> breach.getValue());
    private final Setting<PredictionMode> predictionMode = mode("PredictionMode", PredictionMode.Off)
            .described("选择目标位置预测算法");
    private final Setting<Integer> predictTicks = num("PredictTicks", 3, 1, 20)
            .setVisibility(v -> predictionMode.getValue() != PredictionMode.Off);
    private final Setting<Integer> legacyDataSize = num("LegacyDataSize", 4, 2, 4)
            .setVisibility(v -> predictionMode.getValue() == PredictionMode.Legacy);
    private final Setting<Double> legacyResultMul = num("LegacyResultMul", 3.0, 0.1, 5.0)
            .setVisibility(v -> predictionMode.getValue() == PredictionMode.Legacy);
    private final Setting<Boolean> avoidWater = bool("AvoidWater", true)
            .described("如果目标在水中，寻找水面上的安全位置进行攻击");
    private final Setting<Boolean> conditionWaterCheck = bool("ConditionWaterCheck", false)
            .described("同时检查玩家自身和目标是否在水中，智能选择攻击位置");
    private final Setting<Double> waterSearchRange = num("WaterSearchRange", 6.0, 0.0, 20.0)
            .setVisibility(v -> avoidWater.getValue() || conditionWaterCheck.getValue());
    private final Setting<String> missVclip = str("MissVclip", "10.0,15.0,20.0,25.0,30.0")
            .described("Distance of clip.");
    private final Setting<String> breachVclip = str("BreachVclip", "20.0,60.0,120.0")
            .described("Distance of clip.")
            .setVisibility(v -> breach.getValue());
    private final Setting<Double> missMoveDistance = num("MissMoveDistance", 10.0, 1.0, 128.0)
            .described("绕甲路径每包最大位移");
    private final Setting<Double> breachMoveDistance = num("BreachMoveDistance", 128.0, 1.0, 128.0)
            .described("破甲路径每包最大位移")
            .setVisibility(v -> breach.getValue());

    private final List<Entity> targets = new ArrayList<>();
    private int timer = 0;
    private int originalSlot;
    private final Map<UUID, PredictManager> predictManagers = new HashMap<>();
    private int silentPrevSlot = -1;

    public MaceAuraBetaModule() {
        super("MaceAuraBeta", "Miss重锤光环 (双开+预测+水下)", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        targets.clear();
        timer = 0;
        originalSlot = mc.player != null ? mc.player.getInventory().getSelectedSlot() : 0;
        predictManagers.clear();
        silentPrevSlot = -1;
    }

    @Override
    public void onDisable() {
        targets.clear();
        predictManagers.clear();
        if (mc.player != null && originalSlot != -1 && mc.getConnection() != null) {
            for (int i = 0; i < 3; i++) {
                mc.getConnection().send(new ServerboundSetCarriedItemPacket(originalSlot));
            }
        }
        silentPrevSlot = -1;
    }

    private double[] parseDoubleList(String raw) {
        List<String> list = NumberListUtil.parseStrings(raw);
        if (list.isEmpty()) return new double[0];
        List<Double> valid = new ArrayList<>();
        for (String s : list) {
            if (s == null || s.trim().isEmpty()) continue;
            try {
                double val = Double.parseDouble(s.trim());
                if (val > 0.01) valid.add(val);
            } catch (NumberFormatException ignored) {
            }
        }
        return valid.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private PredictManager managerFor(Entity target) {
        return predictManagers.computeIfAbsent(target.getUUID(), k -> new PredictManager());
    }

    private Vec3 predictLegacy(Entity target, int dataSize, double resultMul) {
        PredictManager manager = managerFor(target);
        manager.push(target.position(), dataSize);
        if (manager.isEmpty()) return target.position();
        return target.position().add(manager.getDiff(resultMul));
    }

    private Vec3 predictSimple(Entity target, int ticks) {
        Vec3 pos = target.position();
        Vec3 vel = target.getDeltaMovement();
        return pos.add(vel.scale(ticks * 0.05));
    }

    private Vec3 predictAdvanced(Entity target, int ticks) {
        Vec3 pos = target.position();
        Vec3 vel = target.getDeltaMovement();
        return pos.add(vel.scale(ticks * 0.05));
    }

    private Vec3 predictV2(Entity target, int ticks) {
        Vec3 pos = target.position();
        Vec3 vel = target.getDeltaMovement();
        int halfTicks = ticks / 2;
        Vec3 linearPos = pos.add(vel.scale(halfTicks * 0.05));
        return linearPos.add(vel.scale((ticks - halfTicks) * 0.05));
    }

    private Vec3 getPredictedPosition(Entity target) {
        PredictionMode mode = predictionMode.getValue();
        if (mode == PredictionMode.Off) return target.position();
        int ticks = predictTicks.getValue();
        return switch (mode) {
            case Legacy -> predictLegacy(target, legacyDataSize.getValue(), legacyResultMul.getValue());
            case Simple -> predictSimple(target, ticks);
            case Advanced -> predictAdvanced(target, ticks);
            case V2 -> predictV2(target, ticks);
            default -> target.position();
        };
    }

    private boolean isPositionWaterSafe(BlockPos pos) {
        if (mc.level == null) return true;
        for (int i = 0; i < 3; i++) {
            if (!mc.level.getFluidState(pos.offset(0, i, 0)).isEmpty()) return false;
        }
        return true;
    }

    private boolean isPositionUnderwater(Vec3 pos) {
        if (mc.level == null) return false;
        return !mc.level.getFluidState(BlockPos.containing(pos)).isEmpty();
    }

    private Vec3 findSafeWaterPosition(Vec3 targetPos, Vec3 playerPos) {
        if (mc.level == null) return targetPos;
        boolean targetInWater = isPositionUnderwater(targetPos);
        boolean playerInWater = isPositionUnderwater(playerPos);
        if (!targetInWater && !playerInWater) return targetPos;
        if (conditionWaterCheck.getValue() && targetInWater && playerInWater) return targetPos;

        if (avoidWater.getValue() || conditionWaterCheck.getValue()) {
            double searchRange = waterSearchRange.getValue();
            BlockPos center = BlockPos.containing(targetPos);

            for (int y = 0; y <= (int) searchRange; y++) {
                BlockPos checkPos = center.offset(0, y, 0);
                Vec3 testPos = new Vec3(checkPos.getX() + 0.5, checkPos.getY(), checkPos.getZ() + 0.5);
                if (isPositionWaterSafe(checkPos) && TPUtil.findVClipVecToMove(testPos, playerPos, 1.0, false) != null) {
                    return testPos;
                }
            }
            for (int y = 0; y >= -(int) searchRange; y--) {
                BlockPos checkPos = center.offset(0, y, 0);
                Vec3 testPos = new Vec3(checkPos.getX() + 0.5, checkPos.getY(), checkPos.getZ() + 0.5);
                if (isPositionWaterSafe(checkPos) && TPUtil.findVClipVecToMove(testPos, playerPos, 1.0, false) != null) {
                    return testPos;
                }
            }
            for (int dx = -(int) searchRange; dx <= (int) searchRange; dx++) {
                for (int dz = -(int) searchRange; dz <= (int) searchRange; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    if (Math.sqrt(dx * dx + dz * dz) > searchRange) continue;
                    BlockPos checkPos = center.offset(dx, 0, dz);
                    if (!isPositionWaterSafe(checkPos)) continue;
                    Vec3 testPos = new Vec3(checkPos.getX() + 0.5, checkPos.getY(), checkPos.getZ() + 0.5);
                    if (TPUtil.findVClipVecToMove(testPos, playerPos, 1.0, false) != null) {
                        return testPos;
                    }
                }
            }
        }
        return targetPos;
    }

    private boolean isPlayerWithArmor(Entity target) {
        return target instanceof Player p
                && CombatUtils.remainingArmorCount(p) > ignoreCount.getValue();
    }

    private boolean shouldBreach(Entity target) {
        return breach.getValue() && isPlayerWithArmor(target);
    }

    private boolean targetCheck(Entity t) {
        if (!(t instanceof LivingEntity living)) return false;
        if (living.isDeadOrDying() || living.getHealth() <= 0 || living.deathTime > 0) return false;
        if (t instanceof Player p && p.isCreative()) return false;
        return TargetUtils.matchesSelector(t);
    }

    private void inventorySwap(int slot) {
        if (mc.gameMode == null || mc.player == null) return;
        int syncId = mc.player.containerMenu.containerId;
        int containerSlot = slot < 9 ? slot + 36 : slot;
        mc.gameMode.handleInventoryMouseClick(syncId, containerSlot,
                mc.player.getInventory().getSelectedSlot(), ClickType.SWAP, mc.player);
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundContainerClosePacket(syncId));
        }
    }

    private void swap(int slot, SwitchMode mode) {
        if (mode == SwitchMode.None) return;
        if (mode == SwitchMode.Inventory) {
            inventorySwap(slot);
            return;
        }
        if (slot < 0 || slot > 8) return;
        if (silentPrevSlot == -1) silentPrevSlot = mc.player.getInventory().getSelectedSlot();
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
        }
    }

    private void swapBack(int slot, SwitchMode mode) {
        if (mode == SwitchMode.None) return;
        if (mode == SwitchMode.Inventory) {
            inventorySwap(slot);
            return;
        }
        if (silentPrevSlot != -1 && mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(silentPrevSlot));
            silentPrevSlot = -1;
        }
    }

    private void attack(Entity target) {
        if (mc.player == null || mc.getConnection() == null) return;
        mc.getConnection().send(ServerboundInteractPacket.createAttackPacket(target, false));
        if (swingHand.getValue()) mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private void doBreachAttack(Entity target, double[] heights, double moveDistance) {
        if (heights.length == 0 || mc.player == null) return;
        int mace = CombatUtils.findItem(Items.MACE);
        if (mace == -1) return;

        SwitchMode mode = switchMode.getValue();
        Vec3 playerPos = mc.player.position();
        Vec3 adjustedTarget = findSafeWaterPosition(getPredictedPosition(target), playerPos);

        for (double height : heights) {
            Vec3 above = new Vec3(adjustedTarget.x, adjustedTarget.y + height, adjustedTarget.z);
            Vec3 feet = new Vec3(adjustedTarget.x, adjustedTarget.y, adjustedTarget.z);

            TPUtil.doTp(playerPos, above, moveDistance, false);
            TPUtil.doTp(above, feet, moveDistance, false);

            if (CombatUtils.findItem(Items.MACE) != -1) {
                if (mace <= 8) swap(mace, mode);
                else inventorySwap(mace);
            }
            attack(target);
            if (mace <= 8) swapBack(mace, mode);
            else inventorySwap(mace);

            TPUtil.doTp(feet, playerPos, moveDistance, false);
        }
    }

    public void doTpAura(Entity target, double[] h, double moveDistance) {
        if (h.length == 0 || mc.player == null) return;

        Vec3 playerPos = mc.player.position();
        Vec3 adjustedTarget = findSafeWaterPosition(getPredictedPosition(target), playerPos);
        Vec3 clipVec = TPUtil.findVClipVecToMove(adjustedTarget, playerPos, 1.8, false);
        if (clipVec == null) return;

        int maxPacket = (int) Math.ceil(playerPos.distanceTo(clipVec) / moveDistance);
        if (maxPacket > 20) return;

        SwitchMode mode = switchMode.getValue();
        for (int i = 1; i < maxPacket; i++) {
            TPUtil.sendMovePacket(playerPos.x, playerPos.y, playerPos.z, false);
        }

        for (double attackVclip : h) {
            Vec3 newMiss = Vec3.atBottomCenterOf(BlockUtil.findVclipHole(mc.player.blockPosition(), attackVclip));
            int mace = CombatUtils.findItem(Items.MACE);
            if (mace == -1 || mace > 8) continue;

            swap(mace, mode);
            TPUtil.doTp(playerPos, newMiss, moveDistance, false);
            TPUtil.sendMovePacket(clipVec.x, clipVec.y, clipVec.z, false);
            attack(target);
            swapBack(mace, mode);
        }
        TPUtil.sendMovePacket(playerPos.x, playerPos.y, playerPos.z, false);
    }

    private void runAttack(Entity target) {
        double[] missHeights = parseDoubleList(missVclip.getValue());
        double[] breachHeights = parseDoubleList(breachVclip.getValue());
        if (dual.getValue() && shouldBreach(target)) {
            if (breachHeights.length > 0) {
                doBreachAttack(target, breachHeights, breachMoveDistance.getValue());
            }
            if (missHeights.length > 0) {
                doTpAura(target, missHeights, missMoveDistance.getValue());
            }
            return;
        }

        if (shouldBreach(target)) {
            if (breachHeights.length == 0) return;
            doBreachAttack(target, breachHeights, breachMoveDistance.getValue());
        } else {
            if (missHeights.length == 0) return;
            doTpAura(target, missHeights, missMoveDistance.getValue());
        }
    }

    private void refreshTargets() {
        List<Entity> entities = new ArrayList<>();
        TargetUtils.getList(entities, this::targetCheck, priority.getValue(), maxTarget.getValue());
        targets.clear();
        targets.addAll(entities);
    }

    @Subscribe
    public void onRender(Render2DEvent event) {
        if (timer > 0) {
            timer--;
            return;
        }
        if (mc.player == null || mc.level == null) return;

        refreshTargets();
        if (targets.isEmpty()) return;

        Entity target = targets.get(0);
        if (mc.player.distanceToSqr(target) > range.getValue() * range.getValue()) return;
        if (CombatUtils.findItem(Items.MACE) == -1) return;
        runAttack(target);
        timer = delay.getValue().intValue();
    }

    @Subscribe
    public void onTickEvent(TickEvent event) {
        refreshTargets();
    }

    @Override
    public String getDisplayInfo() {
        if (targets.isEmpty()) return "无目标";
        Entity t = targets.get(0);
        String mode;
        if (dual.getValue() && shouldBreach(t)) mode = "双开";
        else if (shouldBreach(t)) mode = "破甲";
        else mode = "绕甲";
        return t.getName().getString() + " | " + mode;
    }

    private static class PredictManager {
        private final List<Vec3> pos = new ArrayList<>();

        public void push(Vec3 value, int dataSize) {
            if (pos.size() >= dataSize) {
                pos.subList(0, pos.size() - dataSize + 1).clear();
            }
            pos.add(value);
        }

        public boolean isEmpty() {
            return pos.isEmpty();
        }

        public Vec3 getDiff(double mul) {
            Vec3 result;
            switch (pos.size()) {
                case 1 -> result = pos.get(0);
                case 2 -> result = pos.get(0).add(pos.get(1).subtract(pos.get(0)));
                case 3 -> result = pos.get(2).scale(3.0).subtract(pos.get(1).scale(3.0)).add(pos.get(0));
                case 4 -> result = pos.get(3).scale(4.0).subtract(pos.get(2).scale(6.0))
                        .add(pos.get(1).scale(4.0)).subtract(pos.get(0));
                default -> {
                    return Vec3.ZERO;
                }
            }
            return result.subtract(pos.get(pos.size() - 1)).scale(mul);
        }
    }
}
