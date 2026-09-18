package dev.guizhou.util.telemetry;

import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Drives the real accumulator and renderer: window math, gap/correction segmentation,
 * firework coverage, requested-vs-applied, discard rules and the exact server field set.
 * With a file argument, dumps rendered client events as NDJSON for the receiver's
 * cross-validation test (marked as test data).
 */
public final class FlightSummaryTest {
    private static final long MS = 1_000_000L;
    private static int checks;

    public static void main(String[] args) throws Exception {
        levelAndClimbSpeed();
        gapAndJumpInvalidation();
        correctionSegmentsDisplacement();
        inactivePauseCutsUnobservedTime();
        smallWindowsDiscarded();
        flushBoundary();
        fireworkCoverage();
        requestedVersusApplied();
        rocketBlockAccounting();
        rendererContract();
        if (args.length > 0) writeFixture(Path.of(args[0]));
        System.out.println("FlightSummaryTest passed: " + checks + " checks");
    }

    /** A short inactivity stretch must not be averaged in, even below the gap threshold. */
    private static void inactivePauseCutsUnobservedTime() {
        FlightSummary window = new FlightSummary();
        for (int tick = 0; tick <= 12; tick++) {
            window.tick(tick * 50L * MS, 1, 0, 0, true, true, false, false, true, 20, 1, 1, FlightSummary.BLOCK_NONE);
        }
        // 450ms inactive below the 1.5s gap threshold, then one resuming sample.
        window.pause();
        window.tick(1_050 * MS, 1, 0, 0, true, true, false, false, true, 20, 1, 1, FlightSummary.BLOCK_NONE);
        FlightSummary.Window result = window.close(1_100 * MS);
        check(result != null, "The window survives a short pause");
        check(result.observedMs() == 600 && result.armorflyMs() == 600,
                "Idle time is excluded from observed and state coverage");
        check(result.gaps() == 0, "A pause cuts the interval without inventing a data gap");
        check(Math.abs(result.horizontalBlocks() - 12) < 1e-9,
                "Displacement across the pause is dropped");

        FlightSummary resumed = new FlightSummary();
        for (int tick = 0; tick <= 12; tick++) {
            resumed.tick(tick * 50L * MS, 1, 0, 0, true, true, false, false, true, 20, 1, 1, FlightSummary.BLOCK_NONE);
        }
        resumed.pause();
        resumed.tick(2_000 * MS, 1, 0, 0, true, true, false, false, true, 20, 1, 1, FlightSummary.BLOCK_USING_ITEM);
        FlightSummary.Window after = resumed.close(2_050 * MS);
        check(after != null && after.rocketBlocks()[FlightSummary.BLOCK_USING_ITEM] == 1,
                "A fresh reason after a pause is counted exactly once");
    }

    private static void levelAndClimbSpeed() {
        FlightSummary window = new FlightSummary();
        for (int tick = 0; tick <= 20; tick++) {
            window.tick(tick * 50 * MS, 1.0, tick <= 10 ? 0.5 : -0.5, 0,
                    true, true, true, false, true, 20, 1.7, 1.6, FlightSummary.BLOCK_NONE);
        }
        FlightSummary.Window result = window.close(21 * 50 * MS);
        check(result != null, "A full window is kept");
        check(result.observedMs() == 1000, "Accepted intervals sum to observed time");
        check(Math.abs(result.horizontalBlocks() - 20) < 1e-9 && Math.abs(result.horizontalBps() - 20) < 1e-9,
                "Level displacement and speed use accepted intervals only");
        check(Math.abs(result.ascentBlocks() - 5) < 1e-9 && Math.abs(result.descentBlocks() - 5) < 1e-9,
                "Ascent and descent blocks are separated");
        check(Math.abs(result.ascentBps() - 10) < 1e-9, "Ascent speed is per ascent-active time");
        check(Math.abs(result.verticalBps()) < 1e-9, "Net vertical speed reflects the level return");
        check(result.glidingMs() == 1000 && result.armorflyMs() == 1000 && result.chaseMs() == 1000,
                "State coverage uses accepted interval time");
        check(result.diveMs() == 0, "No dive time in a non-dive window");
    }

