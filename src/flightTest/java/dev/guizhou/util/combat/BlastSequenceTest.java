package dev.guizhou.util.combat;

public final class BlastSequenceTest {
    private static int checks;
    public static void main(String[] args) {
        anchors();
        beds();
        timing();
        System.out.println("BlastSequenceTest passed: " + checks + " checks");
    }
    private static void anchors() {
        AnchorSequence s = new AnchorSequence();
        check(s.next(false, 0, true, 0, 8, 2) == AnchorSequence.Action.PLACE, "Empty anchor location places");
        check(s.next(false, 0, true, 1, 8, 2) == AnchorSequence.Action.PLACE, "Refused send never advances");
        s.sent(AnchorSequence.Action.PLACE, 1);
        check(s.next(false, 0, true, 8, 8, 2) == AnchorSequence.Action.WAIT, "No charge before observed anchor");
        check(s.next(true, 0, false, 2, 8, 2) == AnchorSequence.Action.CHARGE, "Observed zero-charge anchor advances immediately");
        s.sent(AnchorSequence.Action.CHARGE, 2);
        check(s.next(true, 0, false, 3, 8, 2) == AnchorSequence.Action.WAIT, "No detonation before observed charge");
        check(s.next(true, 1, false, 3, 8, 2) == AnchorSequence.Action.DETONATE, "One charge is sufficient");
        s.sent(AnchorSequence.Action.DETONATE, 3);
        check(s.next(true, 1, false, 10, 8, 2) == AnchorSequence.Action.WAIT, "Detonation send alone is not completion");
        check(s.next(false, 0, true, 4, 8, 2) == AnchorSequence.Action.COMPLETE, "Observed removal completes");
        check(s.next(true, 4, false, 5, 8, 2) == AnchorSequence.Action.DETONATE, "Full existing anchor never charges again");
        s.reset();
        s.sent(AnchorSequence.Action.PLACE, 0);
        check(s.next(false, 0, true, 8, 8, 2) == AnchorSequence.Action.PLACE, "Timeout permits bounded retry");
        s.sent(AnchorSequence.Action.PLACE, 8);
        check(s.next(false, 0, true, 16, 8, 2) == AnchorSequence.Action.ABORT, "Repeated rejection is bounded");
        s.reset();
        check(s.next(false, 0, true, 0, 8, 2) == AnchorSequence.Action.PLACE, "New session discards previous wait");
        s.sent(AnchorSequence.Action.CHARGE, 0);
        check(s.next(false, 0, true, 1, 8, 2) == AnchorSequence.Action.ABORT, "Mined anchor invalidates charging");
    }
    private static void beds() {
        BedSequence s = new BedSequence();
        check(s.next(false, false, true, 0, 8, 2) == BedSequence.Action.PLACE, "Empty bed location places");
        s.sent(BedSequence.Action.PLACE, 0);
        check(s.next(false, true, false, 1, 8, 2) == BedSequence.Action.WAIT, "Partial pair update waits");
        check(s.next(true, true, false, 2, 8, 2) == BedSequence.Action.DETONATE, "Matched pair permits detonation");
        s.sent(BedSequence.Action.DETONATE, 2);
        check(s.next(false, true, false, 3, 8, 2) == BedSequence.Action.WAIT, "Missing foot alone does not confirm explosion");
        check(s.next(false, false, true, 4, 8, 2) == BedSequence.Action.COMPLETE, "Head removal confirms source gone");
        check(s.next(true, true, false, 5, 8, 2) == BedSequence.Action.DETONATE, "Existing bed needs no bed item");
        s.reset();
        check(s.next(false, true, false, 0, 8, 2) == BedSequence.Action.ABORT, "Mismatched pair does not detonate");
        s.sent(BedSequence.Action.PLACE, 0);
        check(s.next(false, false, true, 8, 8, 2) == BedSequence.Action.PLACE, "Rejected placement retries on timeout");
        s.sent(BedSequence.Action.PLACE, 8);
        check(s.next(false, false, true, 16, 8, 2) == BedSequence.Action.ABORT, "Rejected bed placement has attempt limit");
        s.reset();
        s.sent(BedSequence.Action.DETONATE, 0);
        check(s.next(true, true, false, 8, 8, 2) == BedSequence.Action.DETONATE, "Rejected detonation retries live bed");
        s.sent(BedSequence.Action.DETONATE, 8);
        check(s.next(true, true, false, 16, 8, 2) == BedSequence.Action.ABORT, "Rejected detonation has attempt limit");
        s.reset();
        check(s.next(false, false, true, 0, 8, 2) == BedSequence.Action.PLACE, "New session drops old bed state");
    }
    private static void timing() {
        AnchorTiming timing = new AnchorTiming();
        check(timing.horizon(true, 1, 20, 0, 0, 0) == 1, "Charged anchor uses one remaining stage");
        check(timing.horizon(true, 0, 20, 0, 0, 0) == 2, "Empty anchor includes charge confirmation");
        check(timing.horizon(false, 0, 20, 0, 0, 0) == 3, "New anchor includes all remaining stages");
        timing.observed(AnchorSequence.Action.PLACE, 9);
        check(timing.horizon(false, 0, 20, 0, 0, 0) == 5, "Observed slow placement extends bounded prediction");
        check(timing.horizon(true, 1, 20, 0, 0, 0) == 1, "Placement latency does not inflate charged-anchor prediction");
        check(timing.horizon(false, 0, 4, 20, 20, 20) == 4, "Configured cap bounds long stage delays");
        check(timing.horizon(false, 0, 0, 0, 0, 0) == 0, "Zero cap disables prediction");
        timing.observed(AnchorSequence.Action.WAIT, 99);
        timing.reset();
        check(timing.horizon(false, 0, 20, 0, 0, 0) == 3, "New world discards previous timing samples");
        check(AnchorTiming.ready(3, Integer.MIN_VALUE, 20), "First action should not wait for fictitious previous send");
        check(!AnchorTiming.ready(6, 5, 2) && AnchorTiming.ready(7, 5, 2), "Stage delay boundary");
        check(!AnchorTiming.ready(4, 5, 0), "Tick rollback must not skip delay");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
