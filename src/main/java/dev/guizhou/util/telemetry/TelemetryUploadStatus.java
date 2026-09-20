package dev.guizhou.util.telemetry;

import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Process-local receipt observations. Never stores credentials, payloads, URLs, or exception messages.
 * A 2xx response means receiver confirmation only, not independent proof of durable storage. */
public final class TelemetryUploadStatus {
    public enum Outcome { NEVER_SENT, SENDING, RECEIVER_CONFIRMED, FAILED }
    public enum Failure { NONE, HTTP, TIMEOUT, NETWORK, INVALID_RESPONSE }

    public record Snapshot(Outcome outcome, long attempts, long confirmedBatches, long failedBatches,
                           int consecutiveFailures, long lastAttemptAt, int lastBatchEvents,
                           long lastConfirmedAt, int lastConfirmedHttp, int lastConfirmedBatchEvents,
                           long lastFailureAt, int lastFailureHttp, Failure lastFailure, int lastFailedBatchEvents) {}

    private Outcome outcome = Outcome.NEVER_SENT;
    private long attempts, confirmedBatches, failedBatches;
    private int consecutiveFailures;
    private long lastAttemptAt, lastConfirmedAt, lastFailureAt;
    private int lastBatchEvents, lastConfirmedHttp, lastConfirmedBatchEvents, lastFailureHttp, lastFailedBatchEvents;
    private Failure lastFailure = Failure.NONE;

    public synchronized long started(int batchEvents, long nowMillis) {
        attempts++;
        lastAttemptAt = Math.max(0, nowMillis);
        lastBatchEvents = Math.max(0, Math.min(128, batchEvents));
        outcome = Outcome.SENDING;
        return attempts;
    }

    /** An old or duplicate asynchronous completion must not overwrite newer status. */
    public synchronized void completed(long attempt, int httpStatus, Throwable failure, long nowMillis) {
        if (attempt != attempts || outcome != Outcome.SENDING) return;
        int code = httpStatus >= 100 && httpStatus <= 599 ? httpStatus : 0;
        if (failure == null && code >= 200 && code < 300) {
            outcome = Outcome.RECEIVER_CONFIRMED;
            confirmedBatches++;
            consecutiveFailures = 0;
            lastConfirmedAt = Math.max(0, nowMillis);
            lastConfirmedHttp = code;
            lastConfirmedBatchEvents = lastBatchEvents;
        } else {
            outcome = Outcome.FAILED;
            failedBatches++;
            consecutiveFailures = Math.min(Integer.MAX_VALUE - 1, consecutiveFailures) + 1;
            lastFailureAt = Math.max(0, nowMillis);
            lastFailureHttp = code;
            lastFailure = classify(code, failure);
            lastFailedBatchEvents = lastBatchEvents;
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(outcome, attempts, confirmedBatches, failedBatches, consecutiveFailures,
                lastAttemptAt, lastBatchEvents, lastConfirmedAt, lastConfirmedHttp, lastConfirmedBatchEvents,
                lastFailureAt, lastFailureHttp, lastFailure, lastFailedBatchEvents);
    }

    private static Failure classify(int code, Throwable cause) {
        for (int depth = 0; depth < 8 && (cause instanceof CompletionException || cause instanceof ExecutionException); depth++) {
            if (cause.getCause() == null || cause.getCause() == cause) break;
            cause = cause.getCause();
        }
        if (cause instanceof HttpTimeoutException || cause instanceof java.net.SocketTimeoutException
                || cause instanceof java.util.concurrent.TimeoutException) return Failure.TIMEOUT;
        if (cause != null) return Failure.NETWORK;
        return code == 0 ? Failure.INVALID_RESPONSE : Failure.HTTP;
    }
}
