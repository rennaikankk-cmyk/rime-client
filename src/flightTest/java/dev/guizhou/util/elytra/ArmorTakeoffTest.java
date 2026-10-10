package dev.guizhou.util.elytra;

public final class ArmorTakeoffTest {
    public static void main(String[] args) {
        int checks = 0;
        for (boolean requested : new boolean[]{false, true})
            for (boolean held : new boolean[]{false, true})
                for (boolean grounded : new boolean[]{false, true})
                    for (double speed : new double[]{-10, -.08, -Double.MIN_VALUE, -0.0, 0, .08, .42,
                            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                        boolean ready = ArmorTakeoff.ready(requested, held, grounded, speed);
                        boolean expected = requested && held && !grounded && Double.isFinite(speed) && speed < 0;
                        if (ready != expected) throw new AssertionError("Invalid held-jump takeoff: " + speed);
                        checks++;
                    }
        // A vanilla hop starts descending before the old ten-tick delay expires.
        double velocity = .42;
        int first = -1;
        for (int tick = 1; tick <= 10; tick++) {
            velocity = (velocity - .08) * .98;
            if (first < 0 && ArmorTakeoff.ready(true, true, false, velocity)) first = tick;
        }
        if (first < 1 || first >= 10) throw new AssertionError("Short hops must have time to launch a rocket before landing");
        if (ArmorTakeoff.ready(true, false, false, -.5)) throw new AssertionError("Free fall does not request manual takeoff");
        System.out.println("ArmorTakeoffTest passed: " + (checks + 2) + " checks");
    }
}
