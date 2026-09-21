package dev.guizhou.manager;

import dev.guizhou.util.models.TickRateWindow;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;


import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

public final class TPSCounterServiceTest {
    private static final long SECOND = 1_000_000_000L;
    private static int checks;

    private TPSCounterServiceTest() {
    }

    public static void main(String[] args) {
        checkPacketTiming();
        checkInvalidSamples();
        checkRingRolloverAndReset();
        checkRelativeCorrections();
        System.out.println("TPS checks passed: " + checks + " timing, reset and relative correction checks.");
    }

    private static void checkPacketTiming() {
        TickRateWindow window = new TickRateWindow(20);
        near(window.add(100, -SECOND).average(), 20.0, "Initial sample");
        near(window.add(120, 0).latest(), 20.0, "Normal arrival interval");
        near(window.add(140, 2 * SECOND).latest(), 10.0, "Slow server");
        TickRateWindow.Snapshot delayed = window.add(180, 4 * SECOND);
        near(delayed.latest(), 20.0, "Skipped update uses actual server tick delta");
        near(delayed.average(), 50.0 / 3, "Average uses accepted samples");
        near(delayed.minimum(), 10.0, "Minimum uses accepted samples");
        near(window.add(200, 4 * SECOND + 1).latest(), 20.0, "Burst is capped");
        near(delayed.latest(), 20.0, "Published snapshots remain immutable");
    }

    private static void checkInvalidSamples() {
        TickRateWindow window = new TickRateWindow(20);
        window.add(0, 0);
        TickRateWindow.Snapshot slow = window.add(20, 2 * SECOND);
        same(window.add(20, 3 * SECOND), slow, "Duplicate server tick");
        same(window.add(19, 3 * SECOND), slow, "Backwards server tick");
        same(window.add(40, 2 * SECOND), slow, "Repeated arrival timestamp");
        same(window.add(40, SECOND), slow, "Backwards arrival timestamp");
        near(window.add(40, 4 * SECOND).latest(), 10.0, "Invalid samples cannot shift the valid baseline");

        window.reset();
        window.add(-1, 0);
        same(window.add(Long.MAX_VALUE, SECOND), TickRateWindow.DEFAULT, "Overflowing tick delta");
        near(window.add(19, SECOND).latest(), 20.0, "Overflow cannot poison the baseline");

        window.reset();
        window.add(0, Long.MAX_VALUE - SECOND / 2);
        near(window.add(20, Long.MIN_VALUE + SECOND / 2 - 1).latest(), 20.0, "nanoTime rollover");
    }

    private static void checkRingRolloverAndReset() {
        TickRateWindow window = new TickRateWindow(2);
        window.add(0, 0);
        window.add(10, SECOND);
        window.add(15, 2 * SECOND);
        TickRateWindow.Snapshot wrapped = window.add(35, 3 * SECOND);
        near(wrapped.average(), 12.5, "Full ring evicts the oldest sample");
        near(wrapped.minimum(), 5.0, "Ring minimum");
        near(window.add(55, 4 * SECOND).minimum(), 20.0, "Evicted minimum is removed");
        same(window.reset(), TickRateWindow.DEFAULT, "Disconnect resets published rates");
        same(window.add(5, 10 * SECOND), TickRateWindow.DEFAULT, "First packet in new session is not a delta");
        near(window.add(25, 11 * SECOND).average(), 20.0, "New session ignores old history");
    }

    // 1.21.1: packets carry raw axis values plus the relative-argument set; the
    // service math is Vec3-based, so build before/after positions explicitly.
    private static void checkRelativeCorrections() {
        Vec3 before = new Vec3(1000, 70, -2000);
        // 1.21.1 packets carry raw axis values: relative axes are deltas added to the
        // current position, absolute axes are used verbatim.
        near(distance(before, 0, 0, 0, Set.of(RelativeMovement.X, RelativeMovement.Y, RelativeMovement.Z)), 0.0,
                "Relative zero does not imply a large teleport");
        near(distance(before, 1, 2, 2, Set.of(RelativeMovement.X, RelativeMovement.Y, RelativeMovement.Z)), 9.0,
                "Relative displacement preserves the three-block boundary");
        near(distance(before, 3, 70, -2000, Set.of(RelativeMovement.X)), 9.0,
                "Mixed relative and absolute axes");
        near(distance(before, 1004, 70, -2000, Set.of()), 16.0, "Absolute teleport");
        near(distance(before, 1000, 70, -2000, Set.of(RelativeMovement.Y_ROT, RelativeMovement.X_ROT)), 0.0,
                "Relative rotation does not change position");
    }

    private static double distance(Vec3 before, double x, double y, double z,
                                   Set<RelativeMovement> relatives) {
        ClientboundPlayerPositionPacket packet = new ClientboundPlayerPositionPacket(x, y, z, 10, 10, relatives, 0);
        return TPSCounterService.correctionDistanceSquared(before, packet);
    }

    private static void same(Object actual, Object expected, String message) {
        checks++;
        if (actual != expected) throw new AssertionError(message);
    }

    private static void near(double actual, double expected, String message) {
        checks++;
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > 0.00001) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
}
