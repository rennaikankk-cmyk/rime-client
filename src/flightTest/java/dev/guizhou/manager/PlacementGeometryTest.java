package dev.guizhou.manager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import dev.guizhou.util.InteractionTransaction;
import java.util.ArrayList;
import java.util.List;

public final class PlacementGeometryTest {
    public static void main(String[] args) {
        BlockPos[] targets = {
                BlockPos.ZERO, new BlockPos(-17, -64, -33),
                new BlockPos(29_999_980, 319, -29_999_980)
        };
        int cases = 0;
        for (BlockPos target : targets) {
            for (Direction side : Direction.values()) {
                BlockHitResult hit = PlacementGeometry.neighbourHit(target, side);
                check(hit.getBlockPos().equals(target.relative(side)), "wrong support block");
                check(hit.getBlockPos().relative(hit.getDirection()).equals(target),
                        "clicked face does not place into the target cell");
                check(!hit.isInside(), "external placement marked inside");
                Vec3 local = hit.getLocation().subtract(Vec3.atLowerCornerOf(hit.getBlockPos()));
                check(local.x > 0 && local.x < 1 && local.y > 0 && local.y < 1
                        && local.z > 0 && local.z < 1, "cursor outside clicked block: " + local);
                double normalCoordinate = hit.getDirection().getAxis().choose(local.x, local.y, local.z);
                check(Math.abs(normalCoordinate - (hit.getDirection().getAxisDirection()
                        == Direction.AxisDirection.POSITIVE ? 0.95 : 0.05)) < 1e-7,
                        "cursor inset belongs to the wrong face");
                // The old target-relative construction must fail this same bounds invariant.
                Vec3 old = PlacementGeometry.faceCenter(target, side)
                        .subtract(Vec3.atLowerCornerOf(hit.getBlockPos()));
                check(old.x < 0 || old.x > 1 || old.y < 0 || old.y > 1 || old.z < 0 || old.z > 1,
                        "regression fixture no longer reproduces the original bug");
                cases++;
            }
        }
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(10, 20, 30);
        BlockHitResult stored = PlacementGeometry.neighbourHit(mutable, Direction.EAST);
        mutable.set(100, 200, 300);
        check(stored.getBlockPos().equals(new BlockPos(11, 20, 30)), "hit changed with scan cursor");
        realOutlineAndReach();
        cancelledAndThrowingTransactions();
        pistonPowerConfirmation();
        System.out.println("Placement geometry checks passed: " + cases
                + " direction/coordinate cases, original-bug fixtures and mutable-position isolation.");
    }

    private static void realOutlineAndReach() {
        BlockPos pos = new BlockPos(-17, 42, 900);
        AABB bed = new AABB(-17, 42, 900, -16, 42 + 9.0 / 16.0, 901);
        Vec3 eye = new Vec3(-12, 44, 898);
        for (Direction face : Direction.values()) {
            Vec3 point = PlacementGeometry.facePoint(bed, face, eye);
            check(bed.contains(point), "bed interaction aimed outside its actual outline");
            check(PlacementGeometry.validCursor(new BlockHitResult(point, face, pos, false)), "invalid projected cursor");
        }
        Vec3 top = PlacementGeometry.facePoint(bed, Direction.UP, eye);
        check(top.y < 42 + 9.0 / 16.0 && top.y > 42.5, "bed hit used the full-cube top");
        Vec3 origin = new Vec3(0, 0, 0);
        check(PlacementGeometry.withinRange(origin, new Vec3(3, 4, 0), 5), "exact reach rejected");
        check(!PlacementGeometry.withinRange(origin, new Vec3(3, 4.01, 0), 5), "out-of-reach cursor accepted");
        check(!PlacementGeometry.withinRange(origin, top, Double.NaN), "NaN range accepted");
        check(!PlacementGeometry.withinRange(origin, top, -1), "negative range accepted");
        check(!PlacementGeometry.withinRange(origin, new Vec3(Double.NaN, 0, 0), 6), "NaN point accepted");
        check(!PlacementGeometry.validCursor(new BlockHitResult(new Vec3(-18, 42, 900), Direction.UP, pos, false)),
                "cursor belonging to a different block accepted");
        check(!PlacementGeometry.validCursor(new BlockHitResult(new Vec3(Double.NaN, 42, 900), Direction.UP, pos, false)),
                "nonfinite cursor accepted");
        // TP validation must use the eye position corresponding to the synthetic move.
        check(!PlacementGeometry.withinRange(new Vec3(0, 100, 0), top, 6), "distant real eye accepted");
        check(PlacementGeometry.withinRange(new Vec3(-16.5, 45.62, 900.5), top, 6), "valid TP eye rejected");
    }

