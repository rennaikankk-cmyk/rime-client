package dev.guizhou.features.commands.impl;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.modules.render.WaypointsModule;
import dev.guizhou.manager.CommandManager;
import dev.guizhou.util.world.WaypointMath;

import java.util.List;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;

public class WaypointCommand extends Command {
    public WaypointCommand() {
        super("waypoint", "waypoints", "wp");
        setDescription("Manages waypoints: add/del/list/toggle/clear, coords shows nether/overworld conversion");
    }

    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        builder.then(literal("add")
                        .then(argument("name", word())
                                .executes(ctx -> {
                                    if (nullCheck()) return fail("You need to be in a world to do that.");
                                    return add(getString(ctx, "name"), (int) Math.floor(mc.player.getX()),
                                            (int) Math.floor(mc.player.getY()), (int) Math.floor(mc.player.getZ()));
                                })
                                .then(argument("x", IntegerArgumentType.integer())
                                        .then(argument("y", IntegerArgumentType.integer())
                                                .then(argument("z", IntegerArgumentType.integer())
                                                        .executes(ctx -> add(getString(ctx, "name"),
                                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                                IntegerArgumentType.getInteger(ctx, "z"))))))))
                .then(literal("del")
                        .then(argument("name", word())
                                .executes(ctx -> {
                                    String name = getString(ctx, "name");
                                    return module().remove(name) ? success("Removed waypoint {green} %s", name)
                                            : fail("No waypoint named %s", name);
                                })))
                .then(literal("toggle")
                        .then(argument("name", word())
                                .executes(ctx -> {
                                    String name = getString(ctx, "name");
                                    Boolean visible = module().toggle(name);
                                    if (visible == null) return fail("No waypoint named %s", name);
                                    return success("Waypoint %s is now %s", name, visible ? "{green}shown" : "{red}hidden");
                                })))
                .then(literal("list").executes(ctx -> list()))
                .then(literal("clear")
                        .executes(ctx -> success("Removed %s waypoint(s)", module().clear(null)))
                        .then(literal("deaths").executes(ctx ->
                                success("Removed %s death point(s)", module().clear(WaypointsModule.Kind.DEATH)))))
                .then(literal("coords").executes(ctx -> coords()))
                .executes(ctx -> list());
    }

    private WaypointsModule module() {
        return WaypointsModule.get();
    }

    private int add(String name, int x, int y, int z) {
        if (nullCheck()) return fail("You need to be in a world to do that.");
        if (!module().add(name, x, y, z)) return fail("A waypoint named %s already exists", name);
        return success("Added waypoint {green} %s {reset} at %s, %s, %s", name, x, y, z);
    }

    private int list() {
        if (nullCheck()) return fail("You need to be in a world to do that.");
        List<WaypointsModule.Waypoint> all = module().list();
        if (all.isEmpty()) return success("No waypoints on this server.");
        String here = module().dimension();
        for (WaypointsModule.Waypoint w : all) {
            double[] local = WaypointMath.convert(w.x, w.z, w.dimension, here);
            String distance = local == null ? "" : String.format(" {gray}%.0fm", Math.hypot(local[0] - mc.player.getX(), local[1] - mc.player.getZ()));
            sendMessage("%s%s {reset}[%s %s] %s, %s, %s%s", w.visible ? "{green}" : "{gray}", w.name, w.kind.name().toLowerCase(),
                    WaypointMath.shortDimension(w.dimension), w.x, w.y, w.z, distance);
        }
        return success("%s waypoint(s)", all.size());
    }

    private int coords() {
        if (nullCheck()) return fail("You need to be in a world to do that.");
        String here = module().dimension();
        String other = WaypointMath.NETHER.equals(here) ? WaypointMath.OVERWORLD : WaypointMath.NETHER;
        double[] converted = WaypointMath.convert(mc.player.getX(), mc.player.getZ(), here, other);
        if (converted == null) return fail("No coordinate conversion from %s", WaypointMath.shortDimension(here));
        return success("%s: %s, %s → %s: %s, %s", WaypointMath.shortDimension(here),
                (int) Math.floor(mc.player.getX()), (int) Math.floor(mc.player.getZ()),
                WaypointMath.shortDimension(other), (int) Math.floor(converted[0]), (int) Math.floor(converted[1]));
    }
}
