package dev.guizhou.features.modules.qinghua;

import dev.guizhou.event.impl.entity.player.TickEvent;
import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.qinghua.util.BlockUtil;
import dev.guizhou.features.modules.qinghua.util.CombatUtils;
import dev.guizhou.features.modules.qinghua.util.NumberListUtil;
import dev.guizhou.features.modules.qinghua.util.PredictUtility;
import dev.guizhou.features.modules.qinghua.util.SortPriority;
import dev.guizhou.features.modules.qinghua.util.TPUtil;
import dev.guizhou.features.modules.qinghua.util.TargetUtils;
import dev.guizhou.features.settings.Setting;
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
import java.util.List;

/**
 * Port of Compassion LacrymiraAura — breach/miss aura path unchanged (intermediary → Mojang).
 */
public class LacrymiraAuraModule extends Module {
    public enum SwitchMode { Silent, None, Normal, Inventory }

    private final Setting<SortPriority> priority = mode("TargetPriority", SortPriority.LowestDistance);
    private final Setting<Integer> maxTarget = num("MaxTarget", 10, 1, 25);
    private final Setting<Double> range = num("Range", 50.0, 1.0, 200.0);
    private final Setting<Double> delay = num("Delay", 1.0, 0.0, 10.0);
    private final Setting<SwitchMode> switchMode = mode("SwitchMode", SwitchMode.Silent);
    private final Setting<Boolean> swingHand = bool("SwingHand", false);
    private final Setting<Boolean> breach = bool("Breach", true);
    private final Setting<Double> ignoreCount = num("IgnoreCount", 0.0, 0.0, 4.0)
            .setVisibility(v -> breach.getValue());
    private final Setting<String> breachVclip = str("BreachVclip", "20.0,60.0,120.0")
            .setVisibility(v -> breach.getValue());
    private final Setting<Double> breachMoveDistance = num("BreachMoveDistance", 128.0, 1.0, 128.0)
            .setVisibility(v -> breach.getValue());
    private final Setting<String> missVclip = str("MissVclip", "10.0,15.0,20.0,25.0,30.0");
    private final Setting<Double> missMoveDistance = num("MissMoveDistance", 10.0, 1.0, 128.0);

    private final List<Entity> targets = new ArrayList<>();
    private int timer;

    public LacrymiraAuraModule() {
        super("LacrymiraAura", "skidder?", Category.COMBAT);
    }

    private boolean isPlayerWithArmor(Entity target) {
        return target instanceof Player p
                && CombatUtils.remainingArmorCount(p) > ignoreCount.getValue().intValue();
    }

    private boolean targetCheck(Entity t) {
        if (!(t instanceof LivingEntity living)) return false;
        if (living.isDeadOrDying() || living.getHealth() <= 0.0f || living.deathTime > 0) return false;
        if (t instanceof Player p && p.isCreative()) return false;
        return TargetUtils.matchesSelector(t);
    }

    private void swap(int slot, SwitchMode mode) {
        if (mode == SwitchMode.None || slot < 0 || slot > 8 || mc.getConnection() == null) return;
        if (mode == SwitchMode.Silent || mode == SwitchMode.Normal) {
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
            if (mode == SwitchMode.Normal) {
                mc.player.getInventory().setSelectedSlot(slot);
            }
        }
    }

    private void swapBack(int slot, SwitchMode mode) {
        if (mode == SwitchMode.Silent && mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(
                    mc.player.getInventory().getSelectedSlot()));
        }
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

