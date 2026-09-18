package dev.guizhou.features.commands.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.commands.Command;
import dev.guizhou.manager.CommandManager;

public class TelemetryCommand extends Command {
    public TelemetryCommand() {
        super("telemetry");
        setDescription("Anonymous combat/correction diagnostics: telemetry on | off | status. Saved across restarts.");
    }

    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        builder.executes(context -> status())
                .then(literal("status").executes(context -> status()))
                .then(literal("on").executes(context -> set(true)))
                .then(literal("off").executes(context -> set(false)));
    }

    private int set(boolean enabled) {
        GuizhouWare.telemetryManager.setActive(enabled);
        return status();
    }

    private int status() {
        var manager = GuizhouWare.telemetryManager;
        String status = !manager.isActive() ? "OFF" : manager.canUpload() ? "ON"
                : manager.capabilityExpired() ? "OFF (upload capability expired; update through the launcher)"
                : "OFF (no upload capability in this jar)";
        sendMessage("[Diagnostics] " + status + ". No chat, names, player UUIDs or coordinates are uploaded.");
        if (!manager.preferencesSaved()) sendMessage("{red} Could not save diagnostics preference. Check guizhouware/telemetry.json permissions.");
        return NO_OP;
    }
}
