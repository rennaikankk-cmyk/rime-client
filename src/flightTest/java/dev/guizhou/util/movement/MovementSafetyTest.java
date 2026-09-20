package dev.guizhou.util.movement;

public final class MovementSafetyTest {
    public static void main(String[] args) {
        check(GroundSpoofPolicy.falling(3.0, 2.5, -0.5, false), "Ordinary descending fall must trigger");
        check(!GroundSpoofPolicy.falling(2.0, 2.5, -0.5, false), "Short jump must not trigger");
        check(!GroundSpoofPolicy.falling(4.0, 2.5, 0.2, false), "Upward knockback must not trigger");
        check(!GroundSpoofPolicy.falling(4.0, 2.5, -0.2, true), "Actual landing retains vanilla ground");
        check(!GroundSpoofPolicy.falling(Double.NaN, 2.5, -0.2, false), "Invalid distance must not trigger");
        check(GroundSpoofPolicy.hideGround(true, true, true, false, 0, -0.078, false, false),
                "Stable ordinary ground movement can suppress exhaustion");
        check(!GroundSpoofPolicy.hideGround(true, true, false, false, 0, 0, false, false),
                "First landing packet must reach the server");
        check(!GroundSpoofPolicy.hideGround(true, true, true, true, 0, 0, false, false),
                "Jump intent must keep vanilla ground semantics");
        check(!GroundSpoofPolicy.hideGround(true, false, true, false, 3, -0.5, false, true),
                "NoFall ground must not be overwritten by AntiHunger");
        check(!GroundSpoofPolicy.hideGround(true, true, true, false, 0, 0, true, false),
                "Mining must retain grounded tool speed");

        NoSlowSpoofSession session = new NoSlowSpoofSession();
        session.begin(100, 2, 2, 3, 1);
        check(!session.poll(100, 2, true), "Start delay must apply");
        check(!session.poll(101, 2, true), "Second startup tick must not send");
        check(session.poll(102, 2, true), "Consumption after delay must send");
        for (int i = 0; i < 50; i++) check(!session.poll(102, 2, true), "Packet burst exceeded tick limit");
        check(session.poll(103, 2, true) && session.poll(104, 2, true), "Remaining sends should run on later ticks");
        check(!session.poll(105, 2, true), "Packet cap must bound a long consume session");
        session.begin(200, 2, 0, 3, 2);
        check(!session.poll(200, 2, false), "A use packet without actual consumption must not spoof");
        check(session.poll(201, 2, true), "First observed consume can use the window");
        check(!session.poll(202, 2, true) && session.poll(203, 2, true), "Configured interval must be respected");
        check(!session.poll(204, 3, true) && !session.active(), "Changing tools must invalidate the session");
        session.begin(300, 2, 0, 3, 1);
        check(!session.poll(0, 2, true) && !session.active(), "A new player tick epoch must invalidate old work");
        session.begin(400, 2, 0, 3, 1);
        session.reset();
        check(!session.poll(400, 2, true), "Disabling must discard pending sends");
        System.out.println("Movement safety checks passed: landing/fall/mining precedence and bounded consume sessions.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