    private void attack(Entity entity) {
        if (mc.getConnection() == null) return;
        mc.getConnection().send(ServerboundInteractPacket.createAttackPacket(entity, false));
        if (swingHand.getValue()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    private double[] parseList(String raw) {
        List<Double> out = new ArrayList<>();
        for (String s : NumberListUtil.parseStrings(raw)) {
            try {
                out.add(Double.parseDouble(s.trim()));
            } catch (Exception ignored) {
            }
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    @Subscribe
    public void onRender(Render2DEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (this.timer <= 0) {
            ArrayList<Entity> entities = new ArrayList<>();
            TargetUtils.getList(entities, this::targetCheck, this.priority.getValue(), this.maxTarget.getValue());
            this.targets.clear();
            this.targets.addAll(entities);
            if (this.targets.isEmpty()) return;
            for (Entity entity : this.targets) {
                if (entity.position().distanceToSqr(mc.player.position())
                        >= this.range.getValue() * this.range.getValue()) {
                    return;
                }
            }
            int mace = CombatUtils.findItem(Items.MACE);
            if (mace == -1) return;
            Entity target = this.targets.remove(0);
            Vec3 playerVec = mc.player.position();
            if (this.isPlayerWithArmor(target) && this.breach.getValue()) {
                for (double attackHeight : parseList(this.breachVclip.getValue())) {
                    Vec3 targetVec = new Vec3(target.getX(), target.getY() + attackHeight, target.getZ());
                    TPUtil.doTp(playerVec, targetVec, this.breachMoveDistance.getValue(), false);
                    TPUtil.doTp(targetVec, new Vec3(target.getX(), target.getY(), target.getZ()),
                            this.breachMoveDistance.getValue(), false);
                    if (CombatUtils.findItem(Items.MACE) != -1) {
                        if (this.switchMode.getValue() == SwitchMode.Inventory) {
                            inventorySwap(mace);
                        } else {
                            this.swap(mace <= 8 ? mace : 0, this.switchMode.getValue());
                        }
                    }
                    this.attack(target);
                    if (this.switchMode.getValue() == SwitchMode.Inventory) {
                        inventorySwap(mace);
                    } else {
                        this.swapBack(mace, this.switchMode.getValue());
                    }
                    TPUtil.doTp(new Vec3(target.getX(), target.getY(), target.getZ()), playerVec,
                            this.breachMoveDistance.getValue(), false);
                }
            }
            this.timer = this.delay.getValue().intValue();
        } else {
            --this.timer;
        }
    }

    @Subscribe
    public void onTickEvent(TickEvent event) {
        if (mc.player == null || mc.level == null) return;
        ArrayList<Entity> entities = new ArrayList<>();
        TargetUtils.getList(entities, this::targetCheck, this.priority.getValue(), this.maxTarget.getValue());
        this.targets.clear();
        this.targets.addAll(entities);
        if (this.targets.isEmpty()) return;
        for (Entity entity : this.targets) {
            if (entity.position().distanceToSqr(mc.player.position())
                    >= this.range.getValue() * this.range.getValue()) {
                return;
            }
        }
        int mace = CombatUtils.findItem(Items.MACE);
        if (mace == -1) return;
        Entity target = this.targets.remove(0);
        if (!(this.isPlayerWithArmor(target) && this.breach.getValue())) {
            double[] attackHeights = parseList(this.missVclip.getValue());
            this.doTpAura(target, attackHeights, this.missMoveDistance.getValue(), 0, this.swingHand.getValue());
        }
    }

    public void doTpAura(Entity target, double[] h, double moveDistance, int predictTicks, boolean swing) {
        Vec3 predicted = PredictUtility.predictPosition(target, predictTicks);
        Vec3 targetVec = new Vec3(predicted.x, predicted.y, predicted.z);
        Vec3 playerPos = mc.player.position();
        Vec3 clipVec = TPUtil.findVClipVecToMove(targetVec, playerPos, 1.8, false);
        if (clipVec == null) return;
        int b = (int) Math.ceil(playerPos.distanceTo(clipVec) / moveDistance);
        int maxPacket = Math.max(b, 0);
        if (maxPacket > 20) return;
        for (int i = 1; i < maxPacket; ++i) {
            TPUtil.sendMovePacket(playerPos.x, playerPos.y, playerPos.z, false);
        }
        for (double attackVclip : h) {
            Vec3 newMiss = Vec3.atBottomCenterOf(BlockUtil.findVclipHole(mc.player.blockPosition(), attackVclip));
            int mace = CombatUtils.findItem(Items.MACE);
            if (mace == -1) continue;
            if (this.switchMode.getValue() == SwitchMode.Inventory) {
                inventorySwap(mace);
            } else if (mace <= 8) {
                this.swap(mace, this.switchMode.getValue());
            }
            TPUtil.doTp(playerPos, newMiss, moveDistance, false);
            TPUtil.sendMovePacket(clipVec.x, clipVec.y, clipVec.z, false);
            if (swing) mc.player.swing(InteractionHand.MAIN_HAND);
            if (mc.gameMode != null) mc.gameMode.attack(mc.player, target);
            if (this.switchMode.getValue() == SwitchMode.Inventory) {
                inventorySwap(mace);
            } else {
                this.swapBack(mace, this.switchMode.getValue());
            }
        }
        TPUtil.sendMovePacket(playerPos.x, playerPos.y, playerPos.z, false);
    }

    @Override
    public String getDisplayInfo() {
        return this.targets.isEmpty() ? "None" : this.targets.get(0).getName().getString();
    }
}
