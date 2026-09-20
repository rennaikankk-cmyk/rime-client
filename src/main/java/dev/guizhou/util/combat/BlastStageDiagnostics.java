package dev.guizhou.util.combat;

import dev.guizhou.util.diagnostics.ClientDiagnostics;

/** Observes existing state-machine decisions; it does not authorize or delay any action. */
public final class BlastStageDiagnostics {
    private final String prefix;
    private String awaiting;
    private long timer;
    private long recording;
    private int submittedTick;

    public BlastStageDiagnostics(String module) { prefix = "combat." + module; }

    public void decision(String action, int tick, int timeout) {
        refreshRecording();
        if ("WAIT".equals(action)) { ClientDiagnostics.count(prefix + ".waitTicks", 1); return; }
        if ("ABORT".equals(action)) {
            ClientDiagnostics.count(prefix + (awaiting != null && tick - submittedTick >= timeout ? ".timeout" : ".stateAbort"), 1);
            close(false);
        } else if ("COMPLETE".equals(action)) close(true);
        else if (awaiting != null && !awaiting.equals(action)) close(true);
    }

    public void submitted(String action, int tick) {
        refreshRecording();
        ClientDiagnostics.count(prefix + "." + action + ".submitted", 1);
        if (action.equals(awaiting))
            ClientDiagnostics.count(prefix + "." + action + ".retry", 1);
        else { awaiting = action; timer = ClientDiagnostics.startTimer(); }
        submittedTick = tick;
    }

    public void blocked(String reason) { ClientDiagnostics.count(prefix + ".blocked." + reason, 1); }
    public void reset() { close(false); }
    private void refreshRecording() {
        long current = ClientDiagnostics.recordingId();
        if (recording == current) return;
        awaiting = null;
        timer = 0;
        recording = current;
    }
    private void close(boolean confirmed) {
        if (awaiting == null) return;
        if (recording != 0 && recording == ClientDiagnostics.recordingId()) {
            ClientDiagnostics.endTimer(prefix + "." + awaiting + (confirmed ? ".confirmedWait" : ".abortedWait"), timer);
            ClientDiagnostics.count(prefix + "." + awaiting + (confirmed ? ".observed" : ".aborted"), 1);
        }
        awaiting = null;
        timer = 0;
        recording = 0;
    }
}
