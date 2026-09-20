package dev.guizhou.util.combat;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** One running input and one replaceable pending input; no unbounded combat backlog. */
public final class CombatPlanningTask<I, O> {
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), task -> {
                Thread thread = new Thread(task, "gzw-combat-planner");
                thread.setDaemon(true); thread.setPriority(Thread.NORM_PRIORITY - 1); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    public record Completed<I, O>(I input, O output, boolean failed) {}
    private record Pending<I>(I input, long generation) {}
    private final Executor executor;
    private final Function<I, O> planner;
    private Pending<I> pending;
    private Completed<I, O> completed;
    private boolean running;
    private long generation;

    public CombatPlanningTask(Function<I, O> planner) { this(planner, WORKER); }
    CombatPlanningTask(Function<I, O> planner, Executor executor) { this.planner = planner; this.executor = executor; }
    public synchronized void submit(I input) { pending = new Pending<>(input, generation); schedule(); }
    public synchronized Completed<I, O> poll() { schedule(); var value = completed; completed = null; return value; }
    public synchronized void invalidate() { generation++; pending = null; completed = null; }
    private synchronized void schedule() {
        if (running || pending == null) return;
        running = true;
        try { executor.execute(this::run); }
        catch (RejectedExecutionException rejected) { running = false; }
    }
    private void run() {
        Pending<I> work;
        synchronized (this) { work = pending; pending = null; }
        O output = null; boolean failed = false;
        try { if (work != null) output = planner.apply(work.input); }
        catch (RuntimeException failure) { failed = true; }
        finally {
            synchronized (this) {
                if (work != null && work.generation == generation) completed = new Completed<>(work.input, output, failed);
                running = false; schedule();
            }
        }
    }
}
