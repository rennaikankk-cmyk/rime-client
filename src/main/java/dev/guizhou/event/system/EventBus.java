package dev.guizhou.event.system;

import dev.guizhou.event.Event;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class EventBus {
    private final Map<Class<?>, CopyOnWriteArrayList<Listener>> listeners = new ConcurrentHashMap<>();
    private final Set<Object> registeredHosts = Collections.newSetFromMap(new IdentityHashMap<>());

    public synchronized void register(Object host) {
        if (registeredHosts.contains(host)) return;
        List<Subscription> subscriptions = new ArrayList<>();
        collect(host, host.getClass(), subscriptions);
        for (Subscription subscription : subscriptions) {
            List<Listener> registry = listeners.computeIfAbsent(subscription.eventType(),
                    k -> new CopyOnWriteArrayList<>());
            register(registry, subscription.listener());
        }
        registeredHosts.add(host);
    }

    public synchronized void unregister(Object host) {
        for (CopyOnWriteArrayList<Listener> list : listeners.values()) {
            list.removeIf(listener -> listener.host() == host);
        }
        registeredHosts.remove(host);
    }

    public boolean post(Event event) {
        List<Listener> list = listeners.get(event.getClass());
        if (list == null) return event.isCancelled();

        // timing is opt-in: two nanoTime calls per listener per event is real
        // overhead, so we only pay it while a profiling window is open (.timings)
        long deadline = TIMING_WINDOW_UNTIL.get();
        boolean timing = deadline != 0L && System.nanoTime() - deadline < 0L;
        if (!timing && deadline != 0L) TIMING_WINDOW_UNTIL.compareAndSet(deadline, 0L);
        for (Listener listener : list) {
            if (event.isCancelled()) {
                return true;
            }
            long start = timing ? System.nanoTime() : 0L;
            try {
                listener.invoke(event);
            } catch (Throwable t) {
                // One throwing listener must not starve the rest of the chain (a packet-handler
                // NPE used to silently abort every later subscriber for that packet type).
                reportListenerFailure(listener, event, t);
            }
            if (timing) {
                recordTiming(listener, System.nanoTime() - start);
            }
        }

        return event.isCancelled();
    }

    // Elapsed listener time includes nested event dispatch, not exclusive CPU time.
    private static final Map<String, Timing> TIMINGS = new ConcurrentHashMap<>();
    private static final AtomicLong TIMING_WINDOW_UNTIL = new AtomicLong();

    private static final class Timing {
        final LongAdder nanos = new LongAdder();
        final LongAdder calls = new LongAdder();
    }

    private record TimingSnapshot(String key, long nanos, long calls) {}
    private record Subscription(Class<?> eventType, Listener listener) {}

    /** Opens a profiling window: timings are collected for the next {@code seconds}. */
    public static void openTimingWindow(long seconds) {
        TIMING_WINDOW_UNTIL.set(seconds <= 0 ? 0L
                : System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.min(seconds, 86_400L)));
    }

    private static void recordTiming(Listener listener, long nanos) {
        Timing acc = TIMINGS.computeIfAbsent(listener.timingKey(), k -> new Timing());
        acc.nanos.add(nanos);
        acc.calls.increment();
    }

    /** Top-N listeners by accumulated elapsed time, using a stable report snapshot. */
    public static String timingsReport(int topN) {
        StringBuilder sb = new StringBuilder();
        TIMINGS.entrySet().stream()
                .map(e -> new TimingSnapshot(e.getKey(), e.getValue().nanos.sum(), e.getValue().calls.sum()))
                .sorted((a, b) -> Long.compare(b.nanos(), a.nanos()))
                .limit(Math.max(0, topN))
                .forEach(e -> sb.append(String.format(Locale.ROOT, "%s: %.2f ms total, %d calls, %.1f us/call%n",
                        e.key(), e.nanos() / 1e6, e.calls(),
                        e.calls() == 0 ? 0 : e.nanos() / 1e3 / e.calls())));
        return sb.toString();
    }

    public static void resetTimings() {
        TIMINGS.clear();
    }

    private static final Set<String> reportedFailures = ConcurrentHashMap.newKeySet();

    private static void reportListenerFailure(Listener listener, Event event, Throwable t) {
        String key = listener.host().getClass().getName() + "#" + t.getClass().getName();
        if (reportedFailures.add(key)) {
            System.err.println("[GuizhouWare] event listener " + listener.host().getClass().getName()
                    + " threw on " + event.getClass().getSimpleName() + ": " + t);
            t.printStackTrace();
        }
    }

    private void collect(Object host, Class<?> klass, List<Subscription> subscriptions) {
        for (Method method : klass.getDeclaredMethods()) {
            Subscribe subscribe = method.getAnnotation(Subscribe.class);
            if (subscribe == null) continue;

            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1 && Event.class.isAssignableFrom(params[0]) && method.getReturnType() == void.class) {
                Class<?> eventType = params[0];
                Listener listener = Listener.of(host, subscribe.priority(), method);
                subscriptions.add(new Subscription(eventType, listener));
            }
        }

        if (klass.getSuperclass() != null)
            collect(host, klass.getSuperclass(), subscriptions);
    }

    private void register(List<Listener> registry, Listener target) {
        int i = 0;
        for (Listener listener : registry) {
            if (target.priority() > listener.priority()) break;
            i++;
        }
        registry.add(i, target);
    }
}
