package dev.guizhou.features.commands.impl;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.guizhou.event.system.EventBus;
import dev.guizhou.features.commands.Command;
import dev.guizhou.manager.CommandManager;
import dev.guizhou.util.elytra.FlightPlanningTask;
import dev.guizhou.util.diagnostics.ClientDiagnostics;

public class TimingsCommand extends Command {

    public TimingsCommand() {
        super("timings");
        setDescription("Inclusive local timings: .timings [topN] reads retained data; .timings reset starts a fresh 60s window.");
    }

    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        builder.executes(ctx -> report(15))
                .then(literal("reset")
                        .executes(ctx -> {
                            ClientDiagnostics.start(60);
                            return success("Fresh local 60s timing window started. Play, then .timings; no upload.");
                        }))
                .then(argument("topN", IntegerArgumentType.integer(1, 50))
                        .executes(ctx -> report(IntegerArgumentType.getInteger(ctx, "topN"))));
    }

    private int report(int n) {
        if (ClientDiagnostics.snapshot().startedAtEpochMillis() == 0) {
            ClientDiagnostics.start(60);
            return success("Local timing window opened for 60s. Play, then .timings again; no upload.");
        }
        var flight = FlightPlanningTask.diagnostics();
        sendMessage("Flight workers: " + flight.workers() + ", active: " + flight.active()
                + ", queued: " + flight.queued() + ", completed runs: " + flight.completedRuns());
        String report = EventBus.timingsReport(n);
        if (report.isEmpty()) {
            return success("No timed calls in this capture. .timings reset starts a fresh 60s window.");
        }
        for (String line : report.split("\n")) {
            if (!line.isBlank()) sendMessage(line);
        }
        return success("Inclusive elapsed time: listener/module/inner scopes overlap; do not sum them. P95 is approximate. .timings reset restarts capture.");
    }
}