    private static void cancelledAndThrowingTransactions() {
        var calls = new ArrayList<String>();
        check(!InteractionTransaction.run(() -> { calls.add("enter"); return false; },
                () -> { calls.add("action"); return true; }, () -> calls.add("restore")), "cancelled prelude succeeded");
        check(calls.equals(List.of("enter")), "cancelled prelude sent a dependent action");
        for (boolean accepted : new boolean[]{false, true}) {
            calls.clear();
            check(InteractionTransaction.run(() -> { calls.add("enter"); return true; },
                    () -> { calls.add("action"); return accepted; }, () -> calls.add("restore")) == accepted,
                    "transaction discarded actual submission result");
            check(calls.equals(List.of("enter", "action", "restore")), "incorrect restore order");
        }
        calls.clear();
        try {
            InteractionTransaction.run(() -> true, () -> { throw new IllegalStateException("expected"); },
                    () -> calls.add("restore"));
            throw new AssertionError("action exception disappeared");
        } catch (IllegalStateException expected) {
            check(calls.equals(List.of("restore")), "throwing action stranded fake movement state");
        }
    }

    private static void pistonPowerConfirmation() {
        var breakPiston = dev.guizhou.util.combat.PistonPowerSequence.Action.BREAK;
        var wait = dev.guizhou.util.combat.PistonPowerSequence.Action.WAIT;
        var ready = dev.guizhou.util.combat.PistonPowerSequence.Action.READY;
        var abort = dev.guizhou.util.combat.PistonPowerSequence.Action.ABORT;
        check(dev.guizhou.util.combat.PistonPowerSequence.resetPiston(true, false, false, true) == breakPiston,
                "existing piston cannot be recycled");
        check(dev.guizhou.util.combat.PistonPowerSequence.resetPiston(false, true, false, true) == wait,
                "air update advanced before old head disappeared");
        check(dev.guizhou.util.combat.PistonPowerSequence.resetPiston(true, false, false, false) == abort,
                "vanished power ignored");
        check(dev.guizhou.util.combat.PistonPowerSequence.resetPiston(false, true, true, true) == ready,
                "confirmed piston removal cannot advance");
        check(dev.guizhou.util.combat.PistonPowerSequence.resetPiston(false, false, true, true) == abort,
                "replacement block would be mined without ownership");
        var keep = dev.guizhou.util.combat.PistonPowerSequence.Plan.KEEP_POWER;
        var recycle = dev.guizhou.util.combat.PistonPowerSequence.Plan.RECYCLE_PISTON;
        var reject = dev.guizhou.util.combat.PistonPowerSequence.Plan.REJECT;
        check(dev.guizhou.util.combat.PistonPowerSequence.plan(true, true, false, 2, false) == keep,
                "existing power incorrectly requires mining or spare redstone");
        check(dev.guizhou.util.combat.PistonPowerSequence.plan(true, true, true, 2, true) == recycle,
                "existing powered piston cannot be retriggered while preserving power");
        check(dev.guizhou.util.combat.PistonPowerSequence.plan(true, true, true, 2, false) == reject,
                "prepowered piston reused with no way to retrigger it");
        check(dev.guizhou.util.combat.PistonPowerSequence.plan(true, true, false, 1, false) == reject,
                "crystal-first plan accepted overlapping piston cell");
        check(dev.guizhou.util.combat.PistonPowerSequence.plan(true, false, false, 2, false) == reject,
                "disabled reuse ignored");
        check(dev.guizhou.util.combat.PistonPowerSequence.plan(false, true, false, 1, false)
                == dev.guizhou.util.combat.PistonPowerSequence.Plan.PLACE_POWER, "normal sequence lost");
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos crystal = new BlockPos(-10, 50, -20);
            check(!dev.guizhou.util.combat.PistonPowerSequence.clearForPiston(crystal, crystal.relative(dir)),
                    "adjacent piston incorrectly fits after crystal spawn");
            check(dev.guizhou.util.combat.PistonPowerSequence.clearForPiston(crystal, crystal.relative(dir, 2)),
                    "gap-two piston rejected despite clear crystal bounds");
            Vec3 start = Vec3.atBottomCenterOf(crystal);
            Vec3 end = dev.guizhou.util.combat.PistonPowerSequence.predictedPush(crystal, dir);
            check(dev.guizhou.util.combat.PistonPowerSequence.pushed(start, end, dir), "expected push not recognized");
            check(!dev.guizhou.util.combat.PistonPowerSequence.pushed(start, start, dir),
                    "extended block state alone counted as a moved crystal");
            check(!dev.guizhou.util.combat.PistonPowerSequence.pushed(start, start.scale(2).subtract(end), dir),
                    "movement away from target counted as successful push");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
