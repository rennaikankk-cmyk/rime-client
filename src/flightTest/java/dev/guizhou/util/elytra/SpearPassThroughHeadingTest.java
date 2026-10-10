package dev.guizhou.util.elytra;

import net.minecraft.world.phys.Vec3;

/** Headless checks of the close-range pass-through retention ported from SlimefunHelper SpearAura. */
public final class SpearPassThroughHeadingTest {
    private static int checks;

    private static final int HOLD = 20;
    private static final double DEAD = 0.2;

    public static void main(String[] args) {
        redirectWhenTargetBehind();
        deadZoneAndDegenerateInputs();
        retentionBoundedAndFrozen();
        targetScope();
        System.out.println("SpearPassThroughHeadingTest passed: " + checks + " checks");
    }

    private static void redirectWhenTargetBehind() {
        SpearPassThroughHeading heading = new SpearPassThroughHeading();
        Vec3 request = new Vec3(-6.0, -2.0, 0.0);   // target behind: we fly +X
        Vec3 movement = new Vec3(0.8, 0.2, 0.0);    // real motion, vertical ignored
        Vec3 out = heading.apply(7, request, movement, 100, HOLD, DEAD);
        check(out.x == 6.0 && out.z == 0.0, "horizontal redirects onto the crossing direction");
        check(out.y == -2.0, "the request's vertical component is never touched");
        check(heading.retaining(), "retention is active");
        double len = Math.sqrt(out.x * out.x + out.z * out.z);
        check(Math.abs(len - 6.0) < 1.0E-9, "request magnitude is preserved");
    }

    private static void deadZoneAndDegenerateInputs() {
        SpearPassThroughHeading heading = new SpearPassThroughHeading();
        Vec3 movement = new Vec3(0.8, 0.0, 0.0);
        check(heading.apply(7, new Vec3(0.0, -3.0, 6.0), movement, 100, HOLD, DEAD)
                        .equals(new Vec3(0.0, -3.0, 6.0)),
                "perpendicular graze stays in the dead zone");
        check(heading.apply(7, new Vec3(-6.0, 0.0, 0.0), new Vec3(0.01, 0.0, 0.0), 100, HOLD, DEAD)
                        .equals(new Vec3(-6.0, 0.0, 0.0)),
                "no meaningful movement keeps the request");
        check(heading.apply(7, new Vec3(0.0, -5.0, 0.0), movement, 100, HOLD, DEAD)
                        .equals(new Vec3(0.0, -5.0, 0.0)),
                "a vertical request has no horizontal choice to make");
        check(!heading.retaining(), "none of those started a retention");
    }

    private static void retentionBoundedAndFrozen() {
        SpearPassThroughHeading heading = new SpearPassThroughHeading();
        heading.apply(7, new Vec3(-6.0, -1.0, 0.0), new Vec3(0.8, 0.0, 0.0), 100, HOLD, DEAD);
        // Motion drifts sideways mid-pass: the retained crossing stays the one that started it,
        // while the magnitude comes from the current request (sqrt(0.1^2 + 6^2) here).
        Vec3 drifted = heading.apply(7, new Vec3(-0.1, -1.0, -6.0), new Vec3(0.0, 0.0, 0.8), 105, HOLD, DEAD);
        check(Math.abs(drifted.x - Math.sqrt(36.01)) < 1.0E-9 && drifted.z == 0.0 && drifted.y == -1.0,
                "retention keeps its entry direction through the pass");

        Vec3 ahead = heading.apply(7, new Vec3(6.0, 0.0, 0.0), new Vec3(0.8, 0.0, 0.0), 110, HOLD, DEAD);
        check(ahead.equals(new Vec3(6.0, 0.0, 0.0)) && !heading.retaining(),
                "target ahead of the movement ends the pass");

        heading.apply(7, new Vec3(-6.0, 0.0, 0.0), new Vec3(0.8, 0.0, 0.0), 200, HOLD, DEAD);
        Vec3 expired = heading.apply(7, new Vec3(-6.0, 0.0, 0.0), new Vec3(0.8, 0.0, 0.0), 220, HOLD, DEAD);
        check(expired.equals(new Vec3(-6.0, 0.0, 0.0)) && !heading.retaining(),
                "a pass older than the hold bound falls back to the request for that tick");
        Vec3 restarted = heading.apply(7, new Vec3(-6.0, 0.0, 0.0), new Vec3(0.8, 0.0, 0.0), 221, HOLD, DEAD);
        check(restarted.x == 6.0 && heading.retaining(),
                "the next behind-target tick starts a fresh pass");
    }

    private static void targetScope() {
        SpearPassThroughHeading heading = new SpearPassThroughHeading();
        heading.apply(7, new Vec3(-6.0, 0.0, 0.0), new Vec3(0.8, 0.0, 0.0), 100, HOLD, DEAD);
        check(heading.retaining(), "retention held for target 7");
        Vec3 otherTarget = heading.apply(8, new Vec3(6.0, 0.0, 0.0), new Vec3(0.8, 0.0, 0.0), 101, HOLD, DEAD);
        check(otherTarget.equals(new Vec3(6.0, 0.0, 0.0)),
                "another target's geometry is not bent by target 7's retention");
        heading.reset();
        check(!heading.retaining(), "reset clears the state");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
