package dev.guizhou.util.license;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Process-local licence lease. Only an independently verified successful response may call
 * {@link #verifiedSuccess()}; this policy performs no networking, signature verification or I/O.
 * The development exemption is fixed at construction and cannot be enabled by a later transition.
 */
public final class LicenseLease {
    public static final long DEFAULT_LEASE_NANOS = TimeUnit.HOURS.toNanos(24);
    public enum State { PENDING, OK, GRACE, DENIED }
    public record Snapshot(State state, String reason, int networkFailures, long denialGeneration) {}

    private final boolean development;
    private final long leaseNanos;
    private final LongSupplier clock;
    private State state;
    private String reason = "";
    private int networkFailures;
    private long denialGeneration;
    private boolean hasLease;
    private long startedNanos;
    private long observedNanos;
    private long activeLeaseNanos;
    private long freshLeaseNanos;

    public LicenseLease(boolean development) {
        this(development, DEFAULT_LEASE_NANOS, System::nanoTime);
    }

    /** A shorter lease is useful for tests; production callers cannot increase the 24-hour bound. */
    public LicenseLease(boolean development, long leaseNanos, LongSupplier nanoClock) {
        if (leaseNanos <= 0 || leaseNanos > DEFAULT_LEASE_NANOS)
            throw new IllegalArgumentException("Lease duration must be positive and at most 24 hours");
        this.development = development;
        this.leaseNanos = leaseNanos;
        this.clock = Objects.requireNonNull(nanoClock, "nanoClock");
        state = development ? State.OK : State.PENDING;
    }

    /** Caller validates the response; this policy cannot determine whether a signed reply was replayed. */
    public synchronized void verifiedSuccess() { verifiedSuccess(leaseNanos, leaseNanos); }

    /** A server grant may shorten, but never increase, the local allowance. */
    public synchronized void verifiedSuccess(long freshNanos, long totalNanos) {
        if (development) return;
        if (freshNanos <= 0 || totalNanos < freshNanos || totalNanos > DEFAULT_LEASE_NANOS) {
            denyInternal("invalid_lease");
            return;
        }
        activeLeaseNanos = Math.min(leaseNanos, totalNanos);
        freshLeaseNanos = Math.min(activeLeaseNanos, freshNanos);
        final long now;
        try { now = clock.getAsLong(); }
        catch (RuntimeException failure) { denyInternal("clock_error"); return; }
        startedNanos = observedNanos = now;
        hasLease = true;
        networkFailures = 0;
        reason = "";
        state = State.OK;
    }

    /** Explicit rejection immediately destroys the prior lease. Transport failure cannot restore it. */
    public synchronized void deny(String reason) {
        if (!development) denyInternal(reason == null || reason.isBlank() ? "denied" : reason);
    }

    public synchronized void networkFailure() {
        if (development) return;
        if (networkFailures < Integer.MAX_VALUE) networkFailures++;
        refreshLease();
        if (hasLease) state = State.GRACE;
        else if (state != State.DENIED) denyInternal("network_timeout");
    }

    /** Authorization always checks age, even if the heartbeat executor has stopped making progress. */
    public synchronized boolean authorized() {
        if (development) return true;
        refreshLease();
        return hasLease && (state == State.OK || state == State.GRACE);
    }

    /** One atomic observation for status text and once-per-denial notification bookkeeping. */
    public synchronized Snapshot snapshot() {
        if (!development) refreshLease();
        return new Snapshot(state, reason, networkFailures, denialGeneration);
    }

    private void refreshLease() {
        if (!hasLease) return;
        final long now;
        try { now = clock.getAsLong(); }
        catch (RuntimeException failure) { denyInternal("clock_error"); return; }
        // Subtraction handles nanoTime's signed wraparound. Genuine backward movement never
        // extends a lease; the next verified success is required before authorization can recover.
        if (now - observedNanos < 0 || now - startedNanos < 0) {
            denyInternal("clock_regression");
        } else if (now - startedNanos >= activeLeaseNanos) {
            denyInternal("lease_expired");
        } else {
            observedNanos = now;
            if (now - startedNanos >= freshLeaseNanos) state = State.GRACE;
        }
    }

    private void denyInternal(String reason) {
        hasLease = false;
        startedNanos = observedNanos = 0;
        if (state != State.DENIED && denialGeneration < Long.MAX_VALUE) denialGeneration++;
        state = State.DENIED;
        this.reason = reason;
    }
}
