package dev.guizhou.event.system;

import dev.guizhou.event.Event;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class EventBus {
    private final Map<Class<?>, CopyOnWriteArrayList<Listener>> listeners = new ConcurrentHashMap<>();

    public void register(Object host) {
        register(host, host.getClass());
    }

    public void unregister(Object host) {
        for (CopyOnWriteArrayList<Listener> list : listeners.values()) {
            list.removeIf(listener -> listener.getHost().equals(host));
        }
    }

    public boolean post(Event event) {
        List<Listener> list = listeners.get(event.getClass());
        if (list == null) return false;

        for (Listener listener : list) {
            if (event.isCancelled()) {
                return true;
            }
            try {
                listener.invoke(event);
            } catch (Throwable t) {
                // One throwing listener must not starve the rest of the chain (a packet-handler
                // NPE used to silently abort every later subscriber for that packet type).
                reportListenerFailure(listener, event, t);
            }
        }

        return event.isCancelled();
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

    private void register(Object host, Class<?> klass) {
        for (Method method : klass.getDeclaredMethods()) {
            Subscribe subscribe = method.getAnnotation(Subscribe.class);
            if (subscribe == null) continue;

            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1) {
                Class<?> eventType = params[0];

                Listener listener = Listener.of(host, subscribe.priority(), method);
                List<Listener> registry = listeners.computeIfAbsent(eventType,
                        k -> new CopyOnWriteArrayList<>());

                register(registry, listener);
            }
        }

        if (klass.getSuperclass() != null)
            register(host, klass.getSuperclass());
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
