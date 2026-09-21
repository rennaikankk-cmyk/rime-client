package dev.guizhou.util.combat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public final class CombatPlannerTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        rays(); planner(); predictions(); movingSnapshots(); boundedWork(); realBackgroundWork();
        System.out.println("CombatPlannerTest passed: " + checks + " checks");
    }
    private static void rays() {
        Map<Integer, List<CombatCollisionSnapshot.Box>> boxes = new HashMap<>();
        var mutable = new ArrayList<CombatCollisionSnapshot.Box>();
        mutable.add(new CombatCollisionSnapshot.Box(1, 0, 0, 2, 1, 1));
        boxes.put((1 * 3) * 3, mutable);
        var scene = new CombatCollisionSnapshot(0, 0, 0, 3, 3, 3, boxes);
        mutable.clear(); boxes.clear();
        check(!scene.clear(.5, .5, .5, 2.5, .5, .5, -1, -1, -1), "Copied wall blocks even after caller mutates collections");
        check(scene.clear(.5, .5, .5, 2.5, .5, .5, 1, 0, 0), "Removed source block is ignored");
        check(scene.clear(.5, 1.5, .5, 2.5, 1.5, .5, -1, -1, -1), "Ray above wall remains clear");
        check(!scene.clear(.5, .5, .5, 3.5, .5, .5, 1, 0, 0), "Unknown geometry outside capture is never air");
        var negative = new CombatCollisionSnapshot(-3, -3, -3, 6, 6, 6, Map.of());
        check(negative.clear(-2.5, -2.5, -2.5, 2.5, 2.5, 2.5, 0, 0, 0), "Negative coordinates and diagonal DDA");
        check(negative.clear(2.5, 2.5, 2.5, -2.5, -2.5, -2.5, 0, 0, 0), "Reverse diagonal DDA");
        check(negative.clear(0, 0, 0, 0, 0, 0, 1, 1, 1), "Zero-length air ray terminates");
    }
    private static CombatantSnapshot person(int id, double x, float health) {
        return new CombatantSnapshot(id, 1, 1, x, 0, 0, x - .3, 0, -.3, x + .3, 1.8, .3,
                health, 0, 0, 0, 1, 0, 20, true);
    }
    private static void planner() {
        var terrain = new CombatCollisionSnapshot(-8, -3, -4, 24, 8, 9, Map.of());
        var target = person(2, 5, 20);
        var self = person(1, -5, 20);
        var nearTarget = new AnchorPlanner.Candidate(4, 0, 0);
        var nearSelf = new AnchorPlanner.Candidate(0, 0, 0);
        var input = new AnchorPlanner.Input(4, 10, 2, false, 1, 10, self, List.of(target), List.of(nearSelf, nearTarget), terrain);
        var result = AnchorPlanner.plan(input);
        check(nearTarget.equals(result.candidate()), "Planner selects safe higher-damage candidate");
        check(result.rays() > 0 && result.damage() > 0 && result.selfDamage() < 10, "Actual exposure rays and reductions run in planner");
        double distance = Math.sqrt(.5 * .5 * 3);
        double impact = 1 - distance / 10;
        float expected = (float)((impact * impact + impact) / 2 * 70 + 1);
        check(Math.abs(result.damage() - expected) < .0001, "Unobstructed result matches independent power-five formula");
        var unsafe = new AnchorPlanner.Input(4, 10, 2, false, 1, 100, person(1, 4, 5), List.of(target), List.of(nearTarget), terrain);
        check(AnchorPlanner.plan(unsafe).candidate() == null, "Lethal self damage never becomes an actionable choice");
        var peaceful = new AnchorPlanner.Input(4, 10, 0, false, 0, 100, self, List.of(target), List.of(nearTarget), terrain);
        check(AnchorPlanner.plan(peaceful).candidate() == null, "Peaceful zero damage does not produce a choice");
        var assumed = new AnchorPlanner.Input(4, 10, 2, true, 1, 10, self, List.of(target), List.of(nearTarget), terrain);
        check(AnchorPlanner.plan(assumed).damage() < result.damage(), "Assume armor affects targets without changing self profile");
        Map<Integer, List<CombatCollisionSnapshot.Box>> wall = new HashMap<>();
        int minX = -8, minY = -3, minZ = -4, sy = 8, sz = 9;
        for (int y = -2; y <= 3; y++) for (int z = -3; z <= 3; z++) {
            int index = ((3 - minX) * sy + y - minY) * sz + z - minZ;
            wall.put(index, List.of(new CombatCollisionSnapshot.Box(3, y, z, 4, y + 1, z + 1)));
        }
        var shield = new CombatCollisionSnapshot(minX, minY, minZ, 24, sy, sz, wall);
        var blocked = new AnchorPlanner.Input(4, 10, 2, false, 1, 100, self, List.of(target), List.of(nearSelf), shield);
        check(AnchorPlanner.plan(blocked).candidate() == null, "Wall shielding removes target damage instead of only sorting distances");
    }
    private static void predictions() {
        var terrain = new CombatCollisionSnapshot(-10, -3, -4, 32, 8, 9, Map.of());
        var self = person(1, -5, 20);
        var target = person(2, 5, 20);
        var candidate = new AnchorPlanner.Candidate(4, 0, 0, 3);
        var moved = target.at(10, 0, 0);
        check(moved.identity() == target.identity() && moved.minX() == target.minX() + 5
                && moved.maxX() == target.maxX() + 5, "Prediction moves bounds without changing entity identity");
        var original = new AnchorPlanner.Input(1, 1, 2, false, 1, 10, self, List.of(target), List.of(candidate), terrain);
        var mutable = new ArrayList<>(List.of(moved));
        var projected = new AnchorPlanner.Input(1, 1, 2, false, 1, 10, self, List.of(target), List.of(candidate), terrain,
                Map.of(3, mutable), Map.of());
        mutable.clear();
        check(AnchorPlanner.plan(projected).damage() < AnchorPlanner.plan(original).damage(),
                "Candidate scores copied predicted positions for its remaining stage horizon");
        var futureUnsafe = new AnchorPlanner.Input(1, 1, 2, false, 1, 10, self, List.of(target), List.of(candidate), terrain,
                Map.of(), Map.of(3, self.at(4, 0, 0)));
        check(AnchorPlanner.plan(futureUnsafe).candidate() == null, "Unsafe predicted self position blocks action");
        var currentUnsafe = new AnchorPlanner.Input(1, 1, 2, false, 1, 10, self.at(4, 0, 0), List.of(target), List.of(candidate), terrain,
                Map.of(), Map.of(3, self));
        check(AnchorPlanner.plan(currentUnsafe).candidate() == null, "Safe future position cannot hide unsafe current self damage");
    }

    private static void movingSnapshots() {
        var before = person(2, 5, 20);
        var moving = person(2, 5.5, 15);
        check(CombatSnapshotValidation.compatible(before, moving, .75), "Ordinary target movement and health changes allow live winner revalidation");
        check(!CombatSnapshotValidation.compatible(before, person(2, 6, 20), .75), "Large movement discards stale plan");
        var replacement = new CombatantSnapshot(2, 2, 2, 5, 0, 0, 4.7, 0, -.3, 5.3, 1.8, .3, 20, 0, 0, 0, 1, 0, 20, true);
        check(!CombatSnapshotValidation.compatible(before, replacement, .75), "Same entity id in a new lifetime never reuses plan");
        var armored = new CombatantSnapshot(2, 1, 2, 5, 0, 0, 4.7, 0, -.3, 5.3, 1.8, .3, 20, 0, 20, 8, 1, 0, 20, true);
        check(!CombatSnapshotValidation.compatible(before, armored, .75), "Changed armor discards plan");
        check(CombatSnapshotValidation.compatibleTargets(List.of(person(3, 8, 20), before), List.of(moving, person(3, 8.2, 20)), .75),
                "Entity traversal ordering does not invalidate a stable target set");
        check(!CombatSnapshotValidation.compatibleTargets(List.of(before), List.of(moving, person(3, 8, 20)), .75), "Target-set changes invalidate plan");
    }

    private static void boundedWork() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        var task = new CombatPlanningTask<Integer, Integer>(x -> x * 2, queue::addLast);
        for (int i = 0; i < 1000; i++) task.submit(i);
        check(queue.size() == 1, "Latest pending input does not create an unbounded executor queue");
        queue.removeFirst().run();
        check(task.poll().output() == 1998, "Only latest queued input is computed");
        check(task.poll() == null, "Completed result is consumed once");
        task.submit(5); task.invalidate(); queue.removeFirst().run();
        check(task.poll() == null, "Invalidated session cannot publish a result");
        var failed = new CombatPlanningTask<Integer, Integer>(x -> { throw new IllegalStateException(); }, queue::addLast);
        failed.submit(1); queue.removeFirst().run();
        check(failed.poll().failed(), "Worker failure is reported for synchronous fallback");
        AtomicBoolean reject = new AtomicBoolean(true);
        var saturated = new CombatPlanningTask<Integer, Integer>(x -> x, runnable -> {
            if (reject.get()) throw new RejectedExecutionException();
            queue.addLast(runnable);
        });
        saturated.submit(7);
        check(saturated.poll() == null && queue.isEmpty(), "Saturation never computes on caller");
        reject.set(false); saturated.poll(); queue.removeFirst().run();
        check(saturated.poll().output() == 7, "Saturated work can be retried without losing latest input");
    }

    private static void realBackgroundWork() throws Exception {
        Thread caller = Thread.currentThread();
        var background = new CombatPlanningTask<Integer, Boolean>(x -> Thread.currentThread() != caller);
        background.submit(1);
        check(awaitResult(background).output(), "Production executor computes on a real background thread");
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        var invalidated = new CombatPlanningTask<Integer, Integer>(x -> {
            if (x == 1) {
                started.countDown();
                try {
                    if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Test release missing");
                } catch (InterruptedException failure) { throw new IllegalStateException(failure); }
            }
            return x;
        });
        invalidated.submit(1);
        check(started.await(2, TimeUnit.SECONDS), "Worker entered computation before session invalidation");
        invalidated.invalidate(); invalidated.submit(2); release.countDown();
        check(awaitResult(invalidated).output() == 2, "An old running result cannot overwrite the new session");
    }

    private static <I, O> CombatPlanningTask.Completed<I, O> awaitResult(CombatPlanningTask<I, O> task) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            var result = task.poll();
            if (result != null) return result;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        throw new AssertionError("Background result timed out");
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
