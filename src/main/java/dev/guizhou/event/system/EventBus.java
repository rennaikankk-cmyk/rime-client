package dev.guizhou.event.system;

import dev.guizhou.event.Event;
import dev.guizhou.util.diagnostics.ClientDiagnostics;

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

public class EventBus {
    private final Map<Class<?>, CopyOnWriteArrayList<Listener>> listeners = new ConcurrentHashMap<>();
    private final Set<Object> registeredHosts = Collections.newSetFromMap(new IdentityHashMap<>());

    public synchronized void register(Object host) {
        register(host, host.getClass().getSimpleName());
    }

    public synchronized void register(Object host, String stableName) {
        if (registeredHosts.contains(host)) return;
        List<Subscription> subscriptions = new ArrayList<>();
        collect(host, host.getClass(), stableName, subscriptions);
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

        for (Listener listener : list) {
            if (event.isCancelled()) {
                return true;
            }
            long start = ClientDiagnostics.startTimer();
            try {
                listener.invoke(event);
            } catch (RuntimeException t) {
                // One throwing listener must not starve the rest of the chain (a packet-handler
                // NPE used to silently abort every later subscriber for that packet type).
                reportListenerFailure(listener, event, t);
            }
            finally {
                ClientDiagnostics.endTimer(listener.timingKey(), start);
            }
        }

        return event.isCancelled();
    }

    private record Subscription(Class<?> eventType, Listener listener) {}

    /** Opens a profiling window: timings are collected for the next {@code seconds}. */
    public static void openTimingWindow(long seconds) {
        if (seconds <= 0) ClientDiagnostics.stop();
        else ClientDiagnostics.start((int) Math.min(seconds, ClientDiagnostics.MAX_SECONDS));
    }

    /** Top-N inclusive elapsed scopes. Parent listener and child module totals overlap. */
    public static String timingsReport(int topN) {
        StringBuilder sb = new StringBuilder();
        ClientDiagnostics.snapshot().timings().entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().totalNanos(), a.getValue().totalNanos()))
                .limit(Math.max(0, topN))
                .forEach(e -> sb.append(String.format(Locale.ROOT,
                        "%s: %.2f ms total, %d calls, %.1f us/call, peak %.1f us, P95~ %.1f us%n",
                        e.getKey(), e.getValue().totalNanos() / 1e6, e.getValue().count(),
                        e.getValue().meanNanos() / 1e3, e.getValue().maxNanos() / 1e3, e.getValue().p95Nanos() / 1e3)));
        return sb.toString();
    }

    public static void resetTimings() {
        ClientDiagnostics.clear();
    }

    private static final Set<String> reportedFailures = ConcurrentHashMap.newKeySet();

    private static void reportListenerFailure(Listener listener, Event event, Throwable t) {
        ClientDiagnostics.count(listener.timingKey() + ".failures", 1);
        String key = listener.timingKey() + "#" + t.getClass().getName();
        if (reportedFailures.add(key)) {
            System.err.println("[GuizhouWare] " + listener.timingKey() + " threw: " + t);
            t.printStackTrace();
        }
    }

    private void collect(Object host, Class<?> klass, String stableName, List<Subscription> subscriptions) {
        for (Method method : klass.getDeclaredMethods()) {
            Subscribe subscribe = method.getAnnotation(Subscribe.class);
            if (subscribe == null) continue;

            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1 && Event.class.isAssignableFrom(params[0]) && method.getReturnType() == void.class) {
                Class<?> eventType = params[0];
                Listener listener = Listener.of(host, subscribe.priority(), method, stableName);
                subscriptions.add(new Subscription(eventType, listener));
            }
        }

        if (klass.getSuperclass() != null)
            collect(host, klass.getSuperclass(), stableName, subscriptions);
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
