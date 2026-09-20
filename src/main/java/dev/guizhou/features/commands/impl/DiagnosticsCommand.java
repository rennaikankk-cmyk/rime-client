package dev.guizhou.features.commands.impl;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.guizhou.features.commands.Command;
import dev.guizhou.manager.CommandManager;
import dev.guizhou.util.diagnostics.ClientDiagnostics;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Explicit local capture. Independent from the remote .telemetry preference/capability. */
public final class DiagnosticsCommand extends Command {
    public DiagnosticsCommand() {
        super("diagnostics", "diag");
        setDescription("Local performance diagnostics: start [1-60 seconds] | stop | status | export. No upload.");
    }

    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        builder.executes(ctx -> status())
                .then(literal("start").executes(ctx -> start(ClientDiagnostics.DEFAULT_SECONDS))
                        .then(argument("seconds", IntegerArgumentType.integer(1, ClientDiagnostics.MAX_SECONDS))
                                .executes(ctx -> start(IntegerArgumentType.getInteger(ctx, "seconds")))))
                .then(literal("stop").executes(ctx -> {
                    ClientDiagnostics.stop();
                    return status();
                }))
                .then(literal("status").executes(ctx -> status()))
                .then(literal("export").executes(ctx -> exportLocal()));
    }

    private int start(int seconds) {
        ClientDiagnostics.start(seconds);
        return success("Local diagnostics started for " + seconds + "s. .diag stop / status / export; no upload.");
    }

    private int status() {
        var snapshot = ClientDiagnostics.snapshot();
        if (snapshot.startedAtEpochMillis() == 0) return success("Local diagnostics OFF. .diag start [1-60] records a new window.");
        sendMessage(String.format(Locale.ROOT, "Local diagnostics %s: %.1fs captured, %.1fs remaining, %d timing scopes, %d counters.",
                snapshot.active() ? "RECORDING" : "STOPPED", snapshot.durationMillis() / 1000.0,
                snapshot.remainingMillis() / 1000.0, snapshot.timings().size(), snapshot.counters().size()));
        sendMessage(String.format(Locale.ROOT, "HUD render interval: mean %.2fms, peak %.2fms, P95~ %.2fms; GC %d / %dms; heap %.1fMiB.",
                snapshot.frameTiming().meanNanos() / 1e6, snapshot.frameTiming().maxNanos() / 1e6,
                snapshot.frameTiming().p95Nanos() / 1e6, snapshot.gcCollections(), snapshot.gcMillis(),
                snapshot.heapUsedBytes() / 1048576.0));
        if (snapshot.droppedKeys() != 0) sendMessage("Bounded storage rejected " + snapshot.droppedKeys() + " new-key samples.");
        return success(".timings shows inclusive elapsed scopes (nested totals overlap). .diag export writes local JSON only.");
    }

    private int exportLocal() {
        if (ClientDiagnostics.snapshot().startedAtEpochMillis() == 0) return fail("No capture to export. Use .diag start first.");
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        Path file = FabricLoader.getInstance().getGameDir().resolve("guizhouware").resolve("diagnostics")
                .resolve("diagnostics-" + stamp + ".json");
        try {
            return success("Local diagnostics saved: " + ClientDiagnostics.export(file));
        } catch (IOException | SecurityException exception) {
            return fail("Could not write local diagnostics (" + exception.getClass().getSimpleName() + ").");
        }
    }
}
