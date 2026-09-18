package dev.guizhou.event.system;

import dev.guizhou.event.Event;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class EventBusTest {
    private static int checks;

    public static void main(String[] args) throws InterruptedException {
        duplicateAndIdentityRegistration();
        inheritedPrivateListener();
        priorityAndCancellation();
        failureIsolation();
        concurrentTimings();
        System.out.println("EventBusTest passed: " + checks + " checks");
    }

    private static void duplicateAndIdentityRegistration() {
        EventBus bus = new EventBus();
        EqualReceiver first = new EqualReceiver();
        EqualReceiver second = new EqualReceiver();
        bus.register(first);
        bus.register(first);
        bus.register(second);
        bus.post(new SampleEvent());
        check(first.calls == 1 && second.calls == 1, "Register exactly once per instance");
        bus.unregister(first);
        bus.post(new SampleEvent());
        check(first.calls == 1 && second.calls == 2, "Unregister uses identity, not equals");
        bus.register(first);
        bus.post(new SampleEvent());
        check(first.calls == 2 && second.calls == 3, "Re-enable registers again");
    }

    private static void inheritedPrivateListener() {
        EventBus bus = new EventBus();
        ChildReceiver child = new ChildReceiver();
        bus.register(child);
        bus.post(new SampleEvent());
        check(((ParentReceiver) child).calls == 1, "Superclass private listener is callable");
    }

    private static void priorityAndCancellation() {
        EventBus bus = new EventBus();
        List<String> order = new ArrayList<>();
        bus.register(new LowReceiver(order));
        bus.register(new HighReceiver(order));
        check(bus.post(new SampleEvent()), "Cancellation is propagated");
        check(order.equals(List.of("high")), "Higher priority cancels before low listener");
        SampleEvent cancelled = new SampleEvent();
        cancelled.cancel();
        check(new EventBus().post(cancelled), "Cancellation survives an empty registry");
    }

    private static void failureIsolation() {
        EventBus bus = new EventBus();
        EqualReceiver healthy = new EqualReceiver();
        bus.register(new ThrowingReceiver());
        bus.register(healthy);
        bus.post(new SampleEvent());
        check(healthy.calls == 1, "One throwing listener does not starve later listeners");
    }

    private static void concurrentTimings() throws InterruptedException {
        EventBus bus = new EventBus();
        CounterReceiver receiver = new CounterReceiver();
        bus.register(receiver);
        EventBus.resetTimings();
        EventBus.openTimingWindow(60);
        int workers = 6;
        int iterations = 10_000;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int j = 0; j < iterations; j++) bus.post(new SampleEvent());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            });
            threads.add(thread);
            thread.start();
        }
        ready.await();
        start.countDown();
        for (Thread thread : threads) thread.join();
        check(receiver.calls.get() == workers * iterations, "All concurrent callbacks executed");
        String report = EventBus.timingsReport(10);
        check(report.contains("60000 calls"), "Concurrent timing counters lose no calls");
        EventBus.openTimingWindow(0);
        bus.post(new SampleEvent());
        check(EventBus.timingsReport(10).equals(report), "Closed timing windows stop accumulating");
        check(EventBus.timingsReport(-1).isEmpty(), "Negative report limit is bounded");
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            check(EventBus.timingsReport(10).equals(report), "Report numbers are locale independent");
        } finally {
            Locale.setDefault(previous);
            EventBus.resetTimings();
        }
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static final class SampleEvent extends Event {}

    private static final class EqualReceiver {
        int calls;
        @Subscribe private void onSample(SampleEvent event) { calls++; }
        @Override public boolean equals(Object other) { return other instanceof EqualReceiver; }
        @Override public int hashCode() { return 1; }
    }

    private static class ParentReceiver {
        private int calls;
        @Subscribe private void onSample(SampleEvent event) { calls++; }
    }

    private static final class ChildReceiver extends ParentReceiver {}

    private record LowReceiver(List<String> order) {
        @Subscribe(priority = -1) private void onSample(SampleEvent event) { order.add("low"); }
    }

    private record HighReceiver(List<String> order) {
        @Subscribe(priority = 10) private void onSample(SampleEvent event) {
            order.add("high");
            event.cancel();
        }
    }

    private static final class ThrowingReceiver {
        @Subscribe(priority = 20) private void onSample(SampleEvent event) {
            throw new IllegalStateException("Expected failure-isolation test");
        }
    }

    private static final class CounterReceiver {
        final AtomicInteger calls = new AtomicInteger();
        @Subscribe private void onSample(SampleEvent event) { calls.incrementAndGet(); }
    }
}
