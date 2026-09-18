package dev.guizhou.features.commands.impl;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.guizhou.GuizhouWare;
import dev.guizhou.event.system.EventBus;
import dev.guizhou.features.commands.Command;
import dev.guizhou.manager.CommandManager;
import dev.guizhou.util.elytra.FlightPlanningTask;

public class TimingsCommand extends Command {

    public TimingsCommand() {
        super("timings");
        setDescription("Listener self-time profile: .timings [topN] | .timings reset");
    }

    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        builder.executes(ctx -> report(15))
                .then(literal("reset")
                        .executes(ctx -> {
                            EventBus.resetTimings();
                            return success("Timings reset — play a fight, then .timings");
                        }))
                .then(argument("topN", IntegerArgumentType.integer(1, 50))
                        .executes(ctx -> report(IntegerArgumentType.getInteger(ctx, "topN"))));
    }

    private int report(int n) {
        // timings only accumulate while a profiling window is open — open one
        // covering the next minute so ".timings" alone collects fresh data
        EventBus.openTimingWindow(60);
        var flight = FlightPlanningTask.diagnostics();
        sendMessage("Flight workers: " + flight.workers() + ", active: " + flight.active()
                + ", queued: " + flight.queued() + ", completed runs: " + flight.completedRuns());
        String report = EventBus.timingsReport(n);
        if (report.isEmpty()) {
            return success("Timing window opened for 60s — play, then .timings again");
        }
        for (String line : report.split("\n")) {
            if (!line.isBlank()) sendMessage(line);
        }
        return success("(window re-opened 60s — rerun .timings for fresh numbers)");
    }
}
