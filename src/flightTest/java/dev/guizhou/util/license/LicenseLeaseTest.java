package dev.guizhou.util.license;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class LicenseLeaseTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        signedWindowLimits(); initialAndRecovery(); expiryBoundaries(); denialCannotRevive(); clocks(); development(); concurrentObservations();
        System.out.println("LicenseLeaseTest passed: " + checks + " checks");
    }

    private static void signedWindowLimits() {
        AtomicLong time = new AtomicLong();
        var lease = new LicenseLease(false, 100, time::get);
        lease.verifiedSuccess(20, 80); time.set(19);
        check(lease.authorized() && lease.snapshot().state() == LicenseLease.State.OK, "Fresh signed grant");
        time.set(20); check(lease.authorized() && lease.snapshot().state() == LicenseLease.State.GRACE, "Freshness deadline enters existing grace");
        time.set(79); lease.networkFailure(); check(lease.authorized(), "Transient outage preserves bounded allowance");
        time.set(80); check(!lease.authorized(), "Signed expiry is enforced earlier than local default");
        lease.verifiedSuccess(30, 1000); time.set(180); check(!lease.authorized(), "Remote grant cannot increase local allowance");
        lease.verifiedSuccess(0, 100); check(!lease.authorized(), "Invalid fresh grant cannot authorize");
        lease.verifiedSuccess(101, 100); check(!lease.authorized(), "Invalid ordering cannot authorize");
        lease.verifiedSuccess(1, LicenseLease.DEFAULT_LEASE_NANOS + 1); check(!lease.authorized(), "Unbounded grant rejected");
        lease.verifiedSuccess(20,80); lease.deny("banned"); lease.networkFailure(); check(!lease.authorized(), "Network failure cannot restore a denied signed grant");
    }

    private static void initialAndRecovery() {
        AtomicLong time = new AtomicLong();
        var lease = new LicenseLease(false, 100, time::get);
        check(lease.snapshot().state() == LicenseLease.State.PENDING && !lease.authorized(), "Startup is pending and closed");
        lease.networkFailure();
        check(lease.snapshot().state() == LicenseLease.State.DENIED && !lease.authorized(), "First offline startup has no grace");
        check(lease.snapshot().reason().equals("network_timeout") && lease.snapshot().networkFailures() == 1,
                "Initial transport failure has an explanatory reason");
        lease.verifiedSuccess();
        check(lease.authorized() && lease.snapshot().state() == LicenseLease.State.OK, "Verified success recovers an initial failure");
        check(lease.snapshot().networkFailures() == 0 && lease.snapshot().reason().isEmpty(), "Success clears old transport errors");
        time.set(40); lease.networkFailure();
        check(lease.authorized() && lease.snapshot().state() == LicenseLease.State.GRACE, "Valid lease tolerates transport failure");
        time.set(99); lease.networkFailure();
        check(lease.authorized() && lease.snapshot().networkFailures() == 2, "Further failures retain original lease before deadline");
        time.set(100);
        check(!lease.authorized() && lease.snapshot().reason().equals("lease_expired"), "Failures never renew the deadline");
    }

    private static void expiryBoundaries() {
        AtomicLong time = new AtomicLong(-10);
        var lease = new LicenseLease(false, 100, time::get);
        lease.verifiedSuccess();
        time.set(89);
        check(lease.authorized(), "Negative nanoTime origin is valid up to the last nanosecond");
        time.set(90);
        check(!lease.authorized() && lease.snapshot().state() == LicenseLease.State.DENIED,
                "Exact deadline denies without needing another heartbeat");
        long generation = lease.snapshot().denialGeneration();
        time.set(91); lease.networkFailure();
        check(!lease.authorized() && lease.snapshot().denialGeneration() == generation, "Expiry notification is not repeated by network failure");
        lease.verifiedSuccess();
        time.set(190);
        check(lease.authorized(), "New verified success establishes a fresh independent deadline");
        time.set(191);
        check(lease.snapshot().state() == LicenseLease.State.DENIED, "Status reads also expire a stale OK state");
        check(lease.snapshot().denialGeneration() == generation + 1, "A recovered lease can produce a new denial notification");
        expectBadDuration(0); expectBadDuration(-1); expectBadDuration(LicenseLease.DEFAULT_LEASE_NANOS + 1);
        check(LicenseLease.DEFAULT_LEASE_NANOS == TimeUnit.HOURS.toNanos(24), "Production maximum remains exactly 24 hours");
    }

    private static void denialCannotRevive() {
        for (String reason : List.of("banned", "expired", "hwid", "bad_signature", "no_credential")) {
            AtomicLong time = new AtomicLong(5);
            var lease = new LicenseLease(false, 100, time::get);
            lease.verifiedSuccess(); lease.deny(reason);
            check(!lease.authorized() && lease.snapshot().reason().equals(reason), "Explicit " + reason + " immediately closes a valid lease");
            long generation = lease.snapshot().denialGeneration();
            time.set(6); lease.networkFailure(); time.set(7); lease.networkFailure();
            check(!lease.authorized() && lease.snapshot().reason().equals(reason)
                            && lease.snapshot().denialGeneration() == generation,
                    "Transport failure cannot revive or hide " + reason);
            lease.deny(reason);
            check(lease.snapshot().denialGeneration() == generation, "Repeated rejection does not spam notifications");
            lease.verifiedSuccess();
            check(lease.authorized() && lease.snapshot().networkFailures() == 0, "Only fresh verified success recovers " + reason);
        }
    }

    private static void clocks() {
        AtomicLong time = new AtomicLong(100);
        var lease = new LicenseLease(false, 100, time::get);
        lease.verifiedSuccess(); time.set(150); check(lease.authorized(), "Forward monotonic progress is accepted");
        time.set(149);
        check(!lease.authorized() && lease.snapshot().reason().equals("clock_regression"), "Backwards movement within a lease closes it");
        time.set(150); lease.networkFailure();
        check(!lease.authorized(), "Returning the clock forward cannot revive a clock rejection");
        lease.verifiedSuccess(); time.set(1000);
        check(!lease.authorized(), "A large forward jump expires the lease");
        time.set(Long.MAX_VALUE - 10); lease.verifiedSuccess();
        time.set((Long.MAX_VALUE - 10) + 99);
        check(lease.authorized(), "Natural signed nanoTime wraparound keeps a valid interval");
        time.set((Long.MAX_VALUE - 10) + 100);
        check(!lease.authorized(), "Deadline remains exact across signed nanoTime wraparound");
        AtomicBoolean failClock = new AtomicBoolean(false);
        var brokenClock = new LicenseLease(false, 100, () -> {
            if (failClock.get()) throw new IllegalStateException("Synthetic unavailable clock");
            return 0;
        });
        brokenClock.verifiedSuccess(); failClock.set(true);
        check(!brokenClock.authorized() && brokenClock.snapshot().reason().equals("clock_error"), "Clock failure cannot leave an old OK unlocked");
        brokenClock.verifiedSuccess();
        check(!brokenClock.authorized(), "Success cannot create an unbounded lease without a usable clock");
    }

    private static void development() {
        var dev = new LicenseLease(true, 100, () -> { throw new AssertionError("Development must not read the clock"); });
        dev.networkFailure(); dev.deny("test"); dev.verifiedSuccess();
        check(dev.authorized() && dev.snapshot().state() == LicenseLease.State.OK, "Explicit development initialization has no lease deadline");
        check(dev.snapshot().networkFailures() == 0 && dev.snapshot().denialGeneration() == 0, "Development bypass creates no failure notifications");
    }

    private static void concurrentObservations() throws Exception {
        var lease = new LicenseLease(false, 100, () -> 0);
        ExecutorService workers = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> observations = new ArrayList<>();
        try {
            observations.add(workers.submit(() -> {
                await(start);
                for (int i = 0; i < 2_000; i++) {
                    lease.verifiedSuccess(); lease.networkFailure(); lease.deny("explicit_test");
                }
            }));
            for (int reader = 0; reader < 2; reader++) observations.add(workers.submit(() -> {
                await(start);
                long lastGeneration = 0;
                for (int i = 0; i < 5_000; i++) {
                    var snapshot = lease.snapshot();
                    if (snapshot.denialGeneration() < lastGeneration) throw new AssertionError("Denial generation moved backward");
                    lastGeneration = snapshot.denialGeneration();
                    if (snapshot.state() == LicenseLease.State.OK && (snapshot.networkFailures() != 0 || !snapshot.reason().isEmpty()))
                        throw new AssertionError("Observed partially published verified success");
                    if (snapshot.state() == LicenseLease.State.GRACE && (snapshot.networkFailures() < 1 || !snapshot.reason().isEmpty()))
                        throw new AssertionError("Observed partially published grace transition");
                    if (snapshot.state() == LicenseLease.State.DENIED && !snapshot.reason().equals("explicit_test"))
                        throw new AssertionError("Observed denial without its reason");
                }
            }));
            start.countDown();
            for (Future<?> observation : observations) observation.get(5, TimeUnit.SECONDS);
            check(!lease.authorized() && lease.snapshot().denialGeneration() == 2_000,
                    "Concurrent readers observe coherent states and final writer rejection");
            lease.networkFailure();
            check(!lease.authorized(), "Main thread cannot restore a worker's rejection through transport failure");
        } finally {
            workers.shutdownNow();
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) throw new AssertionError("Test workers did not terminate");
        }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test start timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static void expectBadDuration(long duration) {
        boolean rejected = false;
        try { new LicenseLease(false, duration, () -> 0); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Invalid lease duration is rejected: " + duration);
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