    private static void gapAndJumpInvalidation() {
        FlightSummary window = new FlightSummary();
        window.tick(0, 1, 0, 0, true, false, false, false, false, 20, Double.NaN, Double.NaN, FlightSummary.BLOCK_NONE);
        window.tick(50 * MS, 1, 0, 0, true, false, false, false, false, 20, Double.NaN, Double.NaN, FlightSummary.BLOCK_NONE);
        window.tick(2_000 * MS, 30, 0, 0, true, false, false, false, false, 20, Double.NaN, Double.NaN, FlightSummary.BLOCK_NONE);
        for (int tick = 3; tick < 12; tick++) {
            window.tick((2_000 + (tick - 2) * 50L) * MS, 1, 0, 0, true, false, false, false, false, 20, Double.NaN, Double.NaN, FlightSummary.BLOCK_NONE);
        }
        FlightSummary.Window result = window.close(2_550 * MS);
        check(result != null && result.gaps() == 1, "A long sampling gap marks one segment break");
        check(Math.abs(result.horizontalBlocks() - 10) < 1e-9,
                "Displacement across the gap is dropped, not counted as speed");

        FlightSummary jump = new FlightSummary();
        jump.tick(0, 1, 0, 0, true, false, false, false, false, 20, Double.NaN, Double.NaN, FlightSummary.BLOCK_NONE);
        jump.tick(50 * MS, 60, 0, 0, true, false, false, false, false, 20, Double.NaN, Double.NaN, FlightSummary.BLOCK_NONE);
        FlightSummary.Window jumped = jump.close(100 * MS);
        check(jumped == null || jumped.horizontalBlocks() < 10,
                "A teleport-like interval cannot appear as high speed");
    }

    private static void correctionSegmentsDisplacement() {
        FlightSummary window = new FlightSummary();
        window.tick(0, 1, 0, 0, true, true, false, false, true, 20, 1, 1, FlightSummary.BLOCK_NONE);
        window.tick(50 * MS, 1, 0, 0, true, true, false, false, true, 20, 1, 1, FlightSummary.BLOCK_NONE);
        window.correction();
        window.tick(100 * MS, 40, 0, 0, true, true, false, false, true, 20, 1, 1, FlightSummary.BLOCK_NONE);
        for (int tick = 3; tick <= 12; tick++) {
            window.tick(tick * 50L * MS, 1, 0, 0, true, true, false, false, true, 20, 1, 1, FlightSummary.BLOCK_NONE);
        }
        FlightSummary.Window result = window.close(600 * MS);
        check(result != null && result.corrections() == 1, "Corrections are counted in the window");
        check(Math.abs(result.horizontalBlocks() - 11) < 1e-9,
                "The correction crossing displacement is excluded");
    }

    private static void smallWindowsDiscarded() {
        FlightSummary few = new FlightSummary();
        for (int tick = 0; tick < 9; tick++) few.tick(tick * 50L * MS, 1, 0, 0, true, true, false, false, false, 20, 1, 1, FlightSummary.BLOCK_NONE);
        check(few.close(9 * 50 * MS) == null, "Too few ticks discards the window");
        FlightSummary shortObserved = new FlightSummary();
        for (int tick = 0; tick < 10; tick++) shortObserved.tick(tick * 10L * MS, 1, 0, 0, true, true, false, false, false, 20, 1, 1, FlightSummary.BLOCK_NONE);
        check(shortObserved.close(100 * MS) == null, "Too little observed time discards the window");
    }

    private static void flushBoundary() {
        FlightSummary window = new FlightSummary();
        window.tick(0, 1, 0, 0, true, true, false, false, false, 20, 1, 1, FlightSummary.BLOCK_NONE);
        check(!window.shouldFlush(10_000_000_000L - 1), "Before the window target no flush");
        check(window.shouldFlush(10_000_000_000L), "The window target flushes");
    }

