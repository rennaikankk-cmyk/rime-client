package dev.guizhou.util.elytra;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class FlightPlanningTaskTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        latestPendingOnly();
        invalidateAndFailureRecovery();
        saturatedExecutorRetries();
        realBackgroundThread();
        System.out.println("FlightPlanningTaskTest passed: " + checks + " checks");
    }

    private static void latestPendingOnly() {
        QueueExecutor executor = new QueueExecutor();
        List<Integer> inputs = new ArrayList<>();
        FlightPlanningTask<Integer, Integer> task = new FlightPlanningTask<>(input -> {
            inputs.add(input);
            return input * 2;
        }, executor);
        for (int input = 0; input < 100; input++) task.submit(input);
        check(executor.jobs.size() == 1, "Rapid input cannot accumulate queued jobs");
        check(task.latest() == null && inputs.isEmpty(), "Submitting and polling never execute calculation inline");
        executor.runNext();
        check(inputs.equals(List.of(99)), "Only latest unstarted input is calculated");
        check(task.latest().input() == 99 && task.latest().output() == 198, "Result carries its matching input");
        AtomicReference<FlightPlanningTask<Integer, Integer>> holder = new AtomicReference<>();
        FlightPlanningTask<Integer, Integer> concurrent = new FlightPlanningTask<>(input -> {
            if (input == 1) {
                holder.get().submit(2);
                holder.get().submit(3);
            }
            return input;
        }, executor);
        holder.set(concurrent);
        concurrent.submit(1);
        executor.runNext();
        check(concurrent.latest().output() == 1, "Completed running work remains usable while newer input waits");
        check(executor.jobs.size() == 1, "In-flight updates coalesce into one later job");
        executor.runNext();
        check(concurrent.latest().output() == 3, "Latest in-flight update runs next");
    }

    private static void invalidateAndFailureRecovery() {
        QueueExecutor executor = new QueueExecutor();
        AtomicReference<FlightPlanningTask<Integer, Integer>> holder = new AtomicReference<>();
        FlightPlanningTask<Integer, Integer> task = new FlightPlanningTask<>(input -> {
            if (input == 1) holder.get().invalidate();
            if (input == 2) throw new IllegalStateException("Expected failed prediction");
            return input;
        }, executor);
        holder.set(task);
        task.submit(1);
        executor.runNext();
        check(task.latest() == null, "Session invalidation prevents in-flight result publication");
        task.submit(2);
        executor.runNext();
        check(task.latest() == null, "Failed calculation does not publish a false result");
        task.submit(3);
        executor.runNext();
        check(task.latest().output() == 3, "Worker remains usable after prediction failure");
        task.submit(4);
        task.invalidate();
        task.submit(5);
        executor.runNext();
        check(task.latest().output() == 5, "Queued old session may only compute the new generation input");
        task.invalidate();
        check(task.latest() == null, "Reset clears previously completed output");
    }

    private static void saturatedExecutorRetries() {
        QueueExecutor executor = new QueueExecutor();
        executor.reject = true;
        FlightPlanningTask<Integer, Integer> task = new FlightPlanningTask<>(value -> value, executor);
        task.submit(1);
        task.submit(2);
        check(task.latest() == null && executor.jobs.isEmpty(), "Saturation never runs work on caller");
        executor.reject = false;
        task.latest();
        check(executor.jobs.size() == 1, "Poll retries pending work after saturation");
        executor.runNext();
        check(task.latest().output() == 2, "Retry uses newest input");
    }

    private static void realBackgroundThread() throws Exception {
        Thread caller = Thread.currentThread();
        CountDownLatch ran = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        FlightPlanningTask<Integer, Integer> task = new FlightPlanningTask<>(value -> {
            worker.set(Thread.currentThread());
            ran.countDown();
            return value;
        });
        task.submit(42);
        check(ran.await(5, TimeUnit.SECONDS), "Shared executor starts work");
        check(worker.get() != caller, "Shared calculation runs off the submitting thread");
        check(worker.get().isDaemon(), "Planner thread cannot keep a closed game alive");
        check(worker.get().getName().startsWith("gzw-flight-planner-"), "Planner worker is identifiable");
        task.invalidate();
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class QueueExecutor implements Executor {
        final ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        boolean reject;

        @Override public void execute(Runnable command) {
            if (reject) throw new RejectedExecutionException("Expected saturation");
            jobs.addLast(command);
        }

        void runNext() { jobs.removeFirst().run(); }
    }
}
