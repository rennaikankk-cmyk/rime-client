package dev.guizhou.util.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.manager.HeartbeatManager;
import dev.guizhou.manager.RotationRequest;
import dev.guizhou.util.AnchorDamageUtil;
import dev.guizhou.util.MathUtil;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.inventory.SwapMode;
import dev.guizhou.util.player.EatUtil;
import dev.guizhou.util.PlaceUtil;
import dev.guizhou.util.diagnostics.ClientDiagnostics;
import dev.guizhou.util.player.ScreenGate;
import dev.guizhou.util.traits.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.List;

/** Optional self cover. A submitted obsidian is never treated as existing explosion cover. */
public final class BlastShieldController implements Util {
    private BlockPos pending, confirmed;
    private Vec3 source;
    private int sentTick, retryTick;

    public void reset() { pending = confirmed = null; source = null; sentTick = retryTick = 0; }

    /** False defers the explosive. Caller must repeat its ordinary live safety checks next tick. */
    public boolean beforeBlast(Vec3 center, BlockPos removed, double diameter,
                               List<AnchorDamageUtil.Target> targets, double range, boolean rotate, String owner) {
        if (!HeartbeatManager.authorized() || mc.player == null || mc.level == null || !mc.isSameThread()) return false;
        int tick = mc.player.tickCount;
        if (pending != null) {
            if (mc.level.getBlockState(pending).is(Blocks.OBSIDIAN)) {
                confirmed = pending; pending = null;
            } else if (tick >= sentTick && tick - sentTick < 8) return false;
            else { pending = null; retryTick = tick + 20; return true; }
        }
        if (confirmed != null && source != null && source.distanceToSqr(center) < .01
                && mc.level.getBlockState(confirmed).is(Blocks.OBSIDIAN)) return true;
        if (tick < retryTick || targets.isEmpty() || mc.player.isFallFlying() || mc.player.isUsingItem()
                || EatUtil.shouldDefer() || !ScreenGate.allowsWorldUpkeep()
                || !InventoryUtil.find(Items.OBSIDIAN, InventoryUtil.HOTBAR_SCOPE).found()) return true;
        var calculation = new AnchorDamageUtil.Calculation();
        var self = AnchorDamageUtil.Target.of(mc.player);
        var next = self.at(self.pos().add(mc.player.getDeltaMovement().scale(2)));
        float originalSelf = Math.max(calculation.damage(self, center, diameter, removed, null, null),
                calculation.damage(next, center, diameter, removed, null, null));
        if (originalSelf < 1) return true;
        float originalTarget = targetDamage(calculation, targets, center, diameter, removed, null);
        if (originalTarget <= 0 || !Float.isFinite(originalTarget)) return true;
        var mine = GuizhouWare.moduleManager.getModuleByClass(dev.guizhou.features.modules.world.GuangxiMineModule.class);
        BlockPos best = null;
        float bestSelf = originalSelf * .7f;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int height = 0; height <= 1; height++) {
                BlockPos pos = mc.player.blockPosition().relative(direction).above(height);
                if (pos.equals(removed) || pos.getCenter().distanceToSqr(center) < 2.25
                        || !PlaceUtil.canPlace(pos, range)
                        || mine != null && mine.isEnabled() && (mine.isMining(pos) || pos.equals(mine.getRebreakPos()))) continue;
                float target = targetDamage(calculation, targets, center, diameter, removed, pos);
                if (target < originalTarget * .9f || !preservesTargets(calculation, targets, center, diameter, removed, pos)) continue;
                float local = Math.max(calculation.damage(self, center, diameter, removed, null, pos),
                        calculation.damage(next, center, diameter, removed, null, pos));
                if (local < bestSelf) { best = pos; bestSelf = local; }
            }
        }
        if (best == null) return true;
        if (rotate) {
            var hit = GuizhouWare.placementManager.preparePlaceHit(best, null, true, range, range);
            if (hit == null) return true;
            float[] angles = MathUtil.calcAngle(mc.player.getEyePosition(), hit.getLocation());
            if (!GuizhouWare.rotationManager.trySubmit(new RotationRequest(owner + "_shield", 62,
                    angles[0], angles[1], RotationRequest.Mode.SILENT))) return false;
        }
        if (!HeartbeatManager.authorized()) return false;
        if (GuizhouWare.placementManager.placeDirect(best, null, Items.OBSIDIAN,
                SwapMode.SILENT, InventoryUtil.HOTBAR_SCOPE, range)) {
            pending = best.immutable(); source = center; confirmed = null; sentTick = tick;
            ClientDiagnostics.count("combat.shield.submitted", 1);
            return false;
        }
        return true;
    }

    private static boolean preservesTargets(AnchorDamageUtil.Calculation calculation, List<AnchorDamageUtil.Target> targets,
                                             Vec3 center, double diameter, BlockPos removed, BlockPos cover) {
        for (var target : targets) {
            float before = calculation.damage(target, center, diameter, removed, null, null);
            float after = calculation.damage(target, center, diameter, removed, null, cover);
            if (!Float.isFinite(before) || !Float.isFinite(after) || after < before * .9f) return false;
        }
        return true;
    }

    private static float targetDamage(AnchorDamageUtil.Calculation calculation, List<AnchorDamageUtil.Target> targets,
                                      Vec3 center, double diameter, BlockPos removed, BlockPos cover) {
        float maximum = 0;
        for (var target : targets) maximum = Math.max(maximum,
                calculation.damage(target, center, diameter, removed, null, cover));
        return maximum;
    }
}