    private static void fireworkCoverage() {
        FlightSummary window = new FlightSummary();
        for (int tick = 0; tick <= 20; tick++) {
            boolean attached = tick % 2 == 0;
            window.tick(tick * 50 * MS, 1, 0, 0, true, false, false, false, attached, 20, Double.NaN, Double.NaN, FlightSummary.BLOCK_NONE);
        }
        FlightSummary.Window result = window.close(21 * 50 * MS);
        check(result.boostAttachedMs() == 500 && result.boostDetachedMs() == 500,
                "Attached and detached coverage split while gliding");

        FlightSummary grounded = new FlightSummary();
        for (int tick = 0; tick <= 20; tick++) {
            grounded.tick(tick * 50 * MS, 1, 0, 0, false, false, false, false, tick % 2 == 0, 20, Double.NaN, Double.NaN, FlightSummary.BLOCK_NONE);
        }
        FlightSummary.Window landed = grounded.close(21 * 50 * MS);
        check(landed.boostAttachedMs() == 0 && landed.boostDetachedMs() == 0,
                "Firework coverage only accumulates while gliding");
    }

    private static void requestedVersusApplied() {
        FlightSummary window = new FlightSummary();
        for (int tick = 0; tick <= 20; tick++) {
            window.tick(tick * 50 * MS, 1, 0, 0, true, true, false, false, true, 20,
                    tick % 2 == 0 ? 1.7 : Double.NaN, tick % 2 == 0 ? 1.4 : Double.NaN, FlightSummary.BLOCK_NONE);
        }
        FlightSummary.Window result = window.close(21 * 50 * MS);
        check(result.appliedSamples() == 11, "Only ticks with a fresh applied sample count");
        check(Math.abs(result.requestedBpt() - 1.7) < 1e-9 && Math.abs(result.appliedBpt() - 1.4) < 1e-9,
                "Requested and applied averages use the same sample set");
    }

    private static void rocketBlockAccounting() {
        FlightSummary window = new FlightSummary();
        int[] codes = {FlightSummary.BLOCK_NONE, FlightSummary.BLOCK_USING_ITEM, FlightSummary.BLOCK_NO_ROCKET,
                FlightSummary.BLOCK_USING_ITEM, FlightSummary.BLOCK_NONE, FlightSummary.BLOCK_NONE,
                FlightSummary.BLOCK_NONE, FlightSummary.BLOCK_NONE, FlightSummary.BLOCK_NONE,
                FlightSummary.BLOCK_NONE, FlightSummary.BLOCK_NONE, 99};
        for (int tick = 0; tick < codes.length; tick++) {
            window.tick(tick * 50L * MS, 1, 0, 0, true, true, false, false, true, 20, 1, 1, codes[tick]);
        }
        FlightSummary.Window result = window.close(codes.length * 50L * MS);
        check(result != null && result.rocketBlocks()[FlightSummary.BLOCK_USING_ITEM] == 2
                        && result.rocketBlocks()[FlightSummary.BLOCK_NO_ROCKET] == 1,
                "Explicit rocket gate reasons are counted per code");
        int unknown = 0;
        for (int code = 1; code <= FlightSummary.BLOCK_REASONS; code++) {
            if (code != FlightSummary.BLOCK_USING_ITEM && code != FlightSummary.BLOCK_NO_ROCKET) {
                unknown += result.rocketBlocks()[code];
            }
        }
        check(unknown == 0, "Out-of-range and none codes are not counted as blocks");
    }

