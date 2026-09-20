package dev.guizhou.util.combat;

import dev.guizhou.util.diagnostics.ClientDiagnostics;

/** Exercises observed state transitions, retries, aborts and recording-window boundaries. */
public final class BlastStageDiagnosticsTest {
    private static int checks;
    public static void main(String[] args) {
        try {
            ClientDiagnostics.start(60);
            var sequence = new AnchorSequence();
            var diagnostics = new BlastStageDiagnostics("AutoAnchor");
            step(sequence, diagnostics, false, 0, 0);
            step(sequence, diagnostics, false, 0, 1);
            step(sequence, diagnostics, false, 0, 4);
            step(sequence, diagnostics, true, 0, 5);
            step(sequence, diagnostics, true, 1, 6);
            step(sequence, diagnostics, false, 0, 7);
            var complete = ClientDiagnostics.snapshot();
            check(count(complete, "PLACE.submitted") == 2 && count(complete, "PLACE.retry") == 1,
                    "Retry remains a submission, not an observed placement");
            check(count(complete, "waitTicks") == 1 && count(complete, "PLACE.observed") == 1,
                    "Observed anchor state confirms the submitted place exactly once");
            check(count(complete, "CHARGE.observed") == 1 && count(complete, "DETONATE.observed") == 1,
                    "Charge and anchor removal independently confirm their stages");
            check(complete.timings().get("combat.AutoAnchor.PLACE.confirmedWait").count() == 1,
                    "Retry contributes to one complete placement wait measurement");
            step(sequence, diagnostics, false, 0, 10);
            step(sequence, diagnostics, false, 0, 14);
            step(sequence, diagnostics, false, 0, 18);
            var failed = ClientDiagnostics.snapshot();
            check(count(failed, "timeout") == 1 && count(failed, "PLACE.aborted") == 1,
                    "Exhausted attempts record timeout and no observed placement");
            diagnostics.submitted("PLACE", 20);
            ClientDiagnostics.start(60);
            diagnostics.decision("CHARGE", 21, 4);
            check(count(ClientDiagnostics.snapshot(), "PLACE.observed") == 0,
                    "A new recording never inherits prior-window submission confirmation");
            diagnostics.submitted("PLACE", 22);
            diagnostics.blocked("safety"); diagnostics.reset();
            check(count(ClientDiagnostics.snapshot(), "blocked.safety") == 1
                            && count(ClientDiagnostics.snapshot(), "PLACE.aborted") == 1,
                    "Safety stop is explained without fabricating block confirmation");
        } finally { ClientDiagnostics.clear(); }
        System.out.println("BlastStageDiagnosticsTest passed: " + checks + " checks");
    }
    private static void step(AnchorSequence sequence, BlastStageDiagnostics diagnostics, boolean anchor, int charges, int tick) {
        var action = sequence.next(anchor, charges, true, tick, 4, 2);
        diagnostics.decision(action.name(), tick, 4);
        if (action == AnchorSequence.Action.PLACE || action == AnchorSequence.Action.CHARGE || action == AnchorSequence.Action.DETONATE) {
            sequence.sent(action, tick); diagnostics.submitted(action.name(), tick);
        }
    }
    private static long count(ClientDiagnostics.Snapshot snapshot, String key) {
        return snapshot.counters().getOrDefault("combat.AutoAnchor." + key, 0L);
    }
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
}
