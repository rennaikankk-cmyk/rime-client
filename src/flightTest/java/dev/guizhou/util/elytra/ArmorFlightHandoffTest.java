package dev.guizhou.util.elytra;

public final class ArmorFlightHandoffTest {
    public static void main(String[] args) {
        var state = new ArmorFlightHandoff();
        state.observe(false, false, true);
        check(!state.resumeRequested(), "Enabling in free fall is not a previous flight");
        state.observe(true, false, false);
        state.observe(false, false, true);
        check(state.resumeRequested(), "Foreign takeover can clear glide before our next tick");
        for (int i = 0; i < 80; i++) state.observe(false, false, true);
        state.observe(false, false, false);
        check(state.resumeRequested(), "Takeover must retain restart until glide is observed");
        state.observe(true, false, false);
        check(!state.resumeRequested(), "Successful glide clears restart");
        state.observe(false, false, true);
        state.observe(false, true, true);
        state.observe(false, false, false);
        check(!state.wasAirborne() && !state.resumeRequested(), "Landing does not cause a later unsolicited takeoff");
        state.observe(true, false, true);
        state.reset();
        check(!state.wasAirborne() && !state.resumeRequested(), "Correction, disable and session reset discard old flight");
        System.out.println("ArmorFlightHandoffTest passed: 6 checks");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