    private static void rendererContract() {
        FlightSummary window = new FlightSummary();
        for (int tick = 0; tick <= 20; tick++) {
            window.tick(tick * 50 * MS, 1, 0.25, 0, true, true, true, false,
                    tick % 3 != 0, 20, 1.7, 1.4, FlightSummary.BLOCK_REFIRE);
        }
        FlightSummary.Window result = window.close(21 * 50 * MS);
        JsonObject config = new JsonObject();
        config.addProperty("flight_mode", "ROTATION");
        config.addProperty("speed", 1.67);
        JsonObject data = FlightSummary.render(result, "MACE", config);
        Set<String> expected = Set.of("wall_ms", "observed_ms", "ticks", "gaps", "gliding_ms", "armorfly_ms",
                "chase_ms", "mace_dive_ms", "chase_mode", "horizontal_blocks", "ascent_blocks", "descent_blocks",
                "horizontal_bps", "vertical_bps", "ascent_bps", "nominal_bps", "requested_bpt", "applied_bpt",
                "applied_samples", "boost_attached_ms", "boost_detached_ms", "rocket_blocks", "corrections", "config");
        check(data.keySet().equals(expected), "Renderer emits exactly the server flight_summary fields");
        check(data.get("chase_mode").getAsString().equals("MACE"), "Chase mode is carried");
        JsonObject blocks = data.getAsJsonObject("rocket_blocks");
        check(blocks.keySet().equals(Set.of("not_enabled", "using_item", "mace_descent", "speed_gate",
                        "refire", "useful_boost", "no_rocket", "trajectory"))
                && blocks.get("refire").getAsInt() == 21,
                "Rocket blocks render the eight explicit reasons");
        check(data.get("observed_ms").getAsLong() == 1000 && data.get("wall_ms").getAsLong() >= 1000,
                "Wall time spans the window; observed time excludes the first tick");
        check(Double.isFinite(data.get("horizontal_bps").getAsDouble()), "Rendered speeds are finite");
    }

    /** Client-side fixture for the receiver's cross-validation test; marked as test data. */
    private static void writeFixture(Path path) throws Exception {
        StringBuilder lines = new StringBuilder();
        List<String> modes = List.of("MACE", "SPEAR");
        for (int index = 0; index < modes.size(); index++) {
            FlightSummary window = new FlightSummary();
            for (int tick = 0; tick <= 200; tick++) {
                window.tick(tick * 50L * MS, 1.2, index == 0 && tick < 100 ? 0.4 : -0.1, 0.1,
                        true, index == 0, index == 1, index == 0 && tick > 180, tick % 3 != 0,
                        19.5 + tick % 5, 1.7, 1.45, tick % 7 == 0 ? FlightSummary.BLOCK_REFIRE : FlightSummary.BLOCK_NONE);
            }
            FlightSummary.Window result = window.close(201 * 50L * MS);
            JsonObject config = new JsonObject();
            config.addProperty("flight_mode", "ROTATION");
            config.addProperty("motion_mode", "FIREWORKS");
            config.addProperty("speed", 1.67);
            config.addProperty("auto_rescale", true);
            config.addProperty("rescale_amount", 1.7);
            config.addProperty("y_cap", 8.0);
            config.addProperty("corner_climb", true);
            config.addProperty("armor_fly", index == 0);
            config.addProperty("grim_boost", false);
            config.addProperty("pullup_angle", 0.0);
            config.addProperty("chase_mode", modes.get(index));
            config.addProperty("mace_adaptive", true);
            config.addProperty("mace_terminal", true);
            config.addProperty("mace_height", 10.0);
            config.addProperty("mace_min_fall", 1.5);
            config.addProperty("auto_mace_dive", index == 0);
            config.addProperty("combat_range", 10.0);
            JsonObject event = new JsonObject();
            event.addProperty("type", "flight_summary");
            event.addProperty("t", 1_700_000_000_000L + index);
            event.add("data", FlightSummary.render(result, modes.get(index), config));
            lines.append(event).append('\n');
        }
        Files.writeString(path, lines.toString(), StandardCharsets.UTF_8);
        System.out.println("FlightSummaryTest fixture written: " + path);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
