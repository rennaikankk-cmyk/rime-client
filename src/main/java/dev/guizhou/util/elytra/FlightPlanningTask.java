package dev.guizhou.util.elytra;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Latest-input planning with bounded background work and generation-based cancellation. */
public final class FlightPlanningTask<I, O> {
    private static final AtomicInteger THREAD_NUMBER = new AtomicInteger();
    private static final int WORKERS = Runtime.getRuntime().availableProcessors() <= 2 ? 1 : 2;
    private static final ThreadPoolExecutor SHARED = new ThreadPoolExecutor(WORKERS, WORKERS, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), runnable -> {
                Thread thread = new Thread(runnable, "gzw-flight-planner-" + THREAD_NUMBER.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public record Completed<I, O>(I input, O output) { }

    public record Diagnostics(int workers, int active, int queued, long completedRuns) { }

    public static Diagnostics diagnostics() {
        return new Diagnostics(WORKERS, SHARED.getActiveCount(), SHARED.getQueue().size(),
                SHARED.getCompletedTaskCount());
    }

    private record Pending<I>(I input, long generation) { }

    private final Function<I, O> worker;
    private final Executor executor;
    private final Object lock = new Object();
    private Pending<I> pending;
    private Completed<I, O> completed;
    private long generation;
    private boolean scheduled;

    public FlightPlanningTask(Function<I, O> worker) {
        this(worker, SHARED);
    }

    FlightPlanningTask(Function<I, O> worker, Executor executor) {
        this.worker = Objects.requireNonNull(worker);
        this.executor = Objects.requireNonNull(executor);
    }

    public void submit(I input) {
        Objects.requireNonNull(input);
        synchronized (lock) {
            pending = new Pending<>(input, generation);
            schedule();
        }
    }

    public Completed<I, O> latest() {
        synchronized (lock) {
            schedule();
            return completed;
        }
    }

    public void invalidate() {
        synchronized (lock) {
            generation++;
            pending = null;
            completed = null;
        }
    }

    private void schedule() {
        if (scheduled || pending == null) return;
        scheduled = true;
        try {
            executor.execute(this::runOne);
        } catch (RejectedExecutionException saturated) {
            // Polling or the next submit retries without ever running the calculation on the caller.
            scheduled = false;
        }
    }

    private void runOne() {
        Pending<I> work;
        synchronized (lock) {
            work = pending;
            pending = null;
        }
        O output = null;
        boolean succeeded = false;
        try {
            if (work != null) {
                output = worker.apply(work.input);
                succeeded = true;
            }
        } catch (RuntimeException failedCalculation) {
            // One failed prediction must not disable subsequent planning for this flight session.
        } finally {
            synchronized (lock) {
                if (succeeded && work.generation == generation) {
                    completed = new Completed<>(work.input, output);
                }
                scheduled = false;
                schedule();
            }
        }
    }
}
