package dev.guizhou.event.system;

import java.lang.invoke.*;
import java.lang.reflect.Method;
import java.util.function.Consumer;

public record Listener(Object host, int priority, Consumer<Object> consumer, String timingKey) {
    public static Listener of(Object host, int priority, Method method) {
        return of(host, priority, method, host.getClass().getSimpleName());
    }

    public static Listener of(Object host, int priority, Method method, String stableName) {
        // Production registrations supply literals or Module.getName(). @Subscribe method names
        // are kept by ProGuard; class simple names and user-editable display names are not stable.
        String key = "listener." + stableName + "." + method.getName();
        return new Listener(host, priority, buildLambdaMetafactory(host, method), key);
    }

    public void invoke(Object event) {
        consumer.accept(event);
    }

    public Object getHost() {
        return host;
    }

    @SuppressWarnings("unchecked")
    private static Consumer<Object> buildLambdaMetafactory(Object host, Method method) {
        try {
            Class<?> declaringClass = method.getDeclaringClass();
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(declaringClass, MethodHandles.lookup());
            CallSite site = LambdaMetafactory.metafactory(
                    lookup,
                    "accept",
                    MethodType.methodType(Consumer.class, declaringClass),
                    MethodType.methodType(void.class, Object.class),
                    lookup.unreflect(method),
                    MethodType.methodType(void.class, method.getParameterTypes()[0])
            );
            MethodHandle target = site.getTarget();
            return (Consumer<Object>) (target.invoke(host));
        } catch (Throwable e) {
            throw new RuntimeException("Failed to build lambda from %s method".formatted(method.getName()), e);
        }
    }
}
